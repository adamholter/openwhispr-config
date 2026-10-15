package com.voicerewriter;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;

/**
 * Voice controls drawn in the key area of the HeliBoard keyboard. The panel is transparent so
 * the keyboard theme background shows through, and every control takes its colors from the
 * active theme. HeliBoard's toolbar strip stays visible above it.
 */
public final class VoicePanelView extends LinearLayout {
    public interface Actions {
        void showTyping(); void openHistory(); void deleteBackward();
        void insertText(String text); void performEnter();
        void cycleLanguage(); void cycleCleanup(); void editSelection(); void undo();
    }

    private static final String READY = "Tap to speak";

    private final VoiceInputHost host;
    private final Actions actions;
    private VoiceEditorSession session;
    private VoiceSelectionTarget selectionTarget;
    private boolean selectionEditMode;
    private boolean busy;
    private boolean recordingNow;
    private boolean repeatingDelete;
    private boolean cleanupRaw;

    private final TextView history;
    private final TextView status;
    private final TextView cleanup;
    private final ProgressBar inputLevel;
    private final ImageButton cancel;
    private final FrameLayout micContainer;
    private final ImageButton mic;
    private final ProgressBar progress;
    private final TextView selectionEdit;
    private final LinearLayout keyRows;
    private final ImageButton undo;
    private final ImageButton delete;
    private final TextView abc;
    private final TextView space;
    private final ImageButton enter;
    private final TextView[] punctuationKeys;

    // Theme colors, refreshed by applyTheme().
    private int keyColor = Color.rgb(61, 61, 65);
    private int keyText = Color.rgb(245, 245, 243);
    private int functionalColor = Color.rgb(52, 52, 56);
    private int functionalText = Color.rgb(245, 245, 243);
    private int actionColor = Color.rgb(245, 245, 243);
    private int actionIcon = Color.rgb(43, 43, 46);
    private int spaceColor = Color.rgb(61, 61, 65);
    private int hintText = Color.rgb(184, 184, 190);

    private ObjectAnimator micTransition;
    private int micResource;

    private final Runnable meter = new Runnable() {
        @Override public void run() {
            if (!recordingNow || !isShown()) return;
            inputLevel.setProgress(Math.round(ImeDictationPipeline.INSTANCE.recordingLevel() * 100), motionEnabled());
            postDelayed(this, 80);
        }
    };
    private final Runnable repeatDelete = new Runnable() {
        @Override public void run() {
            if (!repeatingDelete) return;
            actions.deleteBackward();
            postDelayed(this, 65);
        }
    };

    public VoicePanelView(@NonNull Context context, @NonNull VoiceInputHost host, @NonNull Actions actions) {
        super(context);
        this.host = host;
        this.actions = actions;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundColor(Color.TRANSPARENT);
        setPadding(dp(6), dp(4), dp(6), dp(6));
        setClickable(true); // touches on empty panel space must not reach the hidden keys

        // Status line: history, current state, cleanup mode.
        LinearLayout statusRow = row();
        history = chip("History");
        history.setContentDescription("OpenWhispr history");
        history.setOnClickListener(v -> actions.openHistory());
        statusRow.addView(history, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)));
        status = new TextView(context);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(1);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LayoutParams(0, dp(32), 1));
        cleanup = chip("Clean");
        cleanup.setOnClickListener(v -> actions.cycleCleanup());
        statusRow.addView(cleanup, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)));
        addView(statusRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));

        // Center: cancel, microphone, selection edit.
        LinearLayout center = new LinearLayout(context);
        center.setOrientation(VERTICAL);
        center.setGravity(Gravity.CENTER);
        LinearLayout micRow = row();
        micRow.setGravity(Gravity.CENTER);
        cancel = iconButton(R.drawable.ic_close, "Cancel recording");
        cancel.setOnClickListener(v -> {
            ImeDictationPipeline.INSTANCE.cancel(true);
            selectionEditMode = false;
            setStatus("Cancelled", false);
        });
        LayoutParams sideParams = new LayoutParams(dp(56), dp(48));
        sideParams.rightMargin = dp(14);
        micRow.addView(cancel, sideParams);
        micContainer = new FrameLayout(context);
        mic = iconButton(R.drawable.sym_keyboard_voice_rounded, "Start recording");
        mic.setPadding(dp(20), dp(14), dp(20), dp(14));
        mic.setOnClickListener(v -> {
            if (session == null || busy) return;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            // With text selected, speech is an instruction for the selection (as on desktop):
            // "make this all caps" edits the selection instead of replacing it.
            if (!recordingNow && selectionTarget != null && !session.getPassword()) selectionEditMode = true;
            if (selectionEditMode && selectionTarget != null)
                ImeDictationPipeline.INSTANCE.toggleSelectionEdit(host, selectionTarget);
            else
                ImeDictationPipeline.INSTANCE.toggle(host, session);
        });
        micContainer.addView(mic, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(context);
        micContainer.addView(progress, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        micRow.addView(micContainer, new LayoutParams(dp(148), dp(64)));
        selectionEdit = chip("Edit");
        selectionEdit.setContentDescription("Edit selected text with voice");
        selectionEdit.setOnClickListener(v -> {
            if (selectionEditMode) {
                selectionEditMode = false;
                setStatus(READY, false);
            } else {
                actions.editSelection();
            }
        });
        LayoutParams editParams = new LayoutParams(dp(56), dp(40));
        editParams.leftMargin = dp(14);
        micRow.addView(selectionEdit, editParams);
        center.addView(micRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        inputLevel = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        inputLevel.setMax(100);
        inputLevel.setContentDescription("Microphone input level");
        LayoutParams levelParams = new LayoutParams(dp(72), dp(4));
        levelParams.topMargin = dp(10);
        center.addView(inputLevel, levelParams);
        addView(center, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        // Keyboard-style rows: editing keys, then the same bottom row shape as typing mode.
        keyRows = new LinearLayout(context);
        keyRows.setOrientation(VERTICAL);
        LinearLayout punctuationRow = row();
        undo = iconButton(R.drawable.ic_undo_rounded, "Undo last edit");
        undo.setOnClickListener(v -> actions.undo());
        punctuationRow.addView(undo, keyParams(1.3f));
        String[] symbols = {",", ".", "?", "!"};
        punctuationKeys = new TextView[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            final String symbol = symbols[i];
            TextView key = textKey(symbol);
            key.setTextSize(20);
            key.setContentDescription("Insert " + symbol);
            key.setOnClickListener(v -> actions.insertText(symbol));
            punctuationKeys[i] = key;
            punctuationRow.addView(key, keyParams(1f));
        }
        delete = iconButton(R.drawable.sym_keyboard_delete_rounded, "Delete backward");
        delete.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                repeatingDelete = true;
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                actions.deleteBackward();
                postDelayed(repeatDelete, 400);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                repeatingDelete = false;
                v.setPressed(false);
                removeCallbacks(repeatDelete);
            }
            return true;
        });
        punctuationRow.addView(delete, keyParams(1.3f));
        keyRows.addView(punctuationRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(50)));

        LinearLayout bottomRow = row();
        abc = textKey("ABC");
        abc.setTextSize(15);
        abc.setContentDescription("Switch to typing keyboard");
        abc.setOnClickListener(v -> actions.showTyping());
        bottomRow.addView(abc, keyParams(1.5f));
        space = textKey("");
        space.setContentDescription("Insert space");
        space.setOnClickListener(v -> actions.insertText(" "));
        bottomRow.addView(space, keyParams(4f));
        enter = iconButton(R.drawable.sym_keyboard_return_rounded, "Return or next field");
        enter.setOnClickListener(v -> actions.performEnter());
        bottomRow.addView(enter, keyParams(1.5f));
        keyRows.addView(bottomRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(50)));
        addView(keyRows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        updateForOrientation(getResources().getConfiguration().orientation);
        applyColors();
        reveal(selectionEdit, false);
        reveal(undo, false);
        setStatus(READY, false);
    }

    /** Takes all colors from the active HeliBoard theme. */
    public void applyTheme(@Nullable Colors colors) {
        if (colors == null) return;
        keyColor = colors.get(ColorType.KEY_BACKGROUND);
        keyText = colors.get(ColorType.KEY_TEXT);
        functionalColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND);
        functionalText = colors.get(ColorType.FUNCTIONAL_KEY_TEXT);
        actionColor = colors.get(ColorType.ACTION_KEY_BACKGROUND);
        actionIcon = colors.get(ColorType.ACTION_KEY_ICON);
        spaceColor = colors.get(ColorType.SPACE_BAR_BACKGROUND);
        hintText = colors.get(ColorType.KEY_HINT_TEXT);
        // Same background drawable HeliBoard paints behind its keys (handles image themes too).
        setBackground(null); // HeliBoard tints a fresh white background for plain colors
        colors.setBackground(this, ColorType.MAIN_BACKGROUND);
        applyColors();
    }

    private void applyColors() {
        status.setTextColor(hintText);
        for (TextView c : new TextView[]{history, cleanup, selectionEdit}) {
            c.setTextColor(functionalText);
            c.setBackground(ripple(functionalColor, 16));
        }
        inputLevel.setProgressTintList(ColorStateList.valueOf(actionColor));
        inputLevel.setProgressBackgroundTintList(ColorStateList.valueOf(keyColor));
        mic.setBackground(ripple(actionColor, 32));
        mic.setColorFilter(actionIcon);
        progress.setIndeterminateTintList(ColorStateList.valueOf(actionIcon));
        cancel.setBackground(ripple(functionalColor, 24));
        cancel.setColorFilter(functionalText);
        for (TextView key : punctuationKeys) {
            key.setTextColor(keyText);
            key.setBackground(ripple(keyColor, 8));
        }
        for (ImageButton key : new ImageButton[]{undo, delete}) {
            key.setColorFilter(functionalText);
            key.setBackground(ripple(functionalColor, 8));
        }
        abc.setTextColor(functionalText);
        abc.setBackground(ripple(functionalColor, 8));
        space.setBackground(ripple(spaceColor, 8));
        enter.setColorFilter(actionIcon);
        enter.setBackground(ripple(actionColor, 8));
    }

    public boolean canSwitchToTyping() { return !busy && !recordingNow; }

    public void startSession(VoiceEditorSession value) {
        session = value;
        selectionTarget = null;
        selectionEditMode = false;
        reveal(selectionEdit, false);
        setStatus(value.getPassword() ? "Voice unavailable in password fields" : READY, false);
    }

    public void setSelectionTarget(VoiceSelectionTarget value) {
        boolean changed = selectionTarget == null ? value != null : !selectionTarget.equals(value);
        selectionTarget = value;
        if (value == null) {
            selectionEditMode = false;
            reveal(selectionEdit, false);
        } else {
            // The mic itself edits a selection now, so the separate Edit chip stays hidden.
            reveal(selectionEdit, false);
            if (changed && selectionEditMode && !recordingNow && !busy) {
                selectionEditMode = false;
                setStatus("Selection changed", false);
                return;
            }
        }
        // Refresh the idle hint ("Speak to edit the selection" vs "Tap to speak").
        if (changed && !recordingNow && !busy && session != null && !session.getPassword()) setStatus(READY, false);
    }

    public void startSelectionEdit() {
        if (selectionTarget == null || session == null || session.getPassword()) return;
        selectionEditMode = true;
        setStatus("Speak an edit instruction", false);
        // Edit is a one-tap affordance: entering it starts listening immediately, and the main
        // mic then finishes the instruction, matching ordinary voice capture.
        ImeDictationPipeline.INSTANCE.toggleSelectionEdit(host, selectionTarget);
    }

    public void endSelectionEdit() {
        selectionEditMode = false;
        if (session != null && !busy && !recordingNow) setStatus(READY, false);
    }

    public void setUndoAvailable(boolean available) { reveal(undo, available); }

    public void setLanguageLabel(String label) { /* English speech; translation stays in app settings. */ }

    public void setCleanupLabel(String label) {
        cleanupRaw = label.equals("Raw transcript");
        changeLabel(cleanup, cleanupRaw ? "Raw" : "Clean");
        cleanup.setContentDescription(cleanupRaw ? "Raw transcript. Tap to enable cleanup"
                : "Cleanup enabled. Tap for raw transcript");
    }

    public void updateForOrientation(int orientation) {
        boolean wide = orientation == Configuration.ORIENTATION_LANDSCAPE;
        LayoutParams lp = (LayoutParams) micContainer.getLayoutParams();
        lp.height = dp(wide ? 46 : 64);
        micContainer.setLayoutParams(lp);
        mic.setPadding(dp(20), dp(wide ? 8 : 14), dp(20), dp(wide ? 8 : 14));
    }

    public void setStatus(@NonNull String message, boolean recording) {
        busy = message.equals("Transcribing") || message.equals("Cleaning up") || message.equals("Translating")
                || message.equals("Editing") || message.equals("Stopping") || message.equals("Starting");
        recordingNow = recording;
        removeCallbacks(meter);
        reveal(inputLevel, recording);
        if (!recording) inputLevel.setProgress(0);
        else post(meter);
        changeLabel(status, recording ? "Listening… tap to finish"
                : message.equals("Ready") || message.equals("Cancelled") || message.equals(READY)
                    ? (selectionTarget != null ? "Speak to edit the selection" : READY) : message);

        boolean allowed = session != null && !session.getPassword();
        mic.setEnabled(allowed && !busy);
        mic.setAlpha(allowed ? 1f : .4f);
        int nextIcon = recording ? R.drawable.sym_keyboard_done_rounded : R.drawable.sym_keyboard_voice_rounded;
        if (micTransition != null) micTransition.cancel();
        mic.setImageResource(nextIcon);
        if (!busy && isShown() && motionEnabled() && (micResource != nextIcon || mic.getImageAlpha() < 255)) {
            micTransition = ObjectAnimator.ofInt(mic, "imageAlpha", 90, 255);
            micTransition.setDuration(130);
            micTransition.start();
        } else {
            mic.setImageAlpha(busy ? 0 : 255);
        }
        micResource = nextIcon;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        mic.setContentDescription(recording ? "Stop recording" : busy ? message
                : selectionEditMode ? "Dictate edit instruction" : "Start recording");

        boolean idle = !busy && !recording;
        for (View v : new View[]{abc, space, enter, delete, cleanup, history}) setEnabledLook(v, idle);
        for (TextView key : punctuationKeys) setEnabledLook(key, idle);
        if (undo.getVisibility() == VISIBLE) undo.setEnabled(idle);
        selectionEdit.setEnabled(idle && selectionTarget != null);
        selectionEdit.setAlpha(selectionEdit.isEnabled() ? 1f : .45f);
        reveal(cancel, recording || busy);
    }

    @Override protected void onDetachedFromWindow() {
        repeatingDelete = false;
        removeCallbacks(repeatDelete);
        removeCallbacks(meter);
        if (micTransition != null) micTransition.cancel();
        for (View v : new View[]{status, cleanup, selectionEdit, undo, cancel, inputLevel}) {
            v.animate().cancel();
            v.animate().withEndAction(null);
        }
        status.setAlpha(1f);
        cleanup.setAlpha(1f);
        super.onDetachedFromWindow();
    }

    // --- view helpers -------------------------------------------------------------------------

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LayoutParams keyParams(float weight) {
        LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        return p;
    }

    private TextView textKey(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setSoundEffectsEnabled(false);
        t.setOnTouchListener(this::tapFeedback);
        return t;
    }

    private TextView chip(String label) {
        TextView t = textKey(label);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(12), 0, dp(12), 0);
        return t;
    }

    private ImageButton iconButton(int resource, String description) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(resource);
        b.setContentDescription(description);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setSoundEffectsEnabled(false);
        b.setOnTouchListener(this::tapFeedback);
        return b;
    }

    private boolean tapFeedback(View v, MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && v.isEnabled())
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return false;
    }

    private void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : .45f);
    }

    private boolean motionEnabled() {
        return Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private void changeLabel(TextView view, String text) {
        if (text.contentEquals(view.getText())) return;
        view.animate().cancel();
        view.setText(text);
        if (isShown() && motionEnabled()) {
            view.setAlpha(.55f);
            view.animate().alpha(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            view.setAlpha(1f);
        }
    }

    /** Shows or hides a control while keeping its reserved space. */
    private void reveal(View view, boolean visible) {
        view.setEnabled(visible);
        view.animate().cancel();
        if (!isShown() || !motionEnabled()) {
            view.setAlpha(visible ? 1f : 0f);
            view.setVisibility(visible ? VISIBLE : INVISIBLE);
            return;
        }
        if (visible) {
            if (view.getVisibility() != VISIBLE) view.setAlpha(0f);
            view.setVisibility(VISIBLE);
            view.animate().alpha(1f).setDuration(140).withEndAction(null).start();
        } else {
            view.animate().alpha(0f).setDuration(100).withEndAction(() -> view.setVisibility(INVISIBLE)).start();
        }
    }

    private RippleDrawable ripple(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        int pressed = Color.argb(60, Color.red(keyText), Color.green(keyText), Color.blue(keyText));
        return new RippleDrawable(ColorStateList.valueOf(pressed), d, mask);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
