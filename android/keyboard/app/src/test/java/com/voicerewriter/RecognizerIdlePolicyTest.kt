package com.voicerewriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerIdlePolicyTest {
    @Test
    fun evictionRequiresAnIdleLoadedRecognizer() {
        assertTrue(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 0,
                releaseRequested = false,
                idleForMs = 60_000,
            ),
        )
        assertFalse(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 1,
                releaseRequested = false,
                idleForMs = 60_000,
            ),
        )
        assertFalse(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 0,
                releaseRequested = false,
                idleForMs = 59_999,
            ),
        )
    }

    @Test
    fun explicitReleaseDoesNotRaceAnIdleTimer() {
        assertFalse(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 0,
                releaseRequested = true,
                idleForMs = 60_000,
            ),
        )
    }
}
