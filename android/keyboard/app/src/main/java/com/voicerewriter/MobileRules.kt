package com.voicerewriter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/** One enabled rule published by the managed OpenWhispr dashboard. */
data class MobileRule(
    val trigger: String,
    val replacement: String,
)

/**
 * The dashboard's rules are intentionally kept apart from the user's personal vocabulary.
 * A failed refresh therefore leaves the last known-good rules available offline and can never
 * overwrite learned aliases or on-device snippets.
 */
class MobileRulesRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE)

    suspend fun get(): List<MobileRule> = withContext(Dispatchers.IO) {
        read(file).rules
    }

    /** Strict network parsing. Invalid remote data must leave the old offline cache intact. */
    internal fun parseRemote(root: JSONObject): List<MobileRule> {
        val array = root.optJSONArray("rules") ?: throw IllegalArgumentException("Rules are missing")
        require(array.length() <= MAX_RULES) { "Too many rules" }
        val rules = ArrayList<MobileRule>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: throw IllegalArgumentException("Invalid rule")
            if (item.has("enabled") && !item.optBoolean("enabled", false)) continue
            val trigger = item.optString("trigger").trim()
            val replacement = item.optString("replacement")
            require(trigger.isNotBlank() && trigger.length <= MAX_TRIGGER) { "Invalid rule trigger" }
            require(replacement.isNotBlank() && replacement.length <= MAX_REPLACEMENT) { "Invalid rule replacement" }
            rules += MobileRule(trigger, replacement)
        }
        val normalized = normalize(rules)
        require(normalized.size == rules.size) { "Duplicate rule trigger" }
        return normalized
    }

    internal suspend fun replace(version: String, rules: List<MobileRule>) = withContext(Dispatchers.IO) {
        require(version.isNotBlank()) { "Rules version is required" }
        val normalized = normalize(rules)
        val payload = JSONObject()
            .put("version", version)
            .put("rules", JSONArray(normalized.map { rule ->
                JSONObject().put("trigger", rule.trigger).put("replacement", rule.replacement)
            }))
            .toString()
        val temp = File(file.parentFile, "$FILE.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Could not replace mobile rules cache")
        }
    }

    private data class Snapshot(val version: String, val rules: List<MobileRule>)

    private fun read(path: File): Snapshot {
        if (!path.exists()) return Snapshot("", emptyList())
        return runCatching {
            val root = JSONObject(path.readText())
            Snapshot(root.optString("version").trim(), parseRules(root))
        }.getOrDefault(Snapshot("", emptyList()))
    }

    private fun parseRules(root: JSONObject): List<MobileRule> {
        val array = root.optJSONArray("rules") ?: return emptyList()
        val rules = ArrayList<MobileRule>(minOf(array.length(), MAX_RULES))
        for (i in 0 until minOf(array.length(), MAX_RULES)) {
            val item = array.optJSONObject(i) ?: continue
            // The ingress already filters disabled rules. Keep this check here so a malformed or
            // future response cannot activate a disabled dashboard row on the phone.
            if (item.has("enabled") && !item.optBoolean("enabled", false)) continue
            val trigger = item.optString("trigger").trim()
            val replacement = item.optString("replacement")
            if (trigger.isBlank() || trigger.length > MAX_TRIGGER) continue
            if (replacement.isBlank() || replacement.length > MAX_REPLACEMENT) continue
            rules += MobileRule(trigger, replacement)
        }
        return normalize(rules)
    }

    private fun normalize(rules: List<MobileRule>): List<MobileRule> {
        val seen = HashSet<String>()
        return rules.asSequence()
            .map { MobileRule(it.trigger.trim(), it.replacement) }
            .filter { it.trigger.isNotBlank() && it.trigger.length <= MAX_TRIGGER }
            .filter { it.replacement.isNotBlank() && it.replacement.length <= MAX_REPLACEMENT }
            .filter { seen.add(it.trigger.lowercase(Locale.ROOT)) }
            .take(MAX_RULES)
            .toList()
    }

    companion object {
        private const val FILE = "mobile_rules.json"
        private const val MAX_RULES = 500
        private const val MAX_TRIGGER = 100
        private const val MAX_REPLACEMENT = 50_000
    }
}

/**
 * Applies managed rules in one pass. Replacements are never fed back through the matcher, and
 * the boundary prevents a trigger from firing inside a larger word.
 */
object MobileRulesCorrector {
    private const val MAX_RULES = 500

    fun apply(text: String, rules: List<MobileRule>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        val active = rules.asSequence()
            .map { MobileRule(Normalizer.normalize(it.trigger.trim(), Normalizer.Form.NFC), it.replacement) }
            .filter { it.trigger.isNotBlank() && it.replacement.isNotEmpty() }
            .distinctBy { it.trigger.lowercase(Locale.ROOT) }
            .sortedByDescending { it.trigger.length }
            .take(MAX_RULES)
            .toList()
        if (active.isEmpty()) return text

        val replacements = active.associateBy(
            keySelector = { it.trigger.lowercase(Locale.ROOT) },
            valueTransform = { it.replacement },
        )
        val alternatives = active.joinToString("|") { Regex.escape(it.trigger.trim()) }
        val matcher = Regex(
            "(?<=^|[\\s\\p{P}\\p{S}])(?:$alternatives)(?=$|[\\s\\p{P}\\p{S}])",
            setOf(RegexOption.IGNORE_CASE),
        )
        return matcher.replace(Normalizer.normalize(text, Normalizer.Form.NFC)) { match ->
            replacements[match.value.lowercase(Locale.ROOT)] ?: match.value
        }
    }
}
