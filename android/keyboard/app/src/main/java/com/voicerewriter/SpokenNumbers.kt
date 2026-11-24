package com.voicerewriter

import java.util.Locale
import java.util.regex.Pattern

/**
 * Spoken number words -> digits, run on the transcript before cleanup.
 * Parakeet always spells numbers out, and the cleanup model only converts them some of the
 * time (it leaves sentence-initial numbers alone, for example). Only unambiguous numbers are
 * converted; a run that does not parse stays as words, never half-converted. A lone "one" stays
 * a word unless a unit follows it. Also: decimals ("zero point five", "one point oh"), scale
 * words ("six hundred billion" -> 600 billion), years ("twenty twenty six"), clock times
 * ("at three thirty" -> 3:30), multipliers ("five x" -> 5x) and model sizes ("nine B model" -> 9B).
 * Line-for-line port of the desktop's speed-patch/owNumbers.js; SpokenNumbersParityTest proves
 * byte-identical output on the transcript corpus.
 */
object SpokenNumbers {
    private val UNITS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val SCALES = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L,
        "billion" to 1_000_000_000L, "trillion" to 1_000_000_000_000L)
    private val BIG = setOf("million", "billion", "trillion")
    private val ORD = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9)
    private val UNIT_AFTER = JsRegex.compile("^(x|percent|%|k|times|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|months?|years?|dollars?|bucks|cents?|gigs?|gb|mb|tb|ms|am|pm|p\\.m\\.|a\\.m\\.|point|o'clock|star|stars|people|users?|b|billion|million|trillion|thousand)$", true)
    private val KEEP_BEFORE = JsRegex.compile("^(high|cloud|take|catch)$", true) // high five, cloud nine, take five, catch twenty-two
    private val KEEP_AFTER = JsRegex.compile("^(factor|way|ways)$", true) // two-factor, three-way
    private val TIME_BEFORE = JsRegex.compile("^(at|by|around|until|till|til|before|after|from|since|about|eta)$", true)
    private val YEAR_BEFORE = JsRegex.compile("^(in|since|by|from|until|till|of|year|circa|early|late|mid|back|summer|winter|spring|fall|autumn|class)$", true)
    private val B_AFTER = JsRegex.compile("^(model|models|parameter|parameters|params|param|dense|moe|active|total|version|variant|class|range|size|scale|sized)$", true)
    private val SPLIT = JsRegex.compile("\\s+|-")
    private val SEP_TOK = JsRegex.compile("^(\\s+|-)$")
    private val WS_TOK = JsRegex.compile("^\\s+$")
    private val TRAIL = JsRegex.compile("[.,!?;:\"')]+$")
    private val LEAD = JsRegex.compile("^[\"'(]+")
    private val ENDS = JsRegex.compile("[.,!?;:][\"')]*$")
    private val CLAUSE_END = JsRegex.compile("[.,!?;:]$")
    private val NON_ALPHA_APOS = JsRegex.compile("[^A-Za-z']")
    private val NON_ALPHA = JsRegex.compile("[^A-Za-z]")
    private val AMPM = JsRegex.compile("^(am|pm|a\\.m|p\\.m)$", true)
    private val ARTICLE_SCALE = JsRegex.compile("^(hundred|thousand|million|billion)$")
    private val OH = JsRegex.compile("^(oh|o)$")
    private val LEADING_DIGIT = JsRegex.compile("^\\d")
    private val CAP_LETTER = JsRegex.compile("^[B-HJ-Z]$")
    private val GLUE_X = JsRegex.compile("^[xX]$")
    private val GLUE_B = JsRegex.compile("^[bB]$")
    private val GLUE_O = JsRegex.compile("^o$", true)
    private val GPT_END = JsRegex.compile("gpt$", true)
    private val ASPECT = JsRegex.compile("^(aspect|ratio)$")
    private val OR_TO_AND = JsRegex.compile("^(or|to|and)$")
    private val PERCENT = JsRegex.compile("^(percent|%)$")
    private val RATIO = JsRegex.compile("\\b(\\d{1,2})[- ](\\d{1,2})(?=\\s+(?:aspect\\s+)?ratio\\b)", true)
    private val YEAR_START = JsRegex.compile("^(nineteen|twenty)$")

    fun convert(text: String): String = runCatching { convertUnsafe(text) }.getOrDefault(text)

    private fun fmt(n: Long): String = if (n >= 10000) String.format(Locale.US, "%,d", n) else n.toString()

    private fun convertUnsafe(text: String): String {
        val tokens = JsRegex.split(text, SPLIT)
        fun tok(i: Int) = tokens.getOrElse(i) { "" }
        fun word(i: Int) = LEAD.replaceFirst(TRAIL.replaceFirst(tok(i), ""), "").lowercase()
        fun isNum(w: String) = w in UNITS || w in TENS || w in SCALES
        fun isSep(i: Int) = SEP_TOK.containsMatchIn(tok(i))
        fun endsAt(i: Int) = ENDS.containsMatchIn(tok(i))
        fun prevWord(o: List<String>): String {
            for (k in o.indices.reversed()) if (o[k].isNotEmpty() && !SEP_TOK.containsMatchIn(o[k])) return o[k]
            return ""
        }
        val out = ArrayList<String>()
        var i = 0

        // Push the converted number, then glue on "x" (5x), "B" (9B) and "o" (GPT-4o) when they follow.
        fun emit(valueIn: String, lastTok: Int) {
            var value = valueIn
            val lead = LEAD.find(tokens[i])?.value ?: ""
            var trail = TRAIL.find(tokens[lastTok])?.value ?: ""
            var k = lastTok
            if (trail.isEmpty() && WS_TOK.containsMatchIn(tok(k + 1)) && LEADING_DIGIT.containsMatchIn(value)) {
                val nxt = tok(k + 2)
                val bare = TRAIL.replaceFirst(nxt, "")
                val nxtWord = NON_ALPHA.replace(tok(k + 4), "")
                val glue = when {
                    GLUE_X.containsMatchIn(bare) -> "x"
                    GLUE_B.containsMatchIn(bare) && (B_AFTER.containsMatchIn(nxtWord) || CLAUSE_END.containsMatchIn(nxt) || tok(k + 3).isEmpty()) -> "B"
                    GLUE_O.containsMatchIn(bare) && GPT_END.containsMatchIn(NON_ALPHA.replace(prevWord(out), "")) -> "o"
                    else -> ""
                }
                if (glue.isNotEmpty()) { value += glue; trail = TRAIL.find(nxt)?.value ?: ""; k += 2 }
            }
            out.add(lead + value + trail)
            i = k + 1
        }

        while (i < tokens.size) {
            val w = word(i)
            val pw = NON_ALPHA_APOS.replace(prevWord(out), "").lowercase()
            // "a hundred", "a thousand": the article stands for one.
            val article = w == "a" && ARTICLE_SCALE.containsMatchIn(word(i + 2)) && isSep(i + 1) && !endsAt(i)
            // A lone digit word right after "point" belongs to a decimal we could not parse; leave it alone.
            if (pw == "point" && !article) { out.add(tokens[i]); i++; continue }
            if (!article && (!isNum(w) || w in SCALES || (w == "zero" && !(word(i + 2) == "point" && word(i + 4) in UNITS)))) {
                out.add(tokens[i]); i++; continue
            }
            // Collect a run of number words joined by spaces or hyphens, stopping at punctuation.
            val idx = arrayListOf(i)
            var j = i
            while (!endsAt(j) && j + 2 < tokens.size && isSep(j + 1) &&
                (isNum(word(j + 2)) || word(j + 2) == "and" && isNum(word(j + 4)) && word(j + 4) != "one")) {
                if (word(j + 2) == "and") { if (word(j) !in SCALES) break; idx.add(j + 2); j += 2 }
                j += 2; idx.add(j)
            }
            val ws = idx.map { word(it) }.filter { it != "and" }.map { if (it == "a") "one" else it }
            // Parse; reject anything that is not a single well-formed number.
            var total = 0L; var cur = 0L; var ok = true; var last: String? = null
            var bigScale: String? = null; var bigBase = 0L
            for (x in ws) {
                val u = UNITS[x]; val t = TENS[x]
                if (u != null) {
                    if (last == "unit" || last == "teen" || (last == "tens" && u >= 10) || (last == "tens" && u == 0)) { ok = false; break }
                    cur += u; last = if (u >= 10) "teen" else "unit"
                } else if (t != null) {
                    if (last != null && last != "scale") { ok = false; break }
                    cur += t; last = "tens"
                } else {
                    val s = SCALES.getValue(x)
                    if (last == null || last == "scale" && s != 100L && cur == 0L) { ok = false; break }
                    if (s == 100L) {
                        if (cur >= 100 || cur == 0L) { ok = false; break }
                        cur *= 100
                    } else {
                        if (x in BIG && total == 0L) { bigScale = x; bigBase = cur } else bigScale = null
                        total += cur * s; cur = 0
                    }
                    last = "scale"
                }
            }
            if (bigScale != null && cur != 0L) bigScale = null // "two million five hundred" is a plain number
            val end = idx.last()
            var nextI = end + 2
            var after = word(nextI)
            val endsClause = endsAt(end)
            val start = i
            fun keepWords() { for (k in start..end) out.add(tokens[k]); i = end + 1 }
            if (!ok) {
                // Years: "twenty twenty six" -> 2026, "nineteen ninety nine" -> 1999.
                val year = parseYear(ws)
                if (year != null && (ws.size >= 3 || YEAR_BEFORE.containsMatchIn(pw))) { emit(year.toString(), end); continue }
                // Aspect ratios: "sixteen nine aspect ratio" -> 16:9.
                if (ws.size == 2 && ws.all { x -> UNITS[x].let { it != null && it >= 1 && it <= 32 } } && ASPECT.containsMatchIn(after)) {
                    emit(UNITS.getValue(ws[0]).toString() + ":" + UNITS.getValue(ws[1]), end); continue
                }
                // Clock times: "at three thirty" -> 3:30, "seven fifteen pm" -> 7:15 pm.
                val time = parseTime(ws)
                if (time != null && (TIME_BEFORE.containsMatchIn(pw) || AMPM.containsMatchIn(after))) { emit(time, end); continue }
                keepWords(); continue
            }
            var n = total + cur
            // "twenty first" -> 21st
            var suffix = ""
            val ord = ORD[after]
            if (!endsClause && last == "tens" && ord != null) {
                n += ord
                suffix = when (ord) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
                nextI += 2; after = word(nextI)
            }
            // "five point five" -> 5.5, "two point one four" -> 2.14, "one point oh" -> 1.0
            var decimals = ""
            fun digitAt(k: Int): Int? {
                val u = UNITS[word(k)]
                return if (u != null && u < 10) u else if (OH.containsMatchIn(word(k))) 0 else null
            }
            if (suffix.isEmpty() && !endsClause && bigScale == null && after == "point" && digitAt(nextI + 2) != null) {
                var k = nextI + 2
                while (digitAt(k) != null) {
                    decimals += digitAt(k)
                    if (endsAt(k) || !WS_TOK.containsMatchIn(tok(k + 1))) break
                    k += 2
                }
                if (digitAt(k) == null) k -= 2
                nextI = k + 2; after = word(nextI)
            }
            // "two point five million" -> 2.5 million
            var bigAfter = ""
            if (decimals.isNotEmpty() && !endsAt(nextI - 2) && after in BIG) { bigAfter = " $after"; nextI += 2; after = word(nextI) }
            val lone = ws.size == 1
            val prev = pw
            val unitFollows = !endsClause && UNIT_AFTER.containsMatchIn(after) && !(ws[0] == "one" && lone && after == "day")
            val listNext = isNum(word(end + 2)) || LEADING_DIGIT.containsMatchIn(word(end + 2)) ||
                (OR_TO_AND.containsMatchIn(after) && !endsClause && (isNum(word(nextI + 2)) || LEADING_DIGIT.containsMatchIn(word(nextI + 2))))
            if ((lone && ws[0] == "one" && !unitFollows && suffix.isEmpty() && decimals.isEmpty() && !listNext) ||
                (KEEP_BEFORE.containsMatchIn(prev) && lone) || (!endsClause && KEEP_AFTER.containsMatchIn(after))) {
                keepWords(); continue
            }
            val lastTok = if (suffix.isNotEmpty() || decimals.isNotEmpty()) nextI - 2 else end
            if (decimals.isNotEmpty()) suffix = ".$decimals$bigAfter"
            val digits = if (bigScale != null) fmt(bigBase) + " " + bigScale else fmt(n)
            if (article && ws.size == 2 && suffix.isEmpty() && !PERCENT.containsMatchIn(after)) { keepWords(); continue } // "a hundred times" reads fine as words
            // A single capital letter before the number is a product name: "T three" -> "T3", "M four" -> "M4".
            if (out.size >= 2 && WS_TOK.containsMatchIn(out[out.size - 1]) && CAP_LETTER.containsMatchIn(out[out.size - 2])) out.removeAt(out.size - 1)
            emit(digits + suffix, lastTok)
        }
        return RATIO.replace(out.joinToString("")) { m -> m.groupValues[1] + ":" + m.groupValues[2] }
    }

    private fun parseYear(ws: List<String>): Int? {
        if (ws.size < 2 || ws.size > 3 || !YEAR_START.containsMatchIn(ws[0])) return null
        val century = UNITS[ws[0]] ?: TENS[ws[0]] ?: return null
        val rest = ws.subList(1, ws.size)
        val r0u = UNITS[rest[0]]; val r0t = TENS[rest[0]]
        val yy: Int = if (rest.size == 1 && r0t != null) r0t
        else if (rest.size == 1 && r0u != null && r0u >= 10) r0u
        else if (rest.size == 2 && r0t != null && UNITS[rest[1]].let { it != null && it >= 1 && it <= 9 }) r0t + UNITS.getValue(rest[1])
        else return null
        return century * 100 + yy
    }

    private fun parseTime(ws: List<String>): String? {
        if (ws.size < 2 || ws.size > 3) return null
        val h = UNITS[ws[0]]
        if (h == null || h < 1 || h > 12) return null
        val rest = ws.subList(1, ws.size)
        val r0u = UNITS[rest[0]]; val r0t = TENS[rest[0]]
        val mm: Int = if (rest.size == 1 && r0t != null && r0t <= 50) r0t
        else if (rest.size == 1 && r0u != null && r0u >= 10) r0u
        else if (rest.size == 2 && r0t != null && r0t <= 50 && UNITS[rest[1]].let { it != null && it >= 1 && it <= 9 }) r0t + UNITS.getValue(rest[1])
        else return null
        return "$h:" + mm.toString().padStart(2, '0')
    }
}

/**
 * Compiles a JavaScript (non-`u`) regex source so it behaves identically on the desktop JVM and
 * on Android's ICU-backed java.util.regex. Both engines disagree with JS on `\b`, `\s`, `\d`,
 * `\w` and `$` (Java's `$` also matches before a final line terminator; ICU's classes are
 * Unicode-aware), so those are rewritten to explicit ASCII/JS-whitespace forms. Case-insensitive
 * matching is ASCII-only, like a JS `i` flag on an ASCII pattern. Shared by SpokenNumbers,
 * ModelNames and SpokenEmoji.
 */
internal object JsRegex {
    /** JS `\s`: WhiteSpace (incl. all Zs and U+FEFF) plus LineTerminator. */
    const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val W = "A-Za-z0-9_"
    private const val B = "(?:(?<=[$W])(?![$W])|(?<![$W])(?=[$W]))"
    private const val NB = "(?:(?<=[$W])(?=[$W])|(?<![$W])(?![$W]))"

    fun translate(js: String): String {
        val sb = StringBuilder()
        var inClass = false
        var i = 0
        while (i < js.length) {
            val c = js[i]
            if (c == '\\' && i + 1 < js.length) {
                val n = js[i + 1]
                when {
                    n == 'b' && !inClass -> sb.append(B)
                    n == 'B' && !inClass -> sb.append(NB)
                    n == 's' -> sb.append(if (inClass) WS else "[$WS]")
                    n == 'S' && !inClass -> sb.append("[^$WS]")
                    n == 'd' -> sb.append(if (inClass) "0-9" else "[0-9]")
                    n == 'D' && !inClass -> sb.append("[^0-9]")
                    n == 'w' -> sb.append(if (inClass) W else "[$W]")
                    n == 'W' && !inClass -> sb.append("[^$W]")
                    else -> sb.append(c).append(n)
                }
                i += 2; continue
            }
            when {
                !inClass && c == '[' -> { inClass = true; sb.append(c) }
                inClass && c == ']' -> { inClass = false; sb.append(c) }
                !inClass && c == '$' -> sb.append("\\z")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun compile(js: String, ignoreCase: Boolean = false): Regex =
        Pattern.compile(translate(js), if (ignoreCase) Pattern.CASE_INSENSITIVE else 0).toRegex()

    /** JS `text.split(/(sep)/)`: pieces with the separators kept between them (sep must not match ""). */
    fun split(text: String, sep: Regex): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in sep.findAll(text)) { out.add(text.substring(last, m.range.first)); out.add(m.value); last = m.range.last + 1 }
        out.add(text.substring(last))
        return out
    }
}
