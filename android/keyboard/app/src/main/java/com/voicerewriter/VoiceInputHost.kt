package com.voicerewriter
import android.content.Context

/** [incognito]: the editor asked for no personalized learning (private tabs, sensitive fields). */
data class VoiceEditorSession @JvmOverloads constructor(val generation: Long, val packageName: String, val fieldId: Int, val password: Boolean,
                              val incognito: Boolean = false)

/**
 * A selection captured from the active editor. The IME must revalidate every field before it
 * replaces the selection because recording and rewriting are asynchronous.
 */
data class VoiceSelectionTarget(
    val session: VoiceEditorSession,
    val selectionStart: Int,
    val selectionEnd: Int,
    val text: String,
)

interface VoiceInputHost {
    val voiceContext: Context
    fun isSessionCurrent(session: VoiceEditorSession): Boolean
    fun commitVoiceText(session: VoiceEditorSession, text: String): Boolean
    fun isSelectionCurrent(target: VoiceSelectionTarget): Boolean
    fun commitSelectionEdit(target: VoiceSelectionTarget, replacement: String): Boolean
    fun setVoiceStatus(text: String, recording: Boolean = false)
    fun cleanupMode(): String
    fun translationTarget(): String?
    fun returnToVoice()
}
