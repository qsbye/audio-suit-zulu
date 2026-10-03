package com.example.audio_stream_app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 波形数据缓冲区，音频线程写入、Compose 绘制线程读取。
 */
class WaveformController {
    private val rawBuffer = ArrayDeque<Short>(CAPACITY)
    private val processedBuffer = ArrayDeque<Short>(CAPACITY)
    private var lastInvalidateAt = 0L

    var version by mutableIntStateOf(0)
        private set

    fun addSamples(raw: ShortArray, processed: ShortArray, count: Int) {
        synchronized(rawBuffer) {
            for (i in 0 until count) {
                if (rawBuffer.size >= CAPACITY) rawBuffer.removeFirst()
                rawBuffer.addLast(raw[i])
            }
        }
        synchronized(processedBuffer) {
            for (i in 0 until count) {
                if (processedBuffer.size >= CAPACITY) processedBuffer.removeFirst()
                processedBuffer.addLast(processed[i])
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastInvalidateAt >= REFRESH_INTERVAL_MS) {
            lastInvalidateAt = now
            version++
        }
    }

    fun clear() {
        synchronized(rawBuffer) { rawBuffer.clear() }
        synchronized(processedBuffer) { processedBuffer.clear() }
        version++
    }

    fun snapshot(): Pair<List<Short>, List<Short>> {
        val raw: List<Short>
        val processed: List<Short>
        synchronized(rawBuffer) { raw = rawBuffer.toList() }
        synchronized(processedBuffer) { processed = processedBuffer.toList() }
        return raw to processed
    }

    private companion object {
        const val CAPACITY = 8192
        const val REFRESH_INTERVAL_MS = 33L
    }
}

/**
 * 双路波形缓冲（消音器页）：A=麦克风原始（灰），B=反相播放波（湖蓝）。
 */
class DualWaveformController {
    private val bufferA = ArrayDeque<Short>(CAPACITY)
    private val bufferB = ArrayDeque<Short>(CAPACITY)
    private var lastInvalidateAt = 0L

    var version by mutableIntStateOf(0)
        private set

    fun addSamples(a: ShortArray, b: ShortArray, count: Int) {
        synchronized(bufferA) {
            for (i in 0 until count) {
                if (bufferA.size >= CAPACITY) bufferA.removeFirst()
                bufferA.addLast(a[i])
            }
        }
        synchronized(bufferB) {
            for (i in 0 until count) {
                if (bufferB.size >= CAPACITY) bufferB.removeFirst()
                bufferB.addLast(b[i])
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastInvalidateAt >= REFRESH_INTERVAL_MS) {
            lastInvalidateAt = now
            version++
        }
    }

    fun clear() {
        synchronized(bufferA) { bufferA.clear() }
        synchronized(bufferB) { bufferB.clear() }
        version++
    }

    fun snapshot(): Pair<List<Short>, List<Short>> {
        val a: List<Short>
        val b: List<Short>
        synchronized(bufferA) { a = bufferA.toList() }
        synchronized(bufferB) { b = bufferB.toList() }
        return a to b
    }

    private companion object {
        const val CAPACITY = 8192
        const val REFRESH_INTERVAL_MS = 33L
    }
}
