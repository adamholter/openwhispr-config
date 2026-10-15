package com.voicerewriter;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, memory-only undo. Every restore verifies the editor session and its current text. */
public final class EditHistory {
    public static final int MAX_ENTRIES = 24;
    private static final int CONTEXT_LIMIT = 4096;
    private static final int MAX_TEXT = 32768;
    private static final int MAX_RETAINED_CHARS = 262144;
    private static final int MAX_EDITORS = 4;
    private static final long DELETE_GROUP_WINDOW_MILLIS = 850L;

    public static final class EditorKey {
        @NonNull public final String packageName;
        public final int fieldId;
        public final long generation;
        public EditorKey(@NonNull String packageName, int fieldId) { this(packageName, fieldId, 0); }
        public EditorKey(@NonNull String packageName, int fieldId, long generation) {
            this.packageName = packageName; this.fieldId = fieldId; this.generation = generation;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof EditorKey)) return false;
            EditorKey k = (EditorKey) other;
            return fieldId == k.fieldId && generation == k.generation && packageName.equals(k.packageName);
        }
        @Override public int hashCode() { return Objects.hash(packageName, fieldId, generation); }
    }

    public static final class Snapshot {
        @NonNull public final String before, selected, after;
        @Nullable public final String fullText;
        public final int selectionStart, selectionEnd;
        public Snapshot(@NonNull String before, @NonNull String selected, @NonNull String after) {
            this(before, selected, after, null, -1, -1);
        }
        public Snapshot(@NonNull String text, int start, int end) {
            this(text.substring(Math.max(0, Math.min(start, end) - CONTEXT_LIMIT), Math.min(start, end)),
                    text.substring(Math.min(start, end), Math.max(start, end)),
                    text.substring(Math.max(start, end), Math.min(text.length(), Math.max(start, end) + CONTEXT_LIMIT)),
                    text, start, end);
        }
        private Snapshot(String before, String selected, String after, String fullText, int start, int end) {
            this.before = before; this.selected = selected; this.after = after;
            this.fullText = fullText; selectionStart = start; selectionEnd = end;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) other;
            return before.equals(s.before) && selected.equals(s.selected) && after.equals(s.after);
        }
        @Override public int hashCode() { return Objects.hash(before, selected, after); }
        private int retainedChars() { return before.length() + selected.length() + after.length() + (fullText == null ? 0 : fullText.length()); }
    }

    private static final class Entry {
        Snapshot original, expected;
        String restoreText, removeText;
        final boolean groupable;
        long timestamp;
        Entry(Snapshot original, Snapshot expected, String restoreText, String removeText, boolean groupable, long timestamp) {
            this.original = original; this.expected = expected; this.restoreText = restoreText;
            this.removeText = removeText; this.groupable = groupable; this.timestamp = timestamp;
        }
        int retainedChars() { return (original == null ? 0 : original.retainedChars()) + expected.retainedChars() + restoreText.length() + removeText.length(); }
    }
    private final LinkedHashMap<EditorKey, ArrayDeque<Entry>> stacks =
            new LinkedHashMap<EditorKey, ArrayDeque<Entry>>(MAX_EDITORS, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<EditorKey, ArrayDeque<EditHistory.Entry>> eldest) {
                    return size() > MAX_EDITORS;
                }
            };

    @Nullable public static Snapshot snapshot(@Nullable InputConnection connection) {
        if (connection == null) return null;
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = MAX_TEXT + 1;
            ExtractedText e = connection.getExtractedText(request, 0);
            if (e != null && e.text != null && e.startOffset == 0 && e.partialStartOffset < 0
                    && e.partialEndOffset < 0 && e.text.length() <= MAX_TEXT
                    && e.selectionStart >= 0 && e.selectionEnd >= 0
                    && e.selectionStart <= e.text.length() && e.selectionEnd <= e.text.length()) {
                return new Snapshot(e.text.toString(), e.selectionStart, e.selectionEnd);
            }
            CharSequence before = connection.getTextBeforeCursor(CONTEXT_LIMIT, 0);
            CharSequence selected = connection.getSelectedText(0);
            CharSequence after = connection.getTextAfterCursor(CONTEXT_LIMIT, 0);
            if (before == null || after == null || (selected != null && selected.length() > MAX_TEXT)) return null;
            int start = e != null && e.startOffset == 0 ? e.selectionStart : -1;
            int end = e != null && e.startOffset == 0 ? e.selectionEnd : -1;
            return new Snapshot(before.toString(), selected == null ? "" : selected.toString(), after.toString(), null, start, end);
        } catch (RuntimeException ignored) { return null; }
    }
    @Nullable public static EditorKey key(@Nullable VoiceEditorSession session) {
        return session == null || session.getPassword() ? null
                : new EditorKey(session.getPackageName(), session.getFieldId(), session.getGeneration());
    }

    public synchronized void recordDeletion(@Nullable EditorKey key, @Nullable Snapshot before,
                                             @Nullable Snapshot after, boolean groupable, long timestamp) {
        if (key == null || before == null || after == null) return;
        if (before.fullText != null && after.fullText != null) {
            if (before.fullText.equals(after.fullText)) return;
            int[] change = diff(before.fullText, after.fullText);
            // Only pure adjacent deletes may merge; autocorrection is a distinct edit.
            boolean deletion = change[2] == 0 && before.selected.isEmpty();
            ArrayDeque<Entry> stack = stackFor(key); Entry last = stack.peekLast();
            if (deletion && groupable && last != null && last.groupable
                    && timestamp - last.timestamp <= DELETE_GROUP_WINDOW_MILLIS
                    && last.expected.fullText != null && last.expected.fullText.equals(before.fullText)
                    && last.expected.selectionStart == before.selectionStart
                    && last.expected.selectionEnd == before.selectionEnd) {
                last.expected = after; last.timestamp = timestamp; trim(); return;
            }
            push(key, new Entry(before, after, "", "", deletion && groupable, timestamp)); return;
        }
        if (before.selectionStart < 0 || after.selectionStart < 0) return;
        String deleted = deletedText(before, after);
        if (deleted == null || deleted.isEmpty()) return;
        Entry last = stackFor(key).peekLast();
        if (groupable && last != null && last.groupable && last.original == null
                && timestamp - last.timestamp <= DELETE_GROUP_WINDOW_MILLIS && last.expected.equals(before)) {
            last.restoreText = deleted + last.restoreText; last.expected = after; last.timestamp = timestamp; trim(); return;
        }
        push(key, new Entry(null, after, deleted, "", groupable, timestamp));
    }
    public synchronized void recordReplacement(@Nullable EditorKey key, @NonNull String original,
                                                @NonNull String replacement, @NonNull Snapshot after, long timestamp) {
        if (key == null || original.equals(replacement) || after.selectionStart < 0) return;
        Snapshot before = beforeInsertion(after, replacement, original);
        push(key, new Entry(before, after, original, replacement, false, timestamp));
    }
    public synchronized void recordInsertion(@Nullable EditorKey key, @NonNull String inserted,
                                              @NonNull Snapshot after, long timestamp) {
        if (key == null || inserted.isEmpty() || after.selectionStart < 0) return;
        Snapshot before = beforeInsertion(after, inserted, "");
        push(key, new Entry(before, after, "", inserted, false, timestamp));
    }
    private static Snapshot beforeInsertion(Snapshot after, String inserted, String original) {
        if (after.fullText == null || after.selectionStart != after.selectionEnd) return null;
        int end = after.selectionStart, start = end - inserted.length();
        if (start < 0 || !after.fullText.substring(start, end).equals(inserted)) return null;
        String text = after.fullText.substring(0, start) + original + after.fullText.substring(end);
        if (text.length() > MAX_TEXT) return null;
        return new Snapshot(text, start, start + original.length());
    }
    public synchronized void recordTypingChange(@Nullable EditorKey key, @Nullable Snapshot before,
                                                 @Nullable Snapshot after, long timestamp) {
        if (key == null || before == null || after == null) return;
        if (before.fullText != null && after.fullText != null) {
            if (!before.fullText.equals(after.fullText)) push(key, new Entry(before, after, "", "", false, timestamp));
            return;
        }
        if (before.selectionStart < 0 || after.selectionStart < 0 || !before.after.equals(after.after) || !after.selected.isEmpty()) return;
        int insertedLength = after.selectionStart - Math.min(before.selectionStart, before.selectionEnd);
        if (insertedLength > 0 && insertedLength <= after.before.length()) {
            String inserted = after.before.substring(after.before.length() - insertedLength);
            String remaining = after.before.substring(0, after.before.length() - insertedLength);
            if (before.before.endsWith(remaining)) recordReplacement(key, before.selected, inserted, after, timestamp);
        } else recordDeletion(key, before, after, false, timestamp);
    }
    public synchronized boolean canUndo(@Nullable EditorKey key) {
        ArrayDeque<Entry> stack = key == null ? null : stacks.get(key);
        return stack != null && !stack.isEmpty();
    }
    public synchronized int size(@Nullable EditorKey key) {
        ArrayDeque<Entry> stack = key == null ? null : stacks.get(key); return stack == null ? 0 : stack.size();
    }
    public synchronized void clear() { stacks.clear(); }

    /** Cursor movement is allowed, but any untracked content change prevents restoration. */
    public synchronized boolean undo(@Nullable InputConnection connection, @Nullable EditorKey key) {
        if (connection == null || key == null) return false;
        ArrayDeque<Entry> stack = stacks.get(key);
        Entry entry = stack == null ? null : stack.peekLast();
        Snapshot current = snapshot(connection);
        if (entry == null || current == null) return false;
        if (entry.original != null && entry.original.fullText != null && entry.expected.fullText != null) {
            if (current.fullText == null || !current.fullText.equals(entry.expected.fullText)) return false;
            int[] change = diff(entry.original.fullText, entry.expected.fullText);
            int start = change[0], removeLength = change[2];
            String restore = entry.original.fullText.substring(start, start + change[1]);
            connection.beginBatchEdit();
            try {
                connection.finishComposingText();
                if (!connection.setSelection(start, start + removeLength)) return false;
                if (!connection.commitText(restore, 1)) {
                    connection.setSelection(current.selectionStart, current.selectionEnd); return false;
                }
                connection.setSelection(entry.original.selectionStart, entry.original.selectionEnd);
            } finally { connection.endBatchEdit(); }
            Snapshot verified = snapshot(connection);
            if (verified == null || !entry.original.fullText.equals(verified.fullText)) return false;
            stack.removeLast(); return true;
        }
        // Editors that withhold complete extracted text get a conservative cursor-local fallback.
        if (entry.expected.selectionStart < 0 || current.selectionStart != entry.expected.selectionStart
                || current.selectionEnd != entry.expected.selectionEnd
                || !entry.expected.equals(current) || !current.selected.isEmpty()) return false;
        if (!entry.removeText.isEmpty()) {
            CharSequence before = connection.getTextBeforeCursor(entry.removeText.length(), 0);
            if (before == null || !before.toString().endsWith(entry.removeText)) return false;
        }
        boolean committed;
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            int start = current.selectionStart - entry.removeText.length();
            if (start < 0 || !connection.setSelection(start, current.selectionEnd)) return false;
            committed = connection.commitText(entry.restoreText, 1);
            if (!committed) connection.setSelection(current.selectionStart, current.selectionEnd);
        } finally { connection.endBatchEdit(); }
        if (committed) stack.removeLast();
        return committed;
    }
    private ArrayDeque<Entry> stackFor(EditorKey key) {
        ArrayDeque<Entry> stack = stacks.get(key);
        if (stack == null) { stack = new ArrayDeque<>(); stacks.put(key, stack); }
        return stack;
    }
    private void push(EditorKey key, Entry entry) {
        ArrayDeque<Entry> stack = stackFor(key); stack.addLast(entry);
        while (stack.size() > MAX_ENTRIES) stack.removeFirst(); trim();
    }
    private void trim() {
        int retained = 0;
        for (ArrayDeque<Entry> stack : stacks.values()) for (Entry entry : stack) retained += entry.retainedChars();
        while (retained > MAX_RETAINED_CHARS) {
            boolean removed = false;
            for (ArrayDeque<Entry> stack : stacks.values()) {
                Entry entry = stack.pollFirst();
                if (entry != null) { retained -= entry.retainedChars(); removed = true; break; }
            }
            if (!removed) break;
        }
    }
    /** {first changed UTF-16 offset, original length, replacement length}. */
    private static int[] diff(String original, String replacement) {
        int start = 0, min = Math.min(original.length(), replacement.length());
        while (start < min && original.charAt(start) == replacement.charAt(start)) start++;
        if (start > 0 && start < original.length() && Character.isHighSurrogate(original.charAt(start - 1))) start--;
        int suffix = 0;
        while (suffix < original.length() - start && suffix < replacement.length() - start
                && original.charAt(original.length() - 1 - suffix) == replacement.charAt(replacement.length() - 1 - suffix)) suffix++;
        if (suffix > 0 && Character.isLowSurrogate(original.charAt(original.length() - suffix))) suffix--;
        return new int[]{start, original.length() - start - suffix, replacement.length() - start - suffix};
    }
    @Nullable private static String deletedText(Snapshot before, Snapshot after) {
        if (!before.selected.isEmpty() && after.selected.isEmpty() && before.before.equals(after.before) && before.after.equals(after.after)) return before.selected;
        if (!before.selected.isEmpty() || !after.selected.isEmpty() || !before.after.equals(after.after)) return null;
        if (before.before.length() > after.before.length() && before.before.startsWith(after.before)) return before.before.substring(after.before.length());
        int max = Math.min(64, Math.min(before.before.length(), after.before.length()) - 1);
        for (int count = 1; count <= max; count++) {
            int shared = before.before.length() - count;
            if (shared > 0 && before.before.substring(0, shared).equals(after.before.substring(count))) return before.before.substring(shared);
        }
        return null;
    }
}
