package com.aegisvision.medbud.perception

import java.util.ArrayDeque

/**
 * Bounded, timestamped ring buffer of [ObservationFrame]s.
 *
 * Ported from the Python `_buf: Deque[_Entry]` inside StateTracker.
 * Exposes time- and count-based queries so later phases can inspect the
 * recent past (e.g. "last 5 seconds of frames").
 *
 * Thread safety: caller must serialise access; in this app the single
 * worker loop in `PerceptionRepository` is the only writer, and UI reads
 * via a derived [PerceptionState] StateFlow — so no explicit lock here.
 */
class ObservationBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val frames = ArrayDeque<ObservationFrame>(capacity)

    /** Append a new observation, dropping the oldest if at capacity. */
    fun add(frame: ObservationFrame) {
        if (frames.size >= capacity) frames.pollFirst()
        frames.addLast(frame)
    }

    fun size(): Int = frames.size
    fun isEmpty(): Boolean = frames.isEmpty()

    /** Snapshot as a list, oldest first. */
    fun snapshot(): List<ObservationFrame> = frames.toList()

    /** Last N frames, oldest first. */
    fun lastN(n: Int): List<ObservationFrame> {
        if (n <= 0 || frames.isEmpty()) return emptyList()
        val list = frames.toList()
        val from = (list.size - n).coerceAtLeast(0)
        return list.subList(from, list.size)
    }

    /** Frames within the last [seconds] seconds of wall clock. */
    fun lastSeconds(seconds: Double): List<ObservationFrame> {
        val cutoff = nowSec() - seconds
        return frames.filter { it.timestampSec >= cutoff }
    }

    /** Drop any frame older than [maxAgeSec]. Called occasionally so stale
     *  data doesn't leak into state if the camera stalls. */
    fun dropOlderThan(maxAgeSec: Double) {
        val cutoff = nowSec() - maxAgeSec
        while (frames.isNotEmpty() && frames.peekFirst().timestampSec < cutoff) {
            frames.pollFirst()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}
