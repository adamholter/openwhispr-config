package com.voicerewriter;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EditHistoryTest {
    private static final EditHistory.EditorKey KEY = new EditHistory.EditorKey("test.editor", 0, 1);
    static class Editor {
        String text; int start, end; boolean extracted = true; boolean partial;
        final InputConnection connection = mock(InputConnection.class);
        Editor(String text) {
            this.text = text; start = end = text.length();
            when(connection.getExtractedText(any(ExtractedTextRequest.class), anyInt())).thenAnswer(i -> {
                if (!extracted) return null;
                ExtractedText e = new ExtractedText(); e.text = this.text; e.startOffset = 0;
                e.partialStartOffset = e.partialEndOffset = partial ? 0 : -1; e.selectionStart = start; e.selectionEnd = end;
                return e;
            });
            when(connection.getTextBeforeCursor(anyInt(), anyInt())).thenAnswer(i -> this.text.substring(Math.max(0, Math.min(start,end) - i.<Integer>getArgument(0)), Math.min(start,end)));
            when(connection.getTextAfterCursor(anyInt(), anyInt())).thenAnswer(i -> this.text.substring(Math.max(start,end), Math.min(this.text.length(), Math.max(start,end) + i.<Integer>getArgument(0))));
            when(connection.getSelectedText(anyInt())).thenAnswer(i -> this.text.substring(Math.min(start,end), Math.max(start,end)));
            when(connection.setSelection(anyInt(), anyInt())).thenAnswer(i -> {
                int a = i.getArgument(0), b = i.getArgument(1);
                if (a < 0 || b < 0 || a > this.text.length() || b > this.text.length()) return false;
                start = a; end = b; return true;
            });
            when(connection.commitText(any(CharSequence.class), anyInt())).thenAnswer(i -> {
                replace(i.<CharSequence>getArgument(0).toString()); return true;
            });
            when(connection.deleteSurroundingText(anyInt(), anyInt())).thenAnswer(i -> {
                int a = Math.max(0, start - i.<Integer>getArgument(0));
                int b = Math.min(this.text.length(), end + i.<Integer>getArgument(1));
                this.text = this.text.substring(0,a) + this.text.substring(b); start = end = a; return true;
            });
        }
        void select(int a, int b) { start = a; end = b; }
        void replace(String value) {
            int a = Math.min(start,end), b = Math.max(start,end);
            text = text.substring(0,a) + value + text.substring(b); start = end = a + value.length();
        }
        void delete() {
            if (start != end) replace("");
            else if (start > 0) { int count = Character.charCount(text.codePointBefore(start)); select(start-count,start); replace(""); }
        }
        EditHistory.Snapshot snapshot() { return EditHistory.snapshot(connection); }
    }
    @Test public void replacementUndoRestoresOnlyOriginalSpanThenEarlierTyping() {
        Editor e = new Editor("hello world"); EditHistory history = new EditHistory();
        EditHistory.Snapshot before = e.snapshot(); e.replace("!");
        history.recordTypingChange(KEY,before,e.snapshot(),0);
        e.select(0,5); e.replace("HELLO");
        history.recordReplacement(KEY,"hello","HELLO",e.snapshot(),1000);
        assertTrue(history.undo(e.connection,KEY)); assertEquals("hello world!",e.text);
        assertEquals(0,e.start); assertEquals(5,e.end);
        assertTrue(history.undo(e.connection,KEY)); assertEquals("hello world",e.text);
    }
    @Test public void heldDeleteIsOneActionAndWorksAfterCursorMove() {
        Editor e = new Editor("hello"); EditHistory h = new EditHistory();
        for (int i=0;i<3;i++) { EditHistory.Snapshot b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,i*65); }
        assertEquals("he",e.text); assertEquals(1,h.size(KEY));
        e.select(0,0); assertTrue(h.undo(e.connection,KEY)); assertEquals("hello",e.text);
    }
    @Test public void untrackedChangesAndAnotherSessionCannotBeOverwritten() {
        Editor e = new Editor("hello"); EditHistory h = new EditHistory();
        EditHistory.Snapshot b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        assertFalse(h.undo(e.connection,new EditHistory.EditorKey("test.editor",0,2)));
        e.replace("x"); assertFalse(h.undo(e.connection,KEY)); assertEquals("hellx",e.text);
    }
    @Test public void normalTypingReplacingSelectionAndAutocorrectAreUndoable() {
        Editor e = new Editor("teh dog"); EditHistory h = new EditHistory();
        e.select(0,3);EditHistory.Snapshot b=e.snapshot();e.replace("the");h.recordTypingChange(KEY,b,e.snapshot(),0);
        assertTrue(h.undo(e.connection,KEY));assertEquals("teh dog",e.text);
        e.select(4,7);b=e.snapshot();e.replace("cat");h.recordTypingChange(KEY,b,e.snapshot(),1);
        assertTrue(h.undo(e.connection,KEY));assertEquals("teh dog",e.text);
    }
    @Test public void emptySelectionReplacementIsUndoable() {
        Editor e = new Editor("hello world"); EditHistory h = new EditHistory();
        e.select(0,5); EditHistory.Snapshot before = e.snapshot(); e.replace("");
        h.recordTypingChange(KEY, before, e.snapshot(), 0);
        assertEquals(" world", e.text);
        assertTrue(h.undo(e.connection, KEY));
        assertEquals("hello world", e.text);
        assertEquals(0, e.start); assertEquals(5, e.end);
    }
    @Test public void emojiBackspaceAndReplacementKeepWholeCodepoints() {
        Editor e = new Editor("A🥁");EditHistory h=new EditHistory();
        EditHistory.Snapshot b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        assertEquals("A",e.text);assertTrue(h.undo(e.connection,KEY));assertEquals("A🥁",e.text);
        e.select(1,3);b=e.snapshot();e.replace("🚨");h.recordTypingChange(KEY,b,e.snapshot(),1000);
        assertTrue(h.undo(e.connection,KEY));assertEquals("A🥁",e.text);
    }
    @Test public void insertionAndMultipleStepsStayBounded() {
        Editor e=new Editor("");EditHistory h=new EditHistory();
        for(int i=0;i<30;i++){EditHistory.Snapshot b=e.snapshot();e.replace("x");h.recordTypingChange(KEY,b,e.snapshot(),i);}
        assertEquals(24,h.size(KEY));
        for(int i=0;i<24;i++)assertTrue(h.undo(e.connection,KEY));
        assertEquals("xxxxxx",e.text);assertFalse(h.undo(e.connection,KEY));
        e.replace(" dictated");h.recordInsertion(KEY," dictated",e.snapshot(),100);
        assertTrue(h.undo(e.connection,KEY));assertEquals("xxxxxx",e.text);
    }
    @Test public void longCursorWindowDeletionHasSafeFallback() {
        Editor e=new Editor("0123456789".repeat(500)+"END");e.partial=true;EditHistory h=new EditHistory();
        EditHistory.Snapshot b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        assertTrue(h.undo(e.connection,KEY));assertTrue(e.text.endsWith("END"));
    }
    @Test public void fallbackRejectsMovedCursorInRepeatedTextAndUnknownPosition() {
        Editor e=new Editor("a".repeat(10000));e.partial=true;EditHistory h=new EditHistory();
        e.select(4500,4500);EditHistory.Snapshot b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        e.select(5000,5000);assertFalse(h.undo(e.connection,KEY));
        e=new Editor("hello");e.extracted=false;h=new EditHistory();b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        assertFalse(h.canUndo(KEY));
    }
    @Test public void fallbackLongInsertionAndSelectionDeleteAreDistinctActions() {
        Editor e=new Editor("a".repeat(5000));e.partial=true;EditHistory h=new EditHistory();
        EditHistory.Snapshot b=e.snapshot();e.replace("x");h.recordTypingChange(KEY,b,e.snapshot(),0);
        assertTrue(h.undo(e.connection,KEY));assertEquals("a".repeat(5000),e.text);
        e=new Editor("abcdef");h=new EditHistory();e.select(4,6);b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,0);
        b=e.snapshot();e.delete();h.recordDeletion(KEY,b,e.snapshot(),true,65);
        assertEquals(2,h.size(KEY));assertTrue(h.undo(e.connection,KEY));assertEquals("abcd",e.text);
    }
    @Test public void passwordSessionsHaveNoHistoryKey() {
        assertNull(EditHistory.key(new VoiceEditorSession(1,"test",0,true)));
        assertNotEquals(EditHistory.key(new VoiceEditorSession(1,"test",0,false)),EditHistory.key(new VoiceEditorSession(2,"test",0,false)));
    }
}
