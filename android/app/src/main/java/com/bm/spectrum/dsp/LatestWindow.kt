package com.bm.spectrum.dsp

/** Keeps only the newest complete RTA window. Analysis never queues old windows. */
class LatestWindow(private val size: Int, private val hop: Int) {
    init { require(size > 1 && hop in 1..size) }
    private val ring = DoubleArray(size)
    private var write = 0
    private var captured = 0L

    data class Snapshot(val pcm: DoubleArray, val captured: Long)

    @Synchronized fun add(block: FloatArray, count: Int) {
        require(count in 0..block.size)
        for (i in 0 until count) {
            ring[write] = block[i].toDouble()
            write = (write + 1) % size
        }
        captured += count
    }

    @Synchronized fun newestAfter(previous: Long): Snapshot? {
        if (captured < size || (previous != 0L && captured - previous < hop)) return null
        val pcm = DoubleArray(size)
        val tail = size - write
        ring.copyInto(pcm, 0, write, size)
        ring.copyInto(pcm, tail, 0, write)
        return Snapshot(pcm, captured)
    }
}
