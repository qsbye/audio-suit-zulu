package com.example.audio_stream_app.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.pow

/**
 * 桌面端实时扩音引擎：麦克风采集 -> 增益 -> 扬声器播放。
 * 使用 JDK 自带的 javax.sound.sampled，无需任何原生依赖。
 */
class AudioEngine(
    val waveform: WaveformController = WaveformController()
) {
    @Volatile
    var gainDb: Double = 0.0

    /** 最近一次麦克风输入电平（0f..1f），供 UI 轮询显示 */
    @Volatile
    var level01: Float = 0f
        private set

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var targetLine: TargetDataLine? = null
    private var sourceLine: SourceDataLine? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val format = openSupportedFormat()
        val target = AudioSystem.getLine(
            DataLine.Info(TargetDataLine::class.java, format)
        ) as TargetDataLine
        val source = AudioSystem.getLine(
            DataLine.Info(SourceDataLine::class.java, format)
        ) as SourceDataLine
        target.open(format, LINE_BUFFER_BYTES)
        source.open(format, LINE_BUFFER_BYTES)
        target.start()
        source.start()
        targetLine = target
        sourceLine = source
        running = true
        level01 = 0f

        worker = Thread({ loop(target, source) }, "audio-passthrough").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        worker?.interrupt()
        worker = null
        runCatching { targetLine?.stop() }
        runCatching { targetLine?.close() }
        runCatching { sourceLine?.stop() }
        runCatching { sourceLine?.close() }
        targetLine = null
        sourceLine = null
        waveform.clear()
        level01 = 0f
    }

    private fun openSupportedFormat(): AudioFormat {
        for (rate in PREFERRED_RATES) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (AudioSystem.isLineSupported(info)) return format
        }
        // 兜底：交由音频系统自行转换
        return AudioFormat(44100f, 16, 1, true, false)
    }

    private fun loop(target: TargetDataLine, source: SourceDataLine) {
        val samples = ShortArray(CHUNK_SAMPLES)
        val processed = ShortArray(CHUNK_SAMPLES)
        val inBytes = ByteArray(CHUNK_SAMPLES * 2)
        val outBytes = ByteArray(CHUNK_SAMPLES * 2)
        var peakHold = 0f

        while (running) {
            val read = target.read(inBytes, 0, inBytes.size)
            if (read <= 0) continue
            val count = read / 2
            for (i in 0 until count) {
                val lo = inBytes[i * 2].toInt() and 0xFF
                val hi = inBytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }

            applyGain(samples, processed, count)

            for (i in 0 until count) {
                val v = processed[i].toInt()
                outBytes[i * 2] = (v and 0xFF).toByte()
                outBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            source.write(outBytes, 0, count * 2)
            waveform.addSamples(samples, processed, count)

            var peak = 0f
            for (i in 0 until count) {
                val a = kotlin.math.abs(samples[i].toInt()) / 32768f
                if (a > peak) peak = a
            }
            if (peak > peakHold) peakHold = peak else peakHold *= 0.85f
            level01 = peakHold.coerceIn(0f, 1f)
        }
    }

    private fun applyGain(raw: ShortArray, out: ShortArray, count: Int) {
        val multiplier = 10.0.pow(gainDb / 20.0)
        for (i in 0 until count) {
            out[i] = (raw[i] * multiplier).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private companion object {
        const val CHUNK_SAMPLES = 512
        const val LINE_BUFFER_BYTES = CHUNK_SAMPLES * 2 * 4
        val PREFERRED_RATES = intArrayOf(44100, 48000)
    }
}
