package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencySupportTest {
    @Test
    fun recordingLevelIsBoundedForPcmPeaks() {
        assertEquals(0f, AudioRecorder.peakToLevel(-1), 0f)
        assertEquals(0f, AudioRecorder.peakToLevel(0), 0f)
        assertEquals(1f, AudioRecorder.peakToLevel(Short.MAX_VALUE.toInt()), 0f)
        assertEquals(1f, AudioRecorder.peakToLevel(Int.MAX_VALUE), 0f)
    }

    @Test
    fun parakeetHotwordsNormalizeTheSharedGlossary() {
        assertEquals(
            listOf("adam", "openwhispr", "mercury"),
            LocalParakeetStt.hotwordsFromBiasPrompt("Glossary: Adam, OpenWhispr, Mercury."),
        )
    }

    @Test
    fun parakeetHotwordsStayWithinTheDecoderLimit() {
        val prompt = "Glossary: " + (1..55).joinToString(", ") { "Term$it" } + "."
        val terms = LocalParakeetStt.hotwordsFromBiasPrompt(prompt)
        assertEquals(50, terms.size)
        assertTrue(terms.all { it == it.lowercase() })
        assertEquals("term50", terms.last())
    }
}
