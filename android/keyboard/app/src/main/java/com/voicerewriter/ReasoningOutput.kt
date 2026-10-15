package com.voicerewriter

/** Parse provider reasoning boundaries before text reaches history or the editor. */
object ReasoningOutput {
    fun clean(input: String): String {
        var text = input
        val end = Regex("<\\|think_end\\|>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()
        if (end != null) text = text.substring(end.range.last + 1)
        text = text.replace(Regex("(?is)<\\|think_start\\|>.*$"), "")
        val closed = Regex("(?is)<think>(?:(?!<think>).)*?</think>")
        while (closed.containsMatchIn(text)) text = text.replace(closed, "")
        text = text.replace(Regex("(?is)<think>.*$"), "").replace(Regex("(?i)</think>"), "").trim()
        require(!Regex("(?i)<\\|(?:think|reasoning)[^>]*").containsMatchIn(text)) { "Incomplete reasoning boundary in cleanup response" }
        require(text.isNotEmpty()) { "Cleanup returned no final answer" }
        return text
    }
}
