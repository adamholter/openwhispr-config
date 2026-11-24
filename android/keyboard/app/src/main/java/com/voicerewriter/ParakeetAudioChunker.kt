package com.voicerewriter

import kotlin.math.min

/** A contiguous half-open range of PCM samples passed to one offline decode stream. */
internal data class ParakeetSampleRange(val start: Int, val endExclusive: Int) {
    val size: Int get() = endExclusive - start
}

/** Plans bounded Parakeet inputs without dropping, duplicating, or reordering source samples. */
internal object ParakeetAudioChunker {
    const val MAX_CHUNK_SECONDS = 20
    const val SAMPLE_RATE = AudioRecorder.SAMPLE_RATE
    const val MAX_CHUNK_SAMPLES = MAX_CHUNK_SECONDS * SAMPLE_RATE

    private const val SEARCH_SECONDS = 3
    private const val SEARCH_SAMPLES = SEARCH_SECONDS * SAMPLE_RATE
    private const val MIN_TAIL_SECONDS = 1
    private const val MIN_TAIL_SAMPLES = MIN_TAIL_SECONDS * SAMPLE_RATE
    private const val CANDIDATE_STEP_SAMPLES = SAMPLE_RATE / 100 // 10 ms
    private const val ENERGY_RADIUS_SAMPLES = SAMPLE_RATE / 20 // 50 ms either side

    /**
     * Keeps short clips as one exact range. Longer clips split no later than 20 seconds,
     * choosing the quietest 100 ms window in the final three seconds where possible. The
     * search never moves a boundary earlier than 17 seconds, and reserves a one-second tail
     * when a hard split would otherwise leave a tiny final chunk.
     */
    fun plan(samples: FloatArray): List<ParakeetSampleRange> {
        if (samples.isEmpty()) return emptyList()

        val ranges = ArrayList<ParakeetSampleRange>()
        var start = 0
        while (samples.size - start > MAX_CHUNK_SAMPLES) {
            val hardEnd = start + MAX_CHUNK_SAMPLES
            val searchStart = maxOf(start + MAX_CHUNK_SAMPLES - SEARCH_SAMPLES, start + MIN_TAIL_SAMPLES)
            val latestEnd = min(hardEnd, samples.size - MIN_TAIL_SAMPLES)
            val boundary = quietestBoundary(samples, searchStart, latestEnd)
            ranges += ParakeetSampleRange(start, boundary)
            start = boundary
        }
        ranges += ParakeetSampleRange(start, samples.size)
        return ranges
    }

    private fun quietestBoundary(samples: FloatArray, first: Int, last: Int): Int {
        val step = CANDIDATE_STEP_SAMPLES
        var candidate = ((first + step - 1) / step) * step
        var bestBoundary = candidate.coerceAtMost(last)
        var bestEnergy = Double.POSITIVE_INFINITY

        while (candidate <= last) {
            val energy = localEnergy(samples, candidate)
            if (energy < bestEnergy || (energy == bestEnergy && candidate > bestBoundary)) {
                bestBoundary = candidate
                bestEnergy = energy
            }
            candidate += step
        }
        // Include the exact latest legal boundary when the search end is off the 10 ms grid.
        if (bestBoundary != last) {
            val energy = localEnergy(samples, last)
            if (energy < bestEnergy || (energy == bestEnergy && last > bestBoundary)) {
                bestBoundary = last
            }
        }
        return bestBoundary
    }

    /** Mean square energy over a 100 ms window centered at the proposed split. */
    private fun localEnergy(samples: FloatArray, boundary: Int): Double {
        val first = (boundary - ENERGY_RADIUS_SAMPLES).coerceAtLeast(0)
        val end = (boundary + ENERGY_RADIUS_SAMPLES).coerceAtMost(samples.size)
        var energy = 0.0
        for (index in first until end) {
            val value = samples[index].toDouble()
            energy += value * value
        }
        return if (end > first) energy / (end - first) else Double.POSITIVE_INFINITY
    }
}
