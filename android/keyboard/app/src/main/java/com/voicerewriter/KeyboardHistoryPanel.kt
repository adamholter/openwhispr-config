package com.voicerewriter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dictation history inside the keyboard: tap a dictation to insert it, long-press to insert the
 * raw transcript, and transcribe saved (interrupted or cancelled) recordings without leaving the
 * app you are typing in. Drawn over the key area, like the voice panel.
 */
class KeyboardHistoryPanel(context: Context, private val actions: Actions) : LinearLayout(context) {
    interface Actions {
        fun insertFromHistory(text: String)
        fun closeHistory()
        fun openHistoryApp()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context)
    private val title = TextView(context)
    private val close = ImageButton(context)
    private val openApp = TextView(context)
    private var shown = 30

    private var cardColor = 0xFF1A1A1A.toInt()
    private var textColor = Color.WHITE
    private var mutedColor = 0xFFBDBDBD.toInt()
    private var chipColor = 0xFF262626.toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setPadding(dp(8), dp(4), dp(8), dp(4))

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.apply { text = "Dictation history"; textSize = 15f; setPadding(dp(6), 0, 0, 0) }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        openApp.apply {
            text = "Full history"; textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0); contentDescription = "Open full history in the OpenWhispr app"
            setOnClickListener { actions.openHistoryApp() }
        }
        header.addView(openApp, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
        close.apply {
            setImageResource(R.drawable.ic_close); contentDescription = "Close history"
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { actions.closeHistory() }
        }
        header.addView(close, LayoutParams(dp(44), dp(40)).apply { leftMargin = dp(4) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        applyColors()
    }

    fun applyTheme(colors: Colors?) {
        if (colors == null) return
        val key = colors.get(ColorType.KEY_BACKGROUND)
        cardColor = if (Color.alpha(key) < 0x80) 0xFF1A1A1A.toInt() else key
        chipColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND).let { if (Color.alpha(it) < 0x80) 0xFF262626.toInt() else it }
        textColor = colors.get(ColorType.KEY_TEXT)
        mutedColor = (textColor and 0x00FFFFFF) or 0xA6000000.toInt()
        setBackground(null)
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)
        applyColors()
    }

    private fun applyColors() {
        title.setTextColor(textColor)
        openApp.setTextColor(textColor)
        openApp.background = ripple(chipColor, 16)
        close.setColorFilter(textColor)
    }

    /** Reload from disk; call each time the panel is shown. */
    fun refresh() {
        shown = 30
        render()
    }

    private fun render() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val app = context.applicationContext
            val entries = withContext(Dispatchers.IO) { DictationHistory.all(app).sortedByDescending { it.timestamp } }
            val pending = withContext(Dispatchers.IO) { PendingAudio.unfinished(app) }
            list.removeAllViews()
            if (entries.isEmpty() && pending.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "Your dictations will show up here.\nTap one to insert it."
                    setTextColor(mutedColor); textSize = 14f; gravity = Gravity.CENTER
                    setPadding(dp(16), dp(40), dp(16), dp(40))
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                return@launch
            }
            val timeline = (entries.take(shown).map { it.timestamp to (it as Any) } +
                pending.map { it.timestamp to (it as Any) }).sortedByDescending { it.first }
            timeline.forEach { (_, item) ->
                if (item is PendingRecording) list.addView(pendingCard(item)) else list.addView(entryCard(item as DictationEntry))
            }
            if (entries.size > shown) {
                list.addView(TextView(context).apply {
                    text = "Show older"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(textColor)
                    background = ripple(chipColor, 16)
                    setOnClickListener { shown += 50; render() }
                }, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4); bottomMargin = dp(8) })
            }
            list.addView(TextView(context).apply {
                text = "Tap to insert · long-press for the raw transcript"
                setTextColor(mutedColor); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
            })
        }
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = ripple(cardColor, 12)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun meta(text: String) = TextView(context).apply {
        this.text = text; textSize = 11f; setTextColor(mutedColor); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(3), 0, 0)
    }

    private fun entryCard(entry: DictationEntry): View = card().apply {
        addView(TextView(context).apply {
            text = entry.after.ifBlank { entry.before }
            textSize = 15f; setTextColor(textColor); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        })
        addView(meta(listOf(whenLabel(entry.timestamp), entry.appLabel).filter { it.isNotBlank() }.joinToString(" · ")))
        contentDescription = "Insert dictation: ${entry.after}"
        setOnClickListener { entry.after.ifBlank { entry.before }.takeIf { it.isNotBlank() }?.let(actions::insertFromHistory) }
        setOnLongClickListener {
            entry.before.takeIf { it.isNotBlank() }?.let(actions::insertFromHistory); true
        }
    }

    private fun pendingCard(record: PendingRecording): View {
        val c = card()
        val label = if (record.cancelled) "Cancelled recording" else "Interrupted recording"
        val state = TextView(context).apply {
            text = "$label · ${duration(record.durationSec)}. Audio saved on this phone."
            textSize = 14f; setTextColor(textColor)
        }
        c.addView(state)
        c.addView(meta(listOf(whenLabel(record.timestamp), record.appLabel).filter { it.isNotBlank() }.joinToString(" · ")))
        val button = TextView(context).apply {
            text = "Transcribe and insert"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(textColor)
            setPadding(dp(14), 0, dp(14), 0); background = ripple(chipColor, 16)
        }
        c.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply { topMargin = dp(8) })
        button.setOnClickListener {
            button.isEnabled = false; button.text = "Transcribing…"
            scope.launch {
                try {
                    val text = ImeDictationPipeline.retryToHistory(context.applicationContext, record.id)
                    if (text.isNotBlank()) actions.insertFromHistory(text) else {
                        state.text = "No speech found in this recording."; button.text = "Try again"; button.isEnabled = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.text = "Could not transcribe. The audio is still saved. ${e.message.orEmpty().take(80)}"
                    button.text = "Try again"; button.isEnabled = true
                }
            }
        }
        return c
    }

    private fun whenLabel(ts: Long): String {
        val time = DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
        return when {
            DateUtils.isToday(ts) -> time
            DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> "Yesterday $time"
            else -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " $time"
        }
    }

    private fun duration(sec: Int) = if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"

    private fun ripple(color: Int, radiusDp: Int): RippleDrawable {
        val shape = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }
        val pressed = Color.argb(50, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        return RippleDrawable(ColorStateList.valueOf(pressed), shape, null)
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
