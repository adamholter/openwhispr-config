package com.voicerewriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** ADB-shell-only import path for secrets into a non-debuggable release build. */
class BridgeConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.voicerewriter.RUN_SELECTION_FIXTURE" -> {
                if(android.os.Build.HARDWARE !in listOf("ranchu","goldfish"))return
                val file=File(context.getExternalFilesDir(null),"selection_fixture.json")
                val fixture=runCatching{JSONObject(file.readText())}.getOrNull() ?: return
                helium314.keyboard.latin.LatinIME.runSelectionFixture(fixture.getString("instruction"),fixture.optLong("delay_ms",0),fixture.getString("key"))
            }
            PREPARE_CONFIG -> openProvisioningSocket(context)
            IMPORT_MODEL -> openModelSocket(context)
            IMPORT_CONFIG -> importConfig(context)
            IMPORT_TEST -> importTestPayload(context)
            EXPORT_BRIDGE_STATUS -> exportBridgeStatus(context)
            RUN_PIPELINE_TEST -> runPipelineTest(context,intent.getBooleanExtra("force_offline",false))
            PROVISION_PARAKEET -> provisionParakeet(context)
            IMPORT_PARAKEET_FILE -> importParakeetFile(context, intent.getStringExtra("file").orEmpty())
            PREPARE_PARAKEET_STAGING -> File(context.getExternalFilesDir(null), "parakeet-import").mkdirs()
            INSTALL_PARAKEET_FROM_STAGING -> installParakeetFromStaging(context)
            IMPORT_DICTIONARY -> importDictionary(context)
        }
    }

    /** Receiver is protected by android.permission.DUMP in the manifest. No transcript or secrets. */
    private fun exportBridgeStatus(context: Context) {
        val dir = context.applicationContext.filesDir
        val configFile = File(dir, CONFIG)
        val config = runCatching { JSONObject(configFile.readText()) }.getOrNull()
        val rawEndpoint = config?.optString("endpoint").orEmpty()
        val endpoint = runCatching {
            val uri = java.net.URI(rawEndpoint)
            java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrDefault("invalid")
        val queue = File(dir, "bridge_pending.jsonl")
        val lines = runCatching { queue.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val ids = org.json.JSONArray()
        var malformed = 0
        lines.forEach { line ->
            val row = runCatching { JSONObject(line) }.getOrNull()
            if (row == null) malformed++ else ids.put(row.optString("id"))
        }
        val token = config?.optString("token").orEmpty()
        var lastStatus = runCatching { File(dir, "bridge_last_status.txt").readText() }.getOrDefault("none")
        if (token.isNotEmpty()) lastStatus = lastStatus.replace(token, "[redacted]")
        if (rawEndpoint.isNotEmpty()) lastStatus = lastStatus.replace(rawEndpoint, endpoint)
        val mobileRules = runCatching { JSONObject(File(dir, "mobile_rules.json").readText()) }.getOrNull()
        val mobileRulesCount = mobileRules?.optJSONArray("rules")?.length() ?: 0
        val mobileRulesVersion = mobileRules?.optString("version")?.takeIf { it.isNotBlank() } ?: "none"
        val mobileRulesStatus = runCatching {
            File(dir, "mobile_rules_last_status.txt").readText().trim().take(240)
        }.getOrDefault("none")
        val result = JSONObject()
            .put("isDeviceProtectedStorage", context.isDeviceProtectedStorage)
            .put("applicationDeviceProtectedStorage", context.applicationContext.isDeviceProtectedStorage)
            .put("endpoint", endpoint)
            .put("config_exists", configFile.exists())
            .put("config_valid_json", config != null)
            .put("queue_exists", queue.exists())
            .put("queue_count", lines.size)
            .put("queue_ids", ids)
            .put("malformed_rows", malformed)
            .put("bridge_last_status", lastStatus.take(240))
            .put("mobile_rules_count", mobileRulesCount)
            .put("mobile_rules_version", mobileRulesVersion)
            .put("mobile_rules_last_status", mobileRulesStatus)
        val settings = runBlocking { SettingsRepository(context).get() }
        result.put("cleanup_model", settings.model).put("cleanup_provider", settings.provider)
            .put("speech_model", settings.sttModelResolved).put("speech_provider", settings.sttProvider)
            .put("speech_endpoint", settings.sttEndpointResolved)
            .put("deterministic_cleanup", settings.deterministicCleanup)
            .put("cleanup_configured", settings.isConfigured).put("speech_configured", settings.isSttConfigured)
            .put("parakeet_ready", ParakeetModelManager.isReady(context))
            .put("parakeet_bytes", ParakeetModelManager.bundleFiles().sumOf {
                ParakeetModelManager.file(context, it).takeIf(File::isFile)?.length() ?: 0L
            })
        File(context.getExternalFilesDir(null), "bridge_diagnostics.json").writeText(result.toString(2))
    }

    /** Shell-only provisioning hook for repeatable on-device setup and QA. */
    private fun provisionParakeet(context: Context) {
        val pending = goAsync()
        Thread {
            val report = JSONObject()
            runBlocking {
                runCatching {
                    ParakeetModelManager.download(context.applicationContext) { progress ->
                        File(context.getExternalFilesDir(null), "parakeet_provision_status.json")
                            .writeText(JSONObject().put("state", "downloading").put("progress", progress).toString())
                    }
                    val repo = SettingsRepository(context)
                    repo.save(repo.get().copy(sttProvider = "local", sttModel = ParakeetModelManager.MODEL_ID))
                    report.put("state", "ready").put("bytes", ParakeetModelManager.bundleFiles().sumOf {
                        ParakeetModelManager.file(context, it).length()
                    })
                }.onFailure { report.put("state", "error").put("error", it.javaClass.simpleName) }
            }
            File(context.getExternalFilesDir(null), "parakeet_provision_status.json").writeText(report.toString(2))
            pending.finish()
        }.start()
    }

    /** Streams one checksummed bundle member over an adb-forwarded loopback socket. */
    private fun importParakeetFile(context: Context, name: String) {
        val spec = PARAKEET_IMPORTS[name] ?: return
        val pending = goAsync()
        Thread {
            runCatching {
                ServerSocket(28780, 1, InetAddress.getLoopbackAddress()).use { server ->
                    server.soTimeout = 120_000
                    server.accept().use { socket ->
                        val target = ParakeetModelManager.file(context, name)
                        val part = File(target.parentFile, "$name.adbpart")
                        part.outputStream().use { socket.getInputStream().copyTo(it) }
                        val digest = MessageDigest.getInstance("SHA-256")
                        part.inputStream().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                        }
                        val sha = digest.digest().joinToString("") { "%02x".format(it) }
                        if (part.length() == spec.first && sha == spec.second && part.renameTo(target)) {
                            socket.getOutputStream().write("OK\n".toByteArray())
                        } else {
                            part.delete()
                            socket.getOutputStream().write("INVALID\n".toByteArray())
                        }
                    }
                }
            }
            pending.finish()
        }.start()
    }

    /** Installs an adb-pushed bundle from external app storage after exact hash validation. */
    private fun installParakeetFromStaging(context: Context) {
        val pending = goAsync()
        Thread {
            val report = JSONObject()
            runBlocking {
                runCatching {
                    val staging = File(context.getExternalFilesDir(null), "parakeet-import")
                    for ((name, spec) in PARAKEET_IMPORTS) {
                        val source = File(staging, name)
                        check(source.isFile && source.length() == spec.first && sha256(source) == spec.second) {
                            "$name failed validation"
                        }
                    }
                    for (name in PARAKEET_IMPORTS.keys) {
                        val target = ParakeetModelManager.file(context, name)
                        val part = File(target.parentFile, "$name.adbpart")
                        File(staging, name).inputStream().use { input ->
                            part.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                        }
                        check(part.renameTo(target)) { "Could not install $name" }
                    }
                    check(ParakeetModelManager.isReady(context)) { "Installed bundle is incomplete" }
                    val repo = SettingsRepository(context)
                    repo.save(repo.get().copy(sttProvider = "local", sttModel = ParakeetModelManager.MODEL_ID))
                    report.put("state", "ready").put("bytes", PARAKEET_IMPORTS.keys.sumOf {
                        ParakeetModelManager.file(context, it).length()
                    })
                }.onFailure { report.put("state", "error").put("error", it.message.orEmpty().take(100)) }
            }
            File(context.getExternalFilesDir(null), "parakeet_provision_status.json").writeText(report.toString(2))
            pending.finish()
        }.start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Shell-only, synthetic WAV probe. Does not insert text or create history. */
    private fun runPipelineTest(context: Context, forceOffline: Boolean) {
        val pending = goAsync()
        Thread {
            val result = runBlocking {
                runCatching { ImeDictationPipeline.verifyPipeline(context.applicationContext,forceOffline) }
                    .getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
            }
            File(context.getExternalFilesDir(null), "pipeline_test_result.json").writeText(result.toString(2))
            pending.finish()
        }.start()
    }

    private fun openModelSocket(context: Context) {
        val pending = goAsync()
        Thread {
            runCatching {
                ServerSocket(28778, 1, InetAddress.getLoopbackAddress()).use { server ->
                    server.soTimeout = 60_000
                    server.accept().use { socket ->
                        val target = WhisperModelManager.modelFile(context, "base")
                        val part = File(target.parentFile, target.name + ".part")
                        part.outputStream().use { output -> socket.getInputStream().copyTo(output) }
                        val md = MessageDigest.getInstance("SHA-256")
                        part.inputStream().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
                        }
                        val digest = md.digest().joinToString("") { "%02x".format(it) }
                        if (part.length() == 147_951_465L && digest == WHISPER_BASE_SHA256 && part.renameTo(target)) {
                            File(context.getExternalFilesDir(null), "whisper_base_status.txt")
                                .writeText("size=147951465 sha256=$digest\n")
                            socket.getOutputStream().write("OK\n".toByteArray())
                        } else part.delete()
                    }
                }
            }
            pending.finish()
        }.start()
    }

    private fun openProvisioningSocket(context: Context) {
        val pending = goAsync()
        Thread {
            runCatching {
                ServerSocket(28777, 1, InetAddress.getLoopbackAddress()).use { server ->
                    server.soTimeout = 15_000
                    server.accept().use { socket ->
                        val body = socket.getInputStream().bufferedReader().readText().take(16_384)
                        val parsed = JSONObject(body)
                        val endpoint = parsed.optString("endpoint").trim()
                        val token = parsed.optString("token").trim()
                        if (!endpoint.startsWith("https://") || token.length < 32) return@use
                        File(context.filesDir, CONFIG).writeText(parsed.toString())
                        val cleanupKey = parsed.optString("cleanup_api_key").trim()
                        runBlocking {
                            val repo = SettingsRepository(context)
                            val current = repo.get()
                            repo.save(current.copy(
                                provider = parsed.optString("cleanup_provider", current.provider),
                                model = parsed.optString("cleanup_model", current.model),
                                apiKey = cleanupKey.takeIf { it.length >= 20 } ?: current.apiKey,
                                sttProvider = parsed.optString("speech_provider", current.sttProvider),
                                sttModel = parsed.optString("speech_model", current.sttModel),
                                sttEndpoint = parsed.optString("speech_endpoint", current.sttEndpoint),
                                sttKey = parsed.optString("speech_api_key", current.sttKey),
                                deterministicCleanup = parsed.optBoolean("deterministic_cleanup", current.deterministicCleanup),
                            ))
                        }
                        File(context.getExternalFilesDir(null), "bridge_config_status.txt").writeText("configured\n")
                        socket.getOutputStream().write("OK\n".toByteArray())
                        BridgeSync.schedule(context, replace = true)
                    }
                }
            }
            pending.finish()
        }.start()
    }

    /** ow-sync pushes the shared desktop dictionary as plain text, one term per line. */
    private fun importDictionary(context: Context) {
        val input = File(context.getExternalFilesDir(null), "dictionary_sync.txt")
        val status = File(context.getExternalFilesDir(null), "dictionary_sync_status.json")
        val result = runCatching {
            val terms = input.readLines()
            val added = VocabRepository(context).replaceSyncedTerms(terms)
            JSONObject().put("state", "ok").put("terms", terms.count { it.isNotBlank() }).put("added", added)
        }.getOrElse { JSONObject().put("state", "error").put("error", it.javaClass.simpleName) }
        input.delete()
        status.writeText(result.toString())
    }

    private fun importConfig(context: Context) {
        val source = File(context.getExternalFilesDir(null), CONFIG)
        val parsed = runCatching { JSONObject(source.readText()) }.getOrNull() ?: return
        val endpoint = parsed.optString("endpoint").trim()
        val token = parsed.optString("token").trim()
        // Archive sync is optional; voice settings import on their own.
        val syncConfigured = endpoint.startsWith("https://") && token.length >= 32
        if (syncConfigured) File(context.filesDir, CONFIG).writeText(
            JSONObject().put("endpoint", endpoint).put("token", token)
                .put("endpoint_ip", parsed.optString("endpoint_ip").trim()).toString()
        )
        val cleanupKey = parsed.optString("cleanup_api_key").trim()
                        runBlocking {
                            val repo = SettingsRepository(context)
                            val current = repo.get()
                            repo.save(current.copy(
                                provider = parsed.optString("cleanup_provider", current.provider),
                                model = parsed.optString("cleanup_model", current.model),
                                apiKey = cleanupKey.takeIf { it.length >= 20 } ?: current.apiKey,
                                sttProvider = parsed.optString("speech_provider", current.sttProvider),
                                sttModel = parsed.optString("speech_model", current.sttModel),
                                sttEndpoint = parsed.optString("speech_endpoint", current.sttEndpoint),
                                sttKey = parsed.optString("speech_api_key", current.sttKey),
                                deterministicCleanup = parsed.optBoolean("deterministic_cleanup", current.deterministicCleanup),
                            ))
                        }
        source.delete()
        if (syncConfigured) BridgeSync.schedule(context, replace = true)
    }

    private fun importTestPayload(context: Context) {
        val source = File(context.getExternalFilesDir(null), TEST_PAYLOAD)
        val payload = runCatching { JSONObject(source.readText()) }.getOrNull() ?: return
        val required = listOf("id", "device_id", "text", "created_at", "duration")
        if (required.any { payload.isNull(it) } ||
            !payload.optString("text").contains("synthetic staging fixture", ignoreCase = true)
        ) return
        BridgeSync.enqueuePayload(context, payload)
        source.delete()
    }

    companion object {
        private const val CONFIG = "bridge_config.json"
        private const val TEST_PAYLOAD = "bridge_test_payload.json"
        const val EXPORT_BRIDGE_STATUS = "com.voicerewriter.EXPORT_BRIDGE_STATUS"
        const val RUN_PIPELINE_TEST = "com.voicerewriter.RUN_PIPELINE_TEST"
        const val PROVISION_PARAKEET = "com.voicerewriter.PROVISION_PARAKEET"
        const val IMPORT_PARAKEET_FILE = "com.voicerewriter.IMPORT_PARAKEET_FILE"
        const val PREPARE_PARAKEET_STAGING = "com.voicerewriter.PREPARE_PARAKEET_STAGING"
        const val INSTALL_PARAKEET_FROM_STAGING = "com.voicerewriter.INSTALL_PARAKEET_FROM_STAGING"
        const val IMPORT_CONFIG = "com.voicerewriter.IMPORT_BRIDGE_CONFIG"
        const val IMPORT_DICTIONARY = "com.voicerewriter.IMPORT_DICTIONARY"
        const val IMPORT_TEST = "com.voicerewriter.IMPORT_BRIDGE_TEST_PAYLOAD"
        const val PREPARE_CONFIG = "com.voicerewriter.PREPARE_BRIDGE_CONFIG"
        const val IMPORT_MODEL = "com.voicerewriter.IMPORT_WHISPER_BASE"
        private const val WHISPER_BASE_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
        private val PARAKEET_IMPORTS = mapOf(
            "encoder.int8.onnx" to (652_184_296L to "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            "decoder.int8.onnx" to (7_257_753L to "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            "joiner.int8.onnx" to (1_739_080L to "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            "tokens.txt" to (9_384L to "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
        )
    }
}
