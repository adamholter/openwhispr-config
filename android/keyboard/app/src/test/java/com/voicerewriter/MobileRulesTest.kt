package com.voicerewriter

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MobileRulesTest {
    @Test
    fun rulesAreCaseInsensitiveAndWholeWordOnly() {
        val rules = listOf(MobileRule("Jeff", "Jev"))
        assertEquals("Jev Jev Jeffrey", MobileRulesCorrector.apply("Jeff jeff Jeffrey", rules))
        assertEquals("Jev_Jev", MobileRulesCorrector.apply("Jeff_Jeff", rules))
    }

    @Test
    fun longerTriggersWinInOnePass() {
        val rules = listOf(
            MobileRule("prompt", "short"),
            MobileRule("whole prompt", "FULL PROMPT"),
            // If replacement text were fed back into the matcher this would change FULL PROMPT.
            MobileRule("full prompt", "BROKEN"),
        )
        assertEquals("FULL PROMPT", MobileRulesCorrector.apply("whole prompt", rules))
    }

    @Test
    fun punctuationAndNewlinesRemainOutsideTheReplacement() {
        val rules = listOf(MobileRule("phrase", "a\nwhole prompt"))
        assertEquals("Say a\nwhole prompt, then stop.", MobileRulesCorrector.apply("Say phrase, then stop.", rules))
    }

    @Test
    fun emptyRulesAndEmptyTextAreNoOps() {
        assertEquals("Jeff", MobileRulesCorrector.apply("Jeff", emptyList()))
        assertEquals("", MobileRulesCorrector.apply("", listOf(MobileRule("Jeff", "Jev"))))
    }

    @Test
    fun boundedReaderAcceptsSmallBodiesWithoutExpectingTheMaximumSize() {
        val body = BridgeSync.readBounded(ByteArrayInputStream("{\"rules\":[]}".toByteArray()), 1024)
        assertNotNull(body)
        assertEquals("{\"rules\":[]}", body!!.toString(Charsets.UTF_8))
        assertNull(BridgeSync.readBounded(ByteArrayInputStream(ByteArray(5)), 4))
    }
}
