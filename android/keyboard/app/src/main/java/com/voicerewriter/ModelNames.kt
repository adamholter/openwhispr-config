package com.voicerewriter

/**
 * Speech-to-text mishears AI model names ("GPT 6.1 soul", "Clawed Code", "Kimmy K3") and the
 * cleanup model keeps them: Sol stayed wrong in 66% of mentions. Run on the transcript before
 * cleanup so the model sees the right name. Rules only fire in model-talk contexts, so an ordinary
 * "soul", "Seoul" or "audio codecs" is left alone. Line-for-line port of the desktop's
 * patches/model-names.js. A parity test (not published: its fixtures are real dictations) checks identical output.
 * Patterns are written in JS syntax and compiled through [JsRegex] so `\b`/`\s` mean the same
 * thing here, in the JVM tests and on Android's ICU regex engine.
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

    /** JS regex with the `i` flag. */
    private fun rx(p: String) = JsRegex.compile(p, true)
    /** JS regex without flags (case-sensitive). */
    private fun cs(p: String) = JsRegex.compile(p, false)

    /** JS `fix`: replace [re]; group 1 (if the pattern has one) is kept in front of [name]. */
    private fun fix(t: String, re: Regex, name: String): String =
        re.replace(t) { m -> (if (m.groups.size > 1) m.groups[1]?.value else null).orEmpty() + name }

    private fun slice(s: String, from: Int, to: Int) = s.substring(maxOf(0, minOf(from, s.length)), maxOf(0, minOf(to, s.length)))

    private val GUARD_BEFORE = rx("\\b(?:a|an|the|his|her|my|your|their|its|our|no|of|one's|human)\\s*$")
    private val GUARD_AFTER = rx("^\\s*(?:document|documents|doc|of)\\b")
    private fun guard(word: String, off: Int, str: String): String {
        val before = slice(str, maxOf(0, off - 40), off)
        val after = slice(str, off + word.length, off + word.length + 14)
        if (GUARD_BEFORE.containsMatchIn(before)) return word
        if (GUARD_AFTER.containsMatchIn(after)) return word
        return "Sol"
    }

    private val MEDIA = rx("\\b(?:audio|video|image|media|lossless|encod\\w*|decod\\w*|h\\.?264|h\\.?265|av1|mp3|stream\\w*|bitrate|compress\\w*|podcast|bandwidth|playback)\\b")

    private val gptSol = rx("($GPT[,]?\\s*)$SOL_BAD\\b()")
    private val gptTerra = rx("($GPT[,]?\\s*)$TERRA_BAD\\b()")
    private val gptLuna = rx("($GPT[,]?\\s*)$LUNA_BAD\\b()")
    private val tierSolAfter = rx("(\\b$TIER$SEP)(?:soul|seoul)\\b()")
    private val tierSolBefore = rx("\\b(?:soul|seoul)\\b(?=$SEP$TIER\\b)")
    private val tierTerraAfter = rx("(\\b$TIER$SEP)(?:terror|tera)\\b()")
    private val tierTerraBefore = rx("\\b(?:terror|tera)\\b(?=$SEP$TIER\\b)")
    private val tierLunaAfter = rx("(\\b$TIER$SEP)llama\\b()")
    private val tierLunaBefore = rx("\\bllama\\b(?=$SEP$TIER\\b)")
    private val versionSol = rx("(\\b(?:six\\s+point\\s+(?:one|oh\\s+one)|6\\.1|six|5\\.6|five\\s+point\\s+six|point\\s+(?:one|oh))\\s+)(?:soul|seoul)\\b()")
    private val modelThenSoul = rx("\\b$MODEL\\b$NOTSENT{0,40}?\\b(?:soul|seoul)\\b")
    private val trailingSoul = rx("\\b(?:soul|seoul)\\b$")
    private val soulThenModel = rx("\\b(?:soul|seoul)\\b(?=$NOTSENT{0,40}?\\b$MODEL\\b)")
    private val capSoul = cs("(?<=[a-z,;]\\s)Soul\\b(?!\\s+(?:document|documents|doc|of)\\b)")
    private val soulEffort = rx("\\b(?:soul|seoul)\\b(?=\\s+(?:on\\s+)?(?:extra\\s+high|x\\s?high|max|high|medium|low|light|mini)\\b)")
    private val codecs = rx("\\bcodecs?\\b")
    private val clawed = rx("\\bclawed\\b(?!\\s+(?:the|a|an|at|through|its|his|her|their|my|your|our|me|him|them|up|out|off|on|back|away|into|over)\\b)")
    private val claw = rx("\\bclaw(?=\\s+(?:limits?|usage|subscription|code|models?|plan|max)\\b)")
    private val claud = rx("\\bclaud\\b")
    private val clogCloud = rx("\\b(?:clog|cloud)(?=\\s+(?:code|haiku|opus|sonnet|desktop|cowork|design|md|max)\\b)")
    private val kimmy = rx("(?<!\\b(?:friend|named|called|aunt|aunt|cousin|sister|girlfriend|wife|daughter|miss)\\s)\\bkimmy\\b")
    private val grock = rx("\\bgrock\\b")
    private val groc = rx("\\b(?:groc|grog)(?=\\s+(?:\\d|bot\\b|four|three|five))")
    private val deepSeek = rx("\\bdeep\\s+seek(?!ing)\\b")
    // Astra
    private val gptAstra = rx("($GPT[,]?\\s*)(?:asteroid|astro)\\b()")
    private val tierAstraAfter = rx("(\\b$TIER$SEP)astro\\b()")
    private val tierAstraBefore = rx("\\bastro\\b(?=$SEP$TIER\\b)")
    // Other product names
    private val openAi = rx("\\bopen[\\s-]ai\\b(?!\\s+source\\b)")
    private val chatGpt = rx("\\bchat[\\s-]g\\.?p\\.?t\\b(?!\\s+dot\\b)")
    private val excalidraw = rx("\\b(?:excal[aiue]?[\\s-]?draw?|excel[\\s-]draw|escalidraw)\\b")
    private val semiAnalysis = rx("\\bsemi[\\s-]analysis\\b")
    private val vercel = rx("\\b(?:vercell|versel)\\b")
    private val qwen = rx("\\bquen\\b")
    private val slopalytics = rx("\\bslopolytics\\b")
    private val grokBot = rx("\\bgrok\\s?bot\\b")
    private val omarchy = rx("\\bomar\\s?key\\b")
    private val postHog = rx("\\bpost\\s+hog\\b")
    private val deepSeq = rx("\\bdeepseq\\b")
    private val openWhispr = rx("\\bopen\\s?whisper\\b")
    private val openRouter = rx("\\bopen\\s+router\\b")
    private val openCode = cs("\\bOpen Code\\b")
    private val t3Code = rx("\\b(?:t|g|tea|tee)[\\s-]?(?:3|three)\\s*code\\b")
    private val agentsMd = rx("\\bagents?\\s+(?:dot\\s+)?md\\b")
    private val claudeMd = rx("\\bclaude\\s+(?:dot\\s+)?md\\b")
    private val xHigh = rx("\\bx[\\s-]high\\b")
    private val maxEffort = rx("\\b(?:mac's|macs|max's)\\s+(?=(?:reasoning|effort|thinking)\\b)")
    private val codexPossessive = rx("\\b(?:kodak's|codec's)\\b")
    private val claudeSonic = rx("\\b(claude\\s+)sonic\\b")
    private val sonicVersion = rx("\\bsonic(?=\\s+(?:\\d|four|five|three|six)\\b)")
    private val nemotron = rx("\\b(?:mimotron|nemetron|nematron|nimotron|neimatron|nemitron)\\b")
    private val kua = rx("\\bkua\\b")
    private val gpg = rx("\\bgpg(?=[\\s-]*(?:\\d|four|five|six|seven|oss)\\b)")
    private val pryors = rx("\\bpryors\\b")
    private val priorsNoun = rx("(?<!\\b(?:your|my|our|their|his|her|its|strong|weak|update|updating|bayesian|informative|uninformative)\\s)\\bpriors\\b(?=\\s+(?:chart|charts|site|app|page|leaderboard|data|users|ranking|rankings|index|homepage|dashboard|mcp|api|repo|team|redesign|analytics|launch|account|domain|post|posts)\\b)")
    private val priorsPrep = rx("\\b((?:on|for|to|into|at|from)\\s+)priors\\b(?!\\s+(?:about|that|on|of|for|and)\\b)")
    private val priorsPrepGuard = rx("\\b(?:your|my|our|their|update|bayesian)\\s*$")
    private val companyCam = rx("\\bcompany\\s+cam\\b")
    private val benches = rx("\\b(hearing|clutter|garble|maze|butter|prinz|counting|ghost|redstone|personality|idea|motion)\\s?bench\\b")
    private val antigravity = rx("\\banti[-\\s]?gravity\\b")
    private val argonne = rx("\\bargonne\\b")
    private val gemini = rx("\\bgemini\\b")
    private val karpathy = rx("\\b(?:carpathy|karpathi|carpathi)\\b")
    private val cash = rx("\\bcash(?=\\s+(?:rate|hit|hits|miss|misses|read|reads|write|writes|pricing|discount|tokens)\\b)")
    private val promptCashing = rx("\\bprompt\\s+cashing\\b")
    private val cashed = rx("\\bcashed(?=\\s+(?:tokens|input|prompt)\\b)")

    fun fix(text: String): String {
        var t = text
        // GPT [version] <mishearing>  ("GPT six point one soul", "GPT-6.1 tera")
        t = fix(t, gptSol, "Sol"); t = fix(t, gptTerra, "Terra"); t = fix(t, gptLuna, "Luna")
        // Tier lists: "Astra, soul and Terra" - a mishearing next to a correctly heard sibling.
        repeat(3) {
            t = fix(t, tierSolAfter, "Sol"); t = fix(t, tierSolBefore, "Sol")
            t = fix(t, tierTerraAfter, "Terra"); t = fix(t, tierTerraBefore, "Terra")
            t = fix(t, tierLunaAfter, "Luna"); t = fix(t, tierLunaBefore, "Luna")
        }
        // Bare version prefix: "six point one soul", "6.1 Soul", "six soul".
        t = fix(t, versionSol, "Sol")
        // Next to another model name in the same sentence.
        t = modelThenSoul.replace(t) { m ->
            val s = m.value
            trailingSoul.replace(s) { w -> guard(w.value, s.length - w.value.length, s) }
        }
        val s1 = t
        t = soulThenModel.replace(s1) { w -> guard(w.value, w.range.first, s1) }
        val s2 = t
        // Capitalized mid-sentence "Soul" (the recognizer treats it as a name): "tell Soul to".
        t = capSoul.replace(s2) { w -> guard(w.value, w.range.first, s2) }
        // "soul on extra high", "soul light": reasoning-effort and tier phrasing.
        t = fix(t, soulEffort, "Sol")
        // Codex: "codecs" is the product; leave real media-codec talk alone.
        val s3 = t
        t = codecs.replace(s3) { m ->
            val off = m.range.first
            if (MEDIA.containsMatchIn(slice(s3, maxOf(0, off - 60), off + m.value.length + 60))) m.value else "Codex"
        }
        // Claude: clawed / claud / clog+model / cloud+model / claw+limit. The verb "clawed the couch" stays.
        t = clawed.replace(t, "Claude"); t = claw.replace(t, "Claude"); t = claud.replace(t, "Claude"); t = clogCloud.replace(t, "Claude")
        // Kimi, Grok, DeepSeek
        t = kimmy.replace(t, "Kimi"); t = grock.replace(t, "Grok"); t = groc.replace(t, "Grok"); t = deepSeek.replace(t, "DeepSeek")
        // Astra: "GPT-6 asteroid/astro", and next to a tier sibling.
        t = fix(t, gptAstra, "Astra"); t = fix(t, tierAstraAfter, "Astra"); t = fix(t, tierAstraBefore, "Astra")
        // Other names the recognizer splits or misspells.
        t = openAi.replace(t, "OpenAI"); t = chatGpt.replace(t, "ChatGPT")
        t = excalidraw.replace(t, "Excalidraw"); t = semiAnalysis.replace(t, "SemiAnalysis")
        t = vercel.replace(t, "Vercel"); t = qwen.replace(t, "Qwen"); t = slopalytics.replace(t, "Slopalytics")
        t = grokBot.replace(t, "Grok Bot"); t = omarchy.replace(t, "Omarchy"); t = postHog.replace(t, "PostHog"); t = deepSeq.replace(t, "DeepSeek")
        t = openWhispr.replace(t, "OpenWhispr"); t = openRouter.replace(t, "OpenRouter"); t = openCode.replace(t, "OpenCode")
        t = t3Code.replace(t, "T3 Code")
        t = agentsMd.replace(t, "AGENTS.md"); t = claudeMd.replace(t, "CLAUDE.md")
        t = xHigh.replace(t, "xhigh")
        t = maxEffort.replace(t, "max ")
        t = codexPossessive.replace(t, "Codex's")
        t = claudeSonic.replace(t) { m -> m.groupValues[1] + "Sonnet" }; t = sonicVersion.replace(t, "Sonnet")
        t = nemotron.replace(t, "Nemotron")
        t = kua.replace(t, "Cua")
        t = gpg.replace(t, "GPT")
        t = pryors.replace(t, "Priors")
        t = priorsNoun.replace(t, "Priors")
        val s4 = t
        t = priorsPrep.replace(s4) { m ->
            val off = m.range.first
            if (priorsPrepGuard.containsMatchIn(slice(s4, maxOf(0, off - 20), off))) m.value else m.groupValues[1] + "Priors"
        }
        t = companyCam.replace(t, "CompanyCam")
        t = benches.replace(t) { m -> val a = m.groupValues[1]; a.substring(0, 1).uppercase() + a.substring(1).lowercase() + "Bench" }
        t = antigravity.replace(t, "Antigravity")
        val s6 = t
        t = argonne.replace(s6) { m ->
            val off = m.range.first
            if (gemini.containsMatchIn(slice(s6, maxOf(0, off - 60), off + 60))) "Argon" else m.value
        }
        t = karpathy.replace(t, "Karpathy")
        t = cash.replace(t, "cache"); t = promptCashing.replace(t, "prompt caching"); t = cashed.replace(t, "cached")
        return ChatAbbrev.lower(emails(t))
    }

    private const val EM_AT = "(?:\\s+at\\s+|\\s*@\\s*)"
    private const val EM_DOT = "\\s*(?:\\.|dot)\\s*"
    private const val EM_HOST = "(gmail|outlook|icloud|yahoo|hotmail|proton(?:mail)?)${EM_DOT}(com|me)"
    private const val EM_WORDS = "([a-z0-9._+-]+(?:\\s+[a-z0-9._+-]+){0,2})"
    private val emStop by lazy { rx("^(?:at|in|on|to|from|for|of|with|by|is|was|are|be|it|this|that|these|those|the|a|an|my|your|our|his|her|their|me|us|you|i|we|they|he|she|them|him|and|or|but|so|then|just|like|um|uh|probably|actually|maybe|now|still|also|here|there|look|looking|looked|check|checking|checked|go|going|went|sign|signing|signed|log|logging|logged|login|open|opening|opened|visit|visiting|see|seeing|up|out|over|back|try|trying|tried|email|mail|account|address|send|sent|use|using|used)$") }
    private val emSpace by lazy { rx("\\s+") }
    private val emDotWord by lazy { rx("^dot$") }
    private val emEdgeDot by lazy { rx("^\\.|\\.$") }
    private val emCue by lazy { rx("(\\be-?mail(?:\\s+address)?\\s*(?:is|was|:)\\s+|\\b(?:address|account)\\s+is\\s+)${EM_WORDS}\\s+at\\s+${EM_HOST}\\b") }
    private val emWhole by lazy { rx("^(\\s*)${EM_WORDS}\\s+at\\s+${EM_HOST}\\b(?=[.!?]?\\s*$)") }

    private fun emAddress(m: MatchResult): String {
        val (lead, words, host, tld) = m.destructured
        val parts = emSpace.split(words)
        var i = parts.size
        while (i > 0 && !emStop.containsMatchIn(parts[i - 1])) i--
        val local = parts.drop(i).joinToString("") { if (emDotWord.containsMatchIn(it)) "." else it }.lowercase()
        if (local.isEmpty() || emEdgeDot.containsMatchIn(local)) return m.value
        return lead + parts.take(i).joinToString("") { "$it " } + local + "@" + host.lowercase() + "." + tld.lowercase()
    }

    /**
     * Spoken email addresses: "my email is jane doe at gmail.com" -> "my email is janedoe@gmail.com".
     * Needs a cue ("my email is ...") or the whole take being the address, so "looking at gmail.com"
     * stays as it is. Mirror of owEmails in patches/model-names.js. (My build also has rules for my
     * own addresses, including misheard surnames; add yours here.)
     */
    fun emails(text: String): String {
        val t = emCue.replace(text) { m -> emAddress(m) }
        return emWhole.replace(t) { m -> emAddress(m) }
    }
}
