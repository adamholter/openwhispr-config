package com.voicerewriter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetAudioChunkerTest {
    @Test
    fun shortClipStaysOneExactRange() {
        val samples = floatArrayOf(0.0f, -0.25f, 0.5f, 0.125f)

        val ranges = ParakeetAudioChunker.plan(samples)

        assertEquals(listOf(ParakeetSampleRange(0, samples.size)), ranges)
        assertArrayEquals(samples, samples.copyOfRange(ranges.single().start, ranges.single().endExclusive), 0f)
    }

    @Test
    fun longClipUsesQuietPauseAndKeepsEverySampleInOrder() {
        val samples = FloatArray(35 * ParakeetAudioChunker.SAMPLE_RATE + 137) { (it % 997) / 997f }
        val pauseStart = 19 * ParakeetAudioChunker.SAMPLE_RATE
        val pauseEnd = pauseStart + 6 * ParakeetAudioChunker.SAMPLE_RATE / 10
        samples.fill(0f, pauseStart, pauseEnd)

        val ranges = ParakeetAudioChunker.plan(samples)
        val firstBoundary = ranges.first().endExclusive
        val reconstructed = FloatArray(samples.size)
        var expectedStart = 0

        for (range in ranges) {
            assertEquals(expectedStart, range.start)
            assertTrue(range.size in 1..ParakeetAudioChunker.MAX_CHUNK_SAMPLES)
            samples.copyOfRange(range.start, range.endExclusive).copyInto(reconstructed, range.start)
            expectedStart = range.endExclusive
        }

        assertTrue(firstBoundary in (pauseStart + ParakeetAudioChunker.SAMPLE_RATE / 20)..(pauseEnd - ParakeetAudioChunker.SAMPLE_RATE / 20))
        assertEquals(samples.size, expectedStart)
        assertArrayEquals(samples, reconstructed, 0f)
    }

    @Test
    fun thirtyMinutePlanStaysBoundedAndCoversTheWholeRecording() {
        val samples = FloatArray(30 * 60 * ParakeetAudioChunker.SAMPLE_RATE)

        val ranges = ParakeetAudioChunker.plan(samples)

        assertTrue(ranges.size in 90..106)
        var expectedStart = 0
        for (index in ranges.indices) {
            val range = ranges[index]
            assertEquals(expectedStart, range.start)
            assertTrue(range.size in 1..ParakeetAudioChunker.MAX_CHUNK_SAMPLES)
            if (index < ranges.lastIndex) {
                assertTrue(range.size >= ParakeetAudioChunker.MAX_CHUNK_SAMPLES - 3 * ParakeetAudioChunker.SAMPLE_RATE)
            }
            expectedStart = range.endExclusive
        }
        assertEquals(samples.size, expectedStart)
    }

    @Test
    fun tinyFinalRemainderGetsAOneSecondChunk() {
        val samples = FloatArray(ParakeetAudioChunker.MAX_CHUNK_SAMPLES + 100) { 0.2f }

        val ranges = ParakeetAudioChunker.plan(samples)

        assertEquals(2, ranges.size)
        assertEquals(ParakeetAudioChunker.SAMPLE_RATE, ranges.last().size)
        assertEquals(samples.size, ranges.last().endExclusive)
    }

    @Test
    fun emptyClipProducesNoRanges() {
        assertTrue(ParakeetAudioChunker.plan(floatArrayOf()).isEmpty())
    }
}
