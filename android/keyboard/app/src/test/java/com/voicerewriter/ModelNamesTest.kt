package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are the desktop fixer (speed-patch/owModelNames.js) outputs. */
class ModelNamesTest {
    @Test
    fun matchesDesktopFixer() {
        assertEquals("gpt six point one Sol is better", ModelNames.fix("gpt six point one soul is better"))
        assertEquals("Sonnet 5.5 and GPT 6.1 Sol are highlighted", ModelNames.fix("Sonnet 5.5 and GPT 6.1 Soul are highlighted"))
        assertEquals("Astra, Sol, Terra, Luna and X", ModelNames.fix("Astra, Soul, Terra, Luna and X"))
        assertEquals("Opus being on top, Sol having a couple points", ModelNames.fix("Opus being on top, Seoul having a couple points"))
        assertEquals("worse than Luna, let alone Sol", ModelNames.fix("worse than Luna, let alone Soul"))
        assertEquals("tell Sol to go", ModelNames.fix("tell Soul to go"))
        assertEquals("Claude has a soul document.", ModelNames.fix("Claude has a soul document."))
        assertEquals("Soul food is great.", ModelNames.fix("Soul food is great."))
        assertEquals("I love Seoul in spring.", ModelNames.fix("I love Seoul in spring."))
        assertEquals("she put her whole soul into it", ModelNames.fix("she put her whole soul into it"))
        assertEquals("GPT-6.1 Terra. Depending", ModelNames.fix("GPT-6.1 tera. Depending"))
        assertEquals("the new codecs for streaming video", ModelNames.fix("the new codecs for streaming video"))
        assertEquals("using Codex and T3 code", ModelNames.fix("using codecs and T3 code"))
        assertEquals("Claude models and my Claude limit", ModelNames.fix("clawed models and my clawed limit"))
        assertEquals("the cat clawed the couch", ModelNames.fix("the cat clawed the couch"))
        assertEquals("Claude Haiku five point", ModelNames.fix("Clog Haiku five point"))
        assertEquals("a Claude limit", ModelNames.fix("a claw limit"))
        assertEquals("Claude code works", ModelNames.fix("cloud code works"))
        assertEquals("cloud storage", ModelNames.fix("cloud storage"))
        assertEquals("Kimi K3.1 is out", ModelNames.fix("Kimmy K3.1 is out"))
        assertEquals("my friend kimmy", ModelNames.fix("my friend kimmy"))
        assertEquals("Grok 4.7 is a", ModelNames.fix("Grock 4.7 is a"))
        assertEquals("Grok 4 costing", ModelNames.fix("Grog 4 costing"))
        assertEquals("DeepSeek V4 and deep seeking", ModelNames.fix("Deep Seek V4 and deep seeking"))
        assertEquals("GPT six Luna and Terra", ModelNames.fix("GPT six llama and Terra"))
        assertEquals("you can use 6.1 Sol or Astra", ModelNames.fix("you can use 6.1 soul or Astra"))
        assertEquals("Sol on extra high", ModelNames.fix("soul on extra high"))
        assertEquals("Six Sol is outdated", ModelNames.fix("Six Soul is outdated"))
        assertEquals("GBT6 Sol decided", ModelNames.fix("GBT6 Soul decided"))
    }
}
