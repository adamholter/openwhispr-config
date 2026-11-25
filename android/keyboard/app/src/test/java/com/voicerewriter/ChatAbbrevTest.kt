package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as the desktop owChat.js checks. */
class ChatAbbrevTest {
    @Test fun restoresExpandedAbbreviations() {
        assertEquals("idk how it works.", ChatAbbrev.restore("IDK how it works", "I don't know how it works."))
        assertEquals("slop wording isn't that useful to me, tbh.", ChatAbbrev.restore("slop wording isn't that useful to me, TBH", "slop wording isn't that useful to me, to be honest."))
        assertEquals("tbh, I should just give you the card.", ChatAbbrev.restore("TBH I should just give you the card", "To be honest, I should just give you the card."))
        assertEquals("idk. I don't know what it is.", ChatAbbrev.restore("IDK. I don't know what it is", "IDK. I don't know what it is."))
        assertEquals("To be honest, it works.", ChatAbbrev.restore("to be honest it works", "To be honest, it works."))
        assertEquals("To be honest, I don't think it matters.", ChatAbbrev.restore("to be honest I don't think it matters", "tbh, I don't think it matters."))
        assertEquals("I think so, to be honest.", ChatAbbrev.restore("i think so to be honest", "I think so, tbh."))
    }
    @Test fun lowercasesButKeepsOlympiadAndAsap() {
        assertEquals("tbh, benchmarks are underrated.", ChatAbbrev.lower("TBH, benchmarks are underrated."))
        assertEquals("It got IMO gold and the IMO 2025 problems, imo.", ChatAbbrev.lower("It got IMO gold and the IMO 2025 problems, IMO."))
        assertEquals("Get me the link ASAP.", ChatAbbrev.lower("Get me the link ASAP."))
    }
}
