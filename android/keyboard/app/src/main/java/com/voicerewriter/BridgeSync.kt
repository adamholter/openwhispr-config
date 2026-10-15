package com.voicerewriter

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Optional private bridge sync. Configuration is provisioned after install into the app-private
 * `files/bridge_config.json`; no endpoint token or shared secret is compiled into the APK.
 * Every payload is written durably before network I/O and retried idempotently by WorkManager.
 */
object BridgeSync {
    private const val CONFIG = "bridge_config.json"
    private const val QUEUE = "bridge_pending.jsonl"
    private const val WORK = "openwispr-private-bridge"
    private const val RULES_STATUS = "mobile_rules_last_status.txt"
    private const val MAX_RULES_RESPONSE_BYTES = 5_000_000L
    private val json = "application/json; charset=utf-8".toMediaType()
    /** Tailscale IP for the ingest host, used only when the app cannot resolve MagicDNS. */
    @Volatile private var pinnedAddress: Pair<String, String>? = null

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                val pinned = pinnedAddress
                if (pinned != null && pinned.first.equals(hostname, ignoreCase = true))
                    listOf(java.net.InetAddress.getByName(pinned.second)) // TLS still verifies hostname
                else throw e
            }
        }).build()

    private fun rememberPinnedAddress(endpoint: String, config: JSONObject) {
        val ip = config.optString("endpoint_ip").trim()
        val host = endpoint.toHttpUrlOrNull()?.host
        pinnedAddress = if (host != null && Regex("^100\\.\\d+\\.\\d+\\.\\d+$").matches(ip)) host to ip else null
    }
    private val rulesClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Reads a bounded HTTP body without requiring the stream to contain the full bound. */
    internal fun readBounded(input: InputStream, maximum: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun isoTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    @Synchronized
    fun enqueue(context: Context, entry: DictationEntry) {
        val payload = JSONObject()
            .put("id", entry.id)
            .put("device_id", DEVICE_ID)
            .put("text", entry.after)
            .put("raw_text", entry.before)
            .put("created_at", isoTime(entry.timestamp))
            .put("duration", entry.durationSec)
            .put("language", "auto")
            .put("engine", if (entry.onDevice) "on-device" else "cloud")
            .put("cleanup_mode", "openwispr-mobile")
            .put("focused_app_name", entry.appLabel)
        enqueuePayload(context, payload)
    }

    /** Shared lock prevents imports being lost while a worker replaces the queue. */
    @Synchronized
    internal fun enqueuePayload(context: Context, payload: JSONObject) {
        File(context.applicationContext.filesDir, QUEUE).appendText(payload.toString() + "\n")
        flushSoon(context)
        schedule(context)
    }

    /** Stable per-model device label, e.g. "android-sm-s942u". */
    val DEVICE_ID: String = "android-" + android.os.Build.MODEL.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-")

    /**
     * Sends the queue right away on a background thread. WorkManager stays as the retry path,
     * but Android's flexible job constraints can hold it for a long time, so delivery should
     * not depend on it.
     */
    fun flushSoon(context: Context) {
        val app = context.applicationContext
        if (!File(app.filesDir, QUEUE).let { it.exists() && it.length() > 0 }) return
        Thread({ runCatching { flush(app) } }, "openwhispr-bridge-flush").start()
    }

    fun schedule(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BridgeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            // REPLACE: a stale enqueued/backed-off request must never block new deliveries.
            // Delivery is idempotent (the server upserts by device and id).
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    @Synchronized
    internal fun flush(context: Context): Boolean {
        val dir = context.applicationContext.filesDir
        val configFile = File(dir, CONFIG)
        if (!configFile.exists()) return true // local-only until explicitly provisioned
        val config = runCatching { JSONObject(configFile.readText()) }.getOrNull() ?: return false
        val endpoint = config.optString("endpoint").trim()
        val token = config.optString("token").trim()
        if (!endpoint.startsWith("https://") || token.length < 32) return false
        rememberPinnedAddress(endpoint, config)
        val queue = File(dir, QUEUE)
        if (!queue.exists()) return true
        val pending = queue.readLines().filter { it.isNotBlank() }
        val retained = mutableListOf<String>()
        val statusFile = File(dir, "bridge_last_status.txt")
        for ((index, line) in pending.withIndex()) {
            val request = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $token")
                .post(line.toRequestBody(json)).build()
            val outcome = runCatching {
                client.newCall(request).execute().use { response ->
                    response.code.also { statusFile.writeText("http:$it") }
                }
            }.onFailure { error ->
                statusFile.writeText("error:${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}")
            }.getOrNull()
            val accepted = outcome == 200
            if (!accepted) {
                retained += pending.drop(index)
                break
            }
        }
        val temp = File(dir, "$QUEUE.tmp")
        temp.writeText(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
        // Android rename replaces the old queue atomically; keep it intact until then.
        if (!temp.renameTo(queue)) return false
        return retained.isEmpty()
    }

    /** Pull enabled dashboard rules without ever replacing the last good offline cache on error. */
    internal suspend fun pullRules(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dir = context.applicationContext.filesDir
        val configFile = File(dir, CONFIG)
        if (!configFile.exists()) return@withContext true // local-only until explicitly provisioned
        val config = runCatching { JSONObject(configFile.readText()) }.getOrNull()
            ?: return@withContext false
        val endpoint = config.optString("endpoint").trim()
        val token = config.optString("token").trim()
        if (!endpoint.startsWith("https://") || token.length < 32) return@withContext false
        rememberPinnedAddress(endpoint, config)
        val rulesUrl = endpoint.toHttpUrlOrNull()?.newBuilder()
            ?.encodedPath("/api/rules")
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?.toString()
            ?: return@withContext false
        val statusFile = File(dir, RULES_STATUS)
        val result = runCatching {
            rulesClient.newCall(
                Request.Builder().url(rulesUrl)
                    .header("Authorization", "Bearer $token")
                    .get().build()
            ).execute().use { response ->
                statusFile.writeText("http:${response.code}")
                if (response.code != 200) return@use false
                val body = response.body ?: return@use false
                if (body.contentLength() > MAX_RULES_RESPONSE_BYTES) return@use false
                val bytes = body.byteStream().use { readBounded(it, MAX_RULES_RESPONSE_BYTES) }
                    ?: return@use false
                val payload = JSONObject(bytes.toString(Charsets.UTF_8))
                val version = payload.optString("version").trim()
                if (version.isEmpty()) return@use false
                val repository = MobileRulesRepository(context)
                repository.replace(version, repository.parseRemote(payload))
                true
            }
        }.onFailure { error ->
            statusFile.writeText("error:${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}")
        }.getOrDefault(false)
        result
    }
}

class BridgeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val queueOk = BridgeSync.flush(applicationContext)
        val rulesOk = BridgeSync.pullRules(applicationContext)
        if (queueOk && rulesOk) Result.success() else Result.retry()
    }
}
