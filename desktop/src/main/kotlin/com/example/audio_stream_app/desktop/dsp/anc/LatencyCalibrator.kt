package com.example.audio_stream_app.desktop.dsp.anc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频回路时延标定器（需求 FR-4）。
 *
 * 播放一段已知的线性 chirp，同时用麦克风录音；将录音与原始 chirp 做
 * 归一化互相关（匹配滤波），最高峰位置即"写入扬声器 → 麦克风收听到"
 * 的总延迟（采样数）。反向波形据此提前同样的采样数生成，以抵消
 * ANC 译文所述的二次通路（secondary path）时延。
 */
object LatencyCalibrator {

    /** 匹配峰置信度阈值，低于此值视为标定失败（环境中找不到标定信号）。 */
    const val PEAK_THRESHOLD = 0.35f

    const val CHIRP_DURATION_S = 0.25
    const val CHIRP_F0 = 500.0
    const val CHIRP_F1 = 3000.0
    const val CHIRP_AMPLITUDE = 0.6f

    /**
     * @param gain 最小二乘植物增益：录音 ≈ gain·播放（扬声器→麦总传递幅度），
     *   反噪播放量按 1/gain 放大以在麦处等幅相消。
     */
    data class Result(val delaySamples: Int, val score: Float, val gain: Float)

    /** 生成标定用 chirp（首尾 5ms 淡入淡出，避免播放爆音）。 */
    fun buildSignal(sampleRate: Int): FloatArray {
        val n = (CHIRP_DURATION_S * sampleRate).toInt()
        val fade = (0.005 * sampleRate).toInt().coerceAtLeast(1)
        val tDur = n.toDouble() / sampleRate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            // 线性扫频的瞬时相位积分：2π(f0·t + (f1-f0)/(2T)·t²)
            val phase = 2.0 * PI * (CHIRP_F0 * t + (CHIRP_F1 - CHIRP_F0) / (2.0 * tDur) * t * t)
            var s = CHIRP_AMPLITUDE * sin(phase).toFloat()
            val edge = minOf(i, n - 1 - i)
            if (edge < fade) s *= edge.toFloat() / fade
            out[i] = s
        }
        return out
    }

    /**
     * 在录音中估计标定信号出现的延迟。
     *
     * @param recorded 麦克风录音（长度需大于 ref，信号前允许任意静音/噪声前缀）
     * @param ref [buildSignal] 生成的原始标定信号
     * @param maxDelaySamples 允许的最大延迟（默认 500ms）
     * @return 延迟采样数与匹配置信度；找不到显著匹配返回 null
     */
    fun estimateDelay(
        recorded: FloatArray,
        ref: FloatArray,
        maxDelaySamples: Int = recorded.size - ref.size
    ): Result? {
        val m = ref.size
        val n = recorded.size
        if (n <= m) return null
        val maxLag = minOf(maxDelaySamples, n - m).coerceAtLeast(0)

        val corr = Fft.crossCorrelate(recorded, ref)

        // 录音局部能量的前缀和，用于逐滞后做归一化
        val prefixSq = DoubleArray(n + 1)
        for (i in 0 until n) {
            prefixSq[i + 1] = prefixSq[i] + recorded[i].toDouble() * recorded[i]
        }
        var refSq = 0.0
        for (v in ref) refSq += v.toDouble() * v
        if (refSq < 1e-9) return null

        var bestLag = -1
        var bestScore = 0f
        for (lag in 0..maxLag) {
            val localSq = prefixSq[lag + m] - prefixSq[lag]
            if (localSq < 1e-9) continue
            val score = (corr[lag].toDouble() / sqrt(refSq * localSq)).toFloat()
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore < PEAK_THRESHOLD) return null
        // 最小二乘幅度：recorded[lag+j] ≈ gain·ref[j]（含外部噪声，相关峰已对齐）
        val gain = (corr[bestLag].toDouble() / refSq).toFloat().coerceIn(0.02f, 2f)
        return Result(bestLag, bestScore, gain)
    }
}
