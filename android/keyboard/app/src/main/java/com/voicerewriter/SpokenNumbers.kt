package com.voicerewriter

/**
 * Spoken number words -> digits, run on the transcript before cleanup.
 * Parakeet always spells numbers out, and the cleanup model only converts them some of the
 * time (it leaves sentence-initial numbers alone, for example). Only unambiguous single
 * numbers are converted. Anything that does not parse as one number ("three thirty",
 * "twenty twenty six") stays as words. A lone "one" stays a word unless a unit or another
 * number follows it. Same rules as the desktop's speed-patch/owNumbers.js.
 */
object SpokenNumbers {
    private val UNITS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val SCALES = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ORD = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9)
    private val UNIT_AFTER = Regex("^(x|percent|%|k|times|minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|months?|years?|dollars?|bucks|cents?|gigs?|gb|mb|tb|ms|am|pm|p\\.m\\.|a\\.m\\.|point|o'clock|star|stars|people|users?)$", RegexOption.IGNORE_CASE)
    private val KEEP_BEFORE = Regex("^(high|cloud|take|catch)$", RegexOption.IGNORE_CASE) // high five, cloud nine
    private val KEEP_AFTER = Regex("^(factor|way|ways)$", RegexOption.IGNORE_CASE) // two-factor, three-way
    private val SEP = Regex("\\s+|-")
    private val CLAUSE_END = Regex("[.,!?;:]$")
    private val TRAIL = Regex("[.,!?;:\"')]+$")
    private val LEAD = Regex("^[\"'(]+")

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in SEP.findAll(text)) { out.add(text.substring(last, m.range.first)); out.add(m.value); last = m.range.last + 1 }
        out.add(text.substring(last))
        return out
    }

    fun convert(text: String): String = runCatching { convertUnsafe(text) }.getOrDefault(text)

    private fun convertUnsafe(text: String): String {
        val tokens = tokenize(text)
        fun tok(i: Int) = tokens.getOrElse(i) { "" }
        fun word(i: Int) = tok(i).replace(TRAIL, "").replace(LEAD, "").lowercase()
        fun isNum(w: String) = w in UNITS || w in TENS || w in SCALES
        fun digit(w: String): Int? = UNITS[w]?.takeIf { it < 10 }
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val w = word(i)
            if (!isNum(w) || w in SCALES || w == "zero") { out.add(tokens[i]); i++; continue }
            // Collect a run of number words joined by spaces or hyphens, stopping at punctuation.
            val idx = arrayListOf(i)
            var j = i
            while (!CLAUSE_END.containsMatchIn(tok(j)) && j + 2 < tokens.size && SEP.matches(tok(j + 1)) &&
                (isNum(word(j + 2)) || word(j + 2) == "and" && isNum(word(j + 4)) && word(j + 4) != "one")) {
                if (word(j + 2) == "and") { if (word(j) !in SCALES) break; idx.add(j + 2); j += 2 }
                j += 2; idx.add(j)
            }
            val ws = idx.map { word(it) }.filter { it != "and" }
            // Parse; reject anything that is not a single well-formed number.
            var total = 0L; var cur = 0L; var ok = true; var last: String? = null
            for (x in ws) {
                val u = UNITS[x]; val t = TENS[x]
                if (u != null) {
                    if (last == "unit" || last == "teen" || (last == "tens" && (u >= 10 || u == 0))) { ok = false; break }
                    cur += u; last = if (u >= 10) "teen" else "unit"
                } else if (t != null) {
                    if (last != null && last != "scale") { ok = false; break }
                    cur += t; last = "tens"
                } else {
                    val s = SCALES.getValue(x)
                    if (last == null || last == "scale" && s != 100L && cur == 0L) { ok = false; break }
                    if (s == 100L) { if (cur >= 100 || cur == 0L) { ok = false; break }; cur *= 100 } else { total += cur * s; cur = 0 }
                    last = "scale"
                }
            }
            val end = idx.last()
            var nextI = end + 2
            val prev = if (out.size >= 2) out[out.size - 2].replace(Regex("[^A-Za-z]"), "") else ""
            var after = word(nextI)
            val endsClause = CLAUSE_END.containsMatchIn(tok(end))
            if (!ok) { for (k in i..end) out.add(tokens[k]); i = end + 1; continue }
            var n = total + cur
            var suffix = ""
            // "twenty first" -> 21st
            val ord = ORD[after]
            if (!endsClause && last == "tens" && ord != null) {
                n += ord; suffix = when (ord) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
                nextI += 2; after = word(nextI)
            }
            // "five point five" -> 5.5, "two point one four" -> 2.14
            var decimals = ""
            if (suffix.isEmpty() && !endsClause && after == "point" && digit(word(nextI + 2)) != null) {
                var k = nextI + 2
                while (digit(word(k)) != null) {
                    decimals += digit(word(k))
                    if (CLAUSE_END.containsMatchIn(tok(k)) || !Regex("^\\s+$").matches(tok(k + 1))) break
                    k += 2
                }
                if (digit(word(k)) == null) k -= 2
                nextI = k + 2; after = word(nextI)
            }
            val lone = ws.size == 1
            val unitFollows = !endsClause && UNIT_AFTER.matches(after) && !(ws[0] == "one" && lone && after == "day")
            val listNext = isNum(word(end + 2)) || word(end + 2).firstOrNull()?.isDigit() == true
            if ((lone && ws[0] == "one" && !unitFollows && suffix.isEmpty() && decimals.isEmpty() && !listNext) ||
                (KEEP_BEFORE.matches(prev) && lone) || (!endsClause && KEEP_AFTER.matches(after))) {
                for (k in i..end) out.add(tokens[k]); i = end + 1; continue
            }
            val lead = LEAD.find(tokens[i])?.value ?: ""
            val lastTok = if (suffix.isNotEmpty() || decimals.isNotEmpty()) nextI - 2 else end
            val trail = TRAIL.find(tokens[lastTok])?.value ?: ""
            if (decimals.isNotEmpty()) suffix = ".$decimals"
            val digits = if (n >= 10000) String.format(java.util.Locale.US, "%,d", n) else n.toString()
            // A single capital letter before the number is a product name: "T three" -> "T3".
            if (out.size >= 2 && Regex("^\\s+$").matches(out.last()) && Regex("^[B-HJ-Z]$").matches(out[out.size - 2])) out.removeAt(out.size - 1)
            out.add(lead + digits + suffix + trail)
            i = lastTok + 1
        }
        return out.joinToString("")
    }
}
