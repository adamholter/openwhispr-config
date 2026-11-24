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
                idleForMs = 600_000,
            ),
        )
        assertFalse(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 1,
                releaseRequested = false,
                idleForMs = 600_000,
            ),
        )
        assertFalse(
            RecognizerIdlePolicy.shouldEvict(
                hasRecognizer = true,
                activeDecodes = 0,
                releaseRequested = false,
                idleForMs = 599_999,
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
                idleForMs = 600_000,
            ),
        )
    }
}
