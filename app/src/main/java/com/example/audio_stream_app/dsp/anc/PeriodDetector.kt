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
        val confidence0 = (v0 * (1f - frac) + v1 * frac).coerceIn(-1f, 1f)

        // 长滞后精修：短窗抛物线在有噪时仍有 ~0.1 采样级误差，按数百个
        // 周期折叠模板时会累积成不可忽视的相位漂移（高次谐波被平均模糊）。
        // 在整段录音的 k·P 附近（k 个周期）再做一次归一化互相关峰定位，
        // 亚采样误差随 k 缩小一个多数量级。
        val (finePeriod, fineConf) = refineLongLag(x, refinedPeriod)

        return Result(
            periodSamples = finePeriod,
            f0Hz = sampleRate / finePeriod,
            confidence = if (fineConf > 0f) fineConf else confidence0
        )
    }

    /**
     * 在整段 [x] 上、约 k 个周期滞后处精修周期估计。
     * 返回（精修周期，长滞后置信度）；信号不足以跨 ≥2 个周期时原样返回。
     */
    private fun refineLongLag(x: FloatArray, coarsePeriod: Float): Pair<Float, Float> {
        val n = x.size
        val k = kotlin.math.floor((n / 2.0) / coarsePeriod).toInt()
        if (k < 2) return coarsePeriod to -1f

        val mean = x.average()
        val w = DoubleArray(n) { x[it] - mean }

        // 平方和前缀，便于 O(1) 求任意重叠段能量
        val sqPref = DoubleArray(n + 1)
        for (i in 0 until n) sqPref[i + 1] = sqPref[i] + w[i] * w[i]

        fun normCorr(lag: Int): Double {
            if (lag < 1 || lag >= n) return -1.0
            val len = n - lag
            var sum = 0.0
            var i = 0
            // 每 4 个一组展开，降低热循环开销
            val limit = len - 3
            while (i < limit) {
                sum += w[i] * w[i + lag] + w[i + 1] * w[i + 1 + lag] +
                    w[i + 2] * w[i + 2 + lag] + w[i + 3] * w[i + 3 + lag]
                i += 4
            }
            while (i < len) { sum += w[i] * w[i + lag]; i++ }
            val e0 = sqPref[len]
            val e1 = sqPref[n] - sqPref[lag]
            return sum / sqrt(e0 * e1.coerceAtLeast(1e-12))
        }

        // k·P 粗位置 ±0.5 个周期（再多 2 采样余量）
        val center = kotlin.math.round(k * coarsePeriod).toInt()
        val radius = kotlin.math.ceil(k * 0.5).toInt() + 2
        val lo = max(1, center - radius)
        val hi = minOf(center + radius, n - 2)
        if (hi <= lo + 2) return coarsePeriod to -1f

        var bestL = center
        var bestV = -2.0
        for (lag in lo..hi) {
            val v = normCorr(lag)
            if (v > bestV) { bestV = v; bestL = lag }
        }
        if (bestV < 0.1) return coarsePeriod to -1f

        val vm = normCorr(bestL - 1)
        val vp = normCorr(bestL + 1)
        val denom = vm - 2.0 * bestV + vp
        val delta = if (abs(denom) > 1e-12) 0.5 * (vm - vp) / denom else 0.0
        val peakLag = bestL + delta.coerceIn(-1.0, 1.0)
        val fine = (peakLag / k).toFloat()
        // 只允许在粗估 ±0.6 采样内修正，异常峰不采信
        if (abs(fine - coarsePeriod) > 0.6f) return coarsePeriod to -1f
        return fine to bestV.toFloat()
    }
}
