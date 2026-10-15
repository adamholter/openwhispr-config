package com.voicerewriter

import org.junit.Assert.*
import org.junit.Test

class DictationContentTest {
    @Test fun rejectsCollapsedSentences() {
        val sentence = "The package arrived beside the blue front door."
        assertFalse(RewriteEngine.preservesContent(List(3) { sentence }.joinToString(" "), sentence, false))
    }
    @Test fun permitsPunctuationAndHesitationCleanup() {
        assertTrue(RewriteEngine.preservesContent("um the package arrived beside the blue front door", "The package arrived beside the blue front door.", false))
    }
    @Test fun permitsExplicitSelfCorrection() {
        assertTrue(RewriteEngine.preservesContent("send the package to mark no wait send the package to john", "Send the package to John.", true))
    }
}
