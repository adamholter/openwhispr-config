package com.voicerewriter

/** Resource policy for the native recognizer cache. */
internal object RecognizerIdlePolicy {
    const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L

    fun shouldEvict(
        hasRecognizer: Boolean,
        activeDecodes: Int,
        releaseRequested: Boolean,
        idleForMs: Long,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    ): Boolean =
        hasRecognizer &&
            activeDecodes == 0 &&
            !releaseRequested &&
            idleForMs >= idleTimeoutMs
}
