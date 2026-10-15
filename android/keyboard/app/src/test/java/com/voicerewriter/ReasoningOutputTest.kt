package com.voicerewriter
import org.junit.Assert.*
import org.junit.Test
class ReasoningOutputTest {
    @Test fun removesDraftAndBoundary() {
        assertEquals("Chief AI officer-type thing.", ReasoningOutput.clean("Chief AI officer-type thing.\n<|think_end|>\nChief AI officer-type thing."))
        assertEquals("But also follow-doxing is kinda rude.", ReasoningOutput.clean("But also follow doxing is kinda rude.\n<|think_end|>\nBut also follow-doxing is kinda rude."))
    }
    @Test fun inlineAndNestedBoundaries() {
        assertEquals("Final",ReasoningOutput.clean("<|think_start|>draft<|think_end|>Final"))
        assertEquals("Final",ReasoningOutput.clean("<think>one<think>two</think>three</think>Final"))
    }
    @Test fun preservesRepetitionAndFormatting() {
        assertEquals("Make it so. Make it so.\n\nNext.",ReasoningOutput.clean("Make it so. Make it so.\n\nNext."))
        assertEquals("{\"text\":\"New text\"}",ReasoningOutput.clean("draft<|think_end|>{\"text\":\"New text\"}"))
    }
    @Test fun fragmentedStreamAssemblesBeforeParsing() {
        assertEquals("Answer",ReasoningOutput.clean(listOf("Draft<|thi","nk_","end|",">Ans","wer").joinToString("")))
    }
    @Test fun rejectsMissingFinalAndBrokenBoundary() {
        for (text in listOf("draft<|think_end|>","<|think_start|>unfinished","draft<|think_en")) {
            try {ReasoningOutput.clean(text);fail("Accepted incomplete output")} catch (_:IllegalArgumentException) {}
        }
    }
}
