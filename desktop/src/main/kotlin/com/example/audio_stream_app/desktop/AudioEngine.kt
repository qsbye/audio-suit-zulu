package com.example.audio_stream_app.desktop

import com.example.audio_stream_app.desktop.dsp.HowlingSuppressor
import com.example.audio_stream_app.desktop.dsp.NlmsAec
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.pow

/**
 * 桌面端实时扩音引擎：麦克风采集 -> 回声消除 -> 防啸叫 -> 增益 -> 扬声器播放。
 * 使用 JDK 自带的 javax.sound.sampled，无需任何原生依赖。
 */
class AudioEngine(
    val waveform: WaveformController = WaveformController()
) {
    @Volatile
    var gainDb: Double = 0.0

    /** 回声消除（NLMS 自适应滤波）使能开关 */
    @Volatile
    var aecEnabled: Boolean = true

    /** 防啸叫（自适应陷波）使能开关 */
    @Volatile
    var howlingEnabled: Boolean = true

    /** 最近一次麦克风输入电平（0f..1f），供 UI 轮询显示 */
    @Volatile
    var level01: Float = 0f
        private set

    private val aec = NlmsAec()

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

        // 按实际打开的采样率创建防啸叫检测器，并清空历史状态
        val suppressor = HowlingSuppressor(format.sampleRate.toInt())
        aec.reset()
        suppressor.reset()

        worker = Thread({ loop(target, source, suppressor) }, "audio-passthrough").apply {
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

    private fun loop(target: TargetDataLine, source: SourceDataLine, howling: HowlingSuppressor) {
        val samples = ShortArray(CHUNK_SAMPLES)
        val processed = ShortArray(CHUNK_SAMPLES)
        val inBytes = ByteArray(CHUNK_SAMPLES * 2)
        val outBytes = ByteArray(CHUNK_SAMPLES * 2)
        var peakHold = 0f
        // DSP 使能边沿跟踪与参考信号都限定在音频线程内，无需加锁
        var aecActive = aecEnabled
        var howlingActive = howlingEnabled
        var reference = 0f

        while (running) {
            val read = target.read(inBytes, 0, inBytes.size)
            if (read <= 0) continue
            val count = read / 2
            for (i in 0 until count) {
                val lo = inBytes[i * 2].toInt() and 0xFF
                val hi = inBytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }

            if (aecEnabled != aecActive) {
                aec.reset()
                reference = 0f
                aecActive = aecEnabled
            }
            if (howlingEnabled != howlingActive) {
                howling.reset()
                howlingActive = howlingEnabled
            }
            reference = processBuffer(
                samples, processed, count, aecActive, howlingActive, reference, howling
            )

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

        // 停止后清空回声路径估计与陷波，避免下次启动出现瞬态
        aec.reset()
        howling.reset()
    }

    /**
     * 处理一帧：回声消除 → 防啸叫陷波 → 增益。
     * AEC 的参考信号是真正送往扬声器的播放信号（增益后），返回本帧最后一个参考采样。
     */
    private fun processBuffer(
        raw: ShortArray,
        out: ShortArray,
        count: Int,
        aecActive: Boolean,
        howlingActive: Boolean,
        referenceIn: Float,
        howling: HowlingSuppressor
    ): Float {
        val multiplier = 10.0.pow(gainDb / 20.0).toFloat()
        var reference = referenceIn
        for (i in 0 until count) {
            var sample = raw[i] / MAX_SAMPLE
            if (aecActive) sample = aec.processSample(sample, reference)
            if (howlingActive) sample = howling.processSample(sample)
            val played = (sample * multiplier).coerceIn(-1f, 1f)
            out[i] = (played * 32767f).toInt().toShort()
            reference = played
        }
        return reference
    }

    private companion object {
        const val CHUNK_SAMPLES = 512
        const val LINE_BUFFER_BYTES = CHUNK_SAMPLES * 2 * 4
        const val MAX_SAMPLE = 32768f
        val PREFERRED_RATES = intArrayOf(44100, 48000)
    }
}
