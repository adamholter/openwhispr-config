package com.voicerewriter

/**
 * Speech-to-text mishears AI model names ("GPT 6.1 soul", "Clawed Code", "Kimmy K3") and the
 * cleanup model keeps them: Sol stayed wrong in 66% of mentions. Run on the transcript before
 * cleanup so the model sees the right name. Rules only fire in model-talk contexts, so an ordinary
 * "soul", "Seoul" or "audio codecs" is left alone. Same rules as the desktop's
 * speed-patch/owModelNames.js (ModelNamesTest holds the shared cases).
 */
object ModelNames {
    private const val NUM = "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten)"
    private const val VER = "(?:\\d+(?:\\.\\d+)*|$NUM(?:\\s+point\\s+(?:$NUM|\\d+))?)"
    private const val GPT = "\\bg\\.?[bp]\\.?[td][- ]?(?:$VER[- ]*)?"
    private const val SOL_BAD = "(?:soul|seoul|sole|sol)"
    private const val TERRA_BAD = "(?:terror|tera|terra)"
    private const val LUNA_BAD = "(?:llama|luna)"
    private const val TIER = "(?:astra|sol|terra|luna)"
    private const val SEP = "(?:\\s*,\\s*(?:and\\s+|or\\s+)?|\\s+(?:and|or|vs\\.?|versus|slash)\\s+|\\s*/\\s*)"
    private const val MODEL = "(?:opus|sonnet|haiku|gemini|claude|codex|astra|terra|luna|gpt|grok|kimi|fable|deepseek|qwen|mythos)"
    private const val NOTSENT = "[^.!?\\n]"
    private val I = RegexOption.IGNORE_CASE

    private fun rx(p: String) = Regex(p, I)

    /** Replace [re]; group 1 (if the pattern has one) is kept in front of [name]. */
    private fun fix(t: String, re: Regex, name: String): String =
        re.replace(t) { m -> (m.groupValues.getOrNull(1) ?: "") + name }

    private val GUARD_BEFORE = rx("\\b(?:a|an|the|his|her|my|your|their|its|our|no|of|one's|human)\\s*$")
    private val GUARD_AFTER = rx("^\\s*(?:document|documents|doc|of)\\b")
    private fun guard(word: String, str: String, off: Int): String {
        val before = str.substring(maxOf(0, off - 40), off)
        val after = str.substring(off + word.length, minOf(str.length, off + word.length + 14))
        if (GUARD_BEFORE.containsMatchIn(before)) return word
        if (GUARD_AFTER.containsMatchIn(after)) return word
        return "Sol"
    }

    private val MEDIA = rx("\\b(?:audio|video|image|media|lossless|encod\\w*|decod\\w*|h\\.?264|h\\.?265|av1|mp3|stream\\w*|bitrate|compress\\w*|podcast|bandwidth|playback)\\b")

    private val gptSol = rx("($GPT,?\\s*)$SOL_BAD\\b")
    private val gptTerra = rx("($GPT,?\\s*)$TERRA_BAD\\b")
    private val gptLuna = rx("($GPT,?\\s*)$LUNA_BAD\\b")
    private val tierSolAfter = rx("(\\b$TIER$SEP)(?:soul|seoul)\\b")
    private val tierSolBefore = rx("\\b(?:soul|seoul)\\b(?=$SEP$TIER\\b)")
    private val tierTerraAfter = rx("(\\b$TIER$SEP)(?:terror|tera)\\b")
    private val tierTerraBefore = rx("\\b(?:terror|tera)\\b(?=$SEP$TIER\\b)")
    private val tierLunaAfter = rx("(\\b$TIER$SEP)llama\\b")
    private val tierLunaBefore = rx("\\bllama\\b(?=$SEP$TIER\\b)")
    private val versionSol = rx("(\\b(?:six\\s+point\\s+(?:one|oh\\s+one)|6\\.1|six|5\\.6|five\\s+point\\s+six|point\\s+(?:one|oh))\\s+)(?:soul|seoul)\\b")
    private val modelThenSoul = rx("\\b$MODEL\\b$NOTSENT{0,40}?\\b(?:soul|seoul)\\b")
    private val trailingSoul = rx("\\b(?:soul|seoul)\\b$")
    private val soulThenModel = rx("\\b(?:soul|seoul)\\b(?=$NOTSENT{0,40}?\\b$MODEL\\b)")
    private val capSoul = Regex("(?<=[a-z,;]\\s)Soul\\b(?!\\s+(?:document|documents|doc|of)\\b)")
    private val soulEffort = rx("\\b(?:soul|seoul)\\b(?=\\s+(?:on\\s+)?(?:extra\\s+high|x\\s?high|max|high|medium|low|light|mini)\\b)")
    private val codecs = rx("\\bcodecs?\\b")
    private val clawed = rx("\\bclawed\\b(?!\\s+(?:the|a|an|at|through|its|his|her|their|my|your|our|me|him|them|up|out|off|on|back|away|into|over)\\b)")
    private val claw = rx("\\bclaw(?=\\s+(?:limits?|usage|subscription|code|models?|plan|max)\\b)")
    private val claud = rx("\\bclaud\\b")
    private val clogCloud = rx("\\b(?:clog|cloud)(?=\\s+(?:code|haiku|opus|sonnet|desktop|cowork|design|md|max)\\b)")
    private val kimmy = rx("(?<!\\b(?:friend|named|called|aunt|cousin|sister|girlfriend|wife|daughter|miss)\\s)\\bkimmy\\b")
    private val grock = rx("\\bgrock\\b")
    private val groc = rx("\\b(?:groc|grog)(?=\\s+(?:\\d|bot\\b|four|three|five))")
    private val deepSeek = rx("\\bdeep\\s+seek(?!ing)\\b")

    fun fix(text: String): String {
        var t = text
        t = fix(t, gptSol, "Sol"); t = fix(t, gptTerra, "Terra"); t = fix(t, gptLuna, "Luna")
        repeat(3) {
            t = fix(t, tierSolAfter, "Sol"); t = fix(t, tierSolBefore, "Sol")
            t = fix(t, tierTerraAfter, "Terra"); t = fix(t, tierTerraBefore, "Terra")
            t = fix(t, tierLunaAfter, "Luna"); t = fix(t, tierLunaBefore, "Luna")
        }
        t = fix(t, versionSol, "Sol")
        t = modelThenSoul.replace(t) { m ->
            val s = m.value
            trailingSoul.replace(s) { w -> guard(w.value, s, w.range.first) }
        }
        t = soulThenModel.replace(t) { w -> guard(w.value, t, w.range.first) }
        val snap = t
        t = capSoul.replace(snap) { w -> guard(w.value, snap, w.range.first) }
        t = fix(t, soulEffort, "Sol")
        val s2 = t
        t = codecs.replace(s2) { m ->
            if (MEDIA.containsMatchIn(s2.substring(maxOf(0, m.range.first - 60), minOf(s2.length, m.range.last + 61)))) m.value else "Codex"
        }
        t = clawed.replace(t, "Claude"); t = claw.replace(t, "Claude"); t = claud.replace(t, "Claude"); t = clogCloud.replace(t, "Claude")
        t = kimmy.replace(t, "Kimi"); t = grock.replace(t, "Grok"); t = groc.replace(t, "Grok"); t = deepSeek.replace(t, "DeepSeek")
        return t
    }
}
