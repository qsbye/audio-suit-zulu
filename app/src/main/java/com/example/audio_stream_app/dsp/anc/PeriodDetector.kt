package com.example.audio_stream_app.dsp.anc

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 周期（基频）检测器。
 *
 * 对应需求 FR-3 的 DSP 前处理，也参考 ANC 译文「窄带系统需先获得周期/基频」一节：
 * 对录音前段做能量归一化自相关，在 30–1200Hz 对应的滞后区间找最高峰，
 * 峰值（周期性置信度）表示信号周期性的强弱；抛物线插值得到亚采样周期。
 * 非周期信号（白噪、扫频）的归一化自相关没有稳定高峰，置信度低。
 */
object PeriodDetector {

    /** 可训练判定阈值：低于该置信度提示"噪音规律性不足"，但仍允许强制训练。 */
    const val CONFIDENCE_THRESHOLD = 0.5f

    // 分析窗长 32768（44.1kHz 下约 0.74s，30Hz 时含 22 个周期，足够稳健）
    private const val WINDOW_SAMPLES = 32768

    data class Result(
        val periodSamples: Float,
        val f0Hz: Float,
        val confidence: Float
    )

    /**
     * 检测信号基频。
     *
     * @param x 归一化到 -1..1 的单声道录音
     * @return 检测结果；信号过短或能量过低时返回 null
     */
    fun detect(x: FloatArray, sampleRate: Int, minF0: Float = 30f, maxF0: Float = 1200f): Result? {
        if (x.size < sampleRate / 10) return null  // 少于 0.1s 无法分析

        val n = minOf(WINDOW_SAMPLES, x.size)
        val mean = x.average().toFloat()
        var energy = 0.0
        for (i in 0 until n) {
            val d = x[i] - mean
            energy += d.toDouble() * d
        }
        if (energy < 1e-6) return null

        val win = FloatArray(n) { x[it] - mean }

        // 用 FFT 求自相关（自相关是互相关 a=b 的特例）
        val corr = Fft.crossCorrelate(win, win)
        // 有偏归一 r[k]/r[0]：其三角衰减天然偏向真周期而非倍周期，
        // 避免无偏归一项 n/(n-k) 抬升长滞后导致的"低八度"误判
        val norm0 = corr[0].toDouble()

        val lagMin = max(1, (sampleRate / maxF0).toInt())       // 最高基频对应滞后
        val lagMax = minOf((sampleRate / minF0).roundToInt(), n - 2) // 最低基频
        if (lagMax <= lagMin + 2) return null

        var bestLag = -1
        var bestVal = 0f
        for (k in lagMin..lagMax) {
            val v = (corr[k].toDouble() / norm0).toFloat()
            if (v > bestVal) {
                bestVal = v
                bestLag = k
            }
        }
        if (bestLag < 2 || bestVal < 0.05f) return null

        // 抛物线亚采样插值
        val denom = (corr[bestLag - 1] - 2f * corr[bestLag] + corr[bestLag + 1])
        val delta = if (abs(denom) > 1e-9f) {
            0.5f * (corr[bestLag - 1] - corr[bestLag + 1]) / denom
        } else 0f
        val refinedPeriod = (bestLag + delta).coerceIn(bestLag - 1f, bestLag + 1f)

        // 以插值周期重算置信度（线性插值相关值，同为有偏归一）
        val k0 = refinedPeriod.toInt()
        val frac = refinedPeriod - k0
        val v0 = (corr[k0].toDouble() / norm0).toFloat()
        val v1 = (corr[k0 + 1].toDouble() / norm0).toFloat()
        val confidence = (v0 * (1f - frac) + v1 * frac).coerceIn(-1f, 1f)

        val f0 = sampleRate / refinedPeriod
        return Result(
            periodSamples = refinedPeriod,
            f0Hz = f0,
            confidence = confidence
        )
    }
}
