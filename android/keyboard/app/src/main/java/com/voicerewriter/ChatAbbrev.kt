package com.voicerewriter

/**
 * Chat abbreviations: Adam writes idk, tbh, btw, iirc... in lowercase, even at the start of a sentence.
 * The recognizer emits them in capitals ("TBH") and the cleanup model sometimes spells them out
 * ("to be honest"). [lower] lowercases them; [restore] puts back an abbreviation the cleanup expanded.
 * ASAP, ETA, DM and NP stay as they are. Mirror of owChat.js (desktop speed-patch).
 */
object ChatAbbrev {
    private val WORDS = listOf("idk", "btw", "iirc", "afaik", "tbh", "tbf", "imo", "imho", "lol", "lmao", "lmk", "omg", "smh", "ngl",
        "fwiw", "irl", "jk", "nvm", "ikr", "icymi", "fyi", "wym", "hmu", "ofc", "tldr")
    private val LONG = linkedMapOf(
        "idk" to listOf("i don't know", "i do not know", "i dunno"), "btw" to listOf("by the way"),
        "iirc" to listOf("if i recall correctly", "if i remember correctly", "if i remember right"), "afaik" to listOf("as far as i know"),
        "tbh" to listOf("to be honest"), "tbf" to listOf("to be fair"), "imo" to listOf("in my opinion"), "imho" to listOf("in my humble opinion"),
        "lmk" to listOf("let me know"), "fwiw" to listOf("for what it's worth", "for what it is worth"), "ngl" to listOf("not gonna lie", "not going to lie"),
        "smh" to listOf("shaking my head"), "jk" to listOf("just kidding"), "fyi" to listOf("for your information"), "nvm" to listOf("never mind", "nevermind"),
        "omg" to listOf("oh my god", "oh my gosh"), "lol" to listOf("laughing out loud"), "icymi" to listOf("in case you missed it"), "irl" to listOf("in real life"),
    )
    private val any by lazy { Regex("\\b(${WORDS.joinToString("|")}|tl;dr)\\b", RegexOption.IGNORE_CASE) }
    private val imoBefore by lazy { Regex("\\b(?:the|an|at|this|last|year's)\\s+$", RegexOption.IGNORE_CASE) }
    private val imoAfter by lazy { Regex("^[\\s-]*(?:gold|silver|bronze|medal|problem|level|grader|question|competition|team|\\d)", RegexOption.IGNORE_CASE) }
    private val abbrevRx by lazy { LONG.keys.associateWith { Regex("\\b$it\\b") } }
    private val abbrevAnyCase by lazy { LONG.keys.associateWith { Regex("\\b$it\\b", RegexOption.IGNORE_CASE) } }
    private val sentenceEnd by lazy { Regex("[.!?]\\s+$") }
    private val loneI by lazy { Regex("\\bi\\b") }
    private val longRx by lazy { LONG.mapValues { (_, ls) -> ls.map { Regex("\\b" + Regex.escape(it).replace("'", "\\E['’]\\Q") + "\\b", RegexOption.IGNORE_CASE) } } }

    fun lower(text: String): String = any.replace(text) { m ->
        val v = m.value
        if (v == v.lowercase()) return@replace v
        if (v.equals("imo", true)) {
            val off = m.range.first
            val before = text.substring(maxOf(0, off - 12), off)
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 15))
            if (imoBefore.containsMatchIn(before) || imoAfter.containsMatchIn(after)) return@replace v
        }
        v.lowercase()
    }

    /** The raw transcript said "TBH" but the cleanup wrote "to be honest": restore it as often as the raw had it. */
    fun restore(raw: String, out: String): String {
        val low = raw.lowercase()
        var t = out
        for ((ab, _) in LONG) {
            var n = abbrevRx.getValue(ab).findAll(low).count()
            if (n == 0) continue
            for (lre in longRx.getValue(ab)) {
                var extra = lre.findAll(t).count() - lre.findAll(raw).count()
                if (extra <= 0) continue
                t = lre.replace(t) { m -> if (extra-- > 0 && n-- > 0) ab else m.value }
                if (n <= 0) break
            }
        }
        // and the reverse: the speaker said "to be honest" but the cleanup wrote "tbh"
        for ((ab, longs) in LONG) {
            val are = abbrevAnyCase.getValue(ab)
            var extra = are.findAll(t).count() - are.findAll(raw).count()
            if (extra <= 0) continue
            val said = longs.indices.firstOrNull { longRx.getValue(ab)[it].containsMatchIn(raw) }?.let { longs[it] } ?: continue
            val cur = t
            t = are.replace(cur) { m ->
                if (extra-- <= 0) return@replace m.value
                val off = m.range.first
                val start = off == 0 || sentenceEnd.containsMatchIn(cur.substring(maxOf(0, off - 3), off))
                val l = said.replace(loneI, "I")
                if (start) l.substring(0, 1).uppercase() + l.substring(1) else l
            }
        }
        return lower(t)
    }
}
