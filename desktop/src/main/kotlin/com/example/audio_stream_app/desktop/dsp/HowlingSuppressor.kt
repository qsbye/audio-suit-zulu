package com.example.audio_stream_app.desktop.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 防啸叫处理器（Howling Suppression）。
 *
 * 参考 docs/aec-in-communication-apps-reddit.md 中的反馈检测方法：
 * 啸叫是声反馈闭环造成的——某一频率的能量随时间指数增长。
 * 检测到某频点窄带能量连续多帧正增长、且明显高出邻域与本底时即"锁定"，
 * 在该频率自动放置一个 IIR 陷波（notch）打断反馈环；
 * 锁定后只要该频点仍保持强能量就持续抑制（覆盖啸叫饱和平台期），
 * 能量消失一段时间后陷波自动淡出释放。
 *
 * 处理流程：信号先过陷波组，再以陷波后的信号做频谱分析——
 * 反馈环被陷波打断后该频点能量下降，陷波即可自动解除。
 */
class HowlingSuppressor(
    private val sampleRate: Int = 44100,
    private val fftSize: Int = FFT_SIZE
) {
    private val binCount = fftSize / 2

    private val window = FloatArray(fftSize) { i ->
        0.5f - 0.5f * cos(2.0 * PI * i / (fftSize - 1)).toFloat()
    }
    private val frame = FloatArray(fftSize)
    private var framePos = 0

    private val fftRe = FloatArray(fftSize)
    private val fftIm = FloatArray(fftSize)
    private val magnitude = FloatArray(binCount)
    private val prevMagnitude = FloatArray(binCount)
    private val growFrames = IntArray(binCount)
    private val locked = BooleanArray(binCount)
    private val releaseCountdown = IntArray(binCount)

    private val notches = Array(MAX_NOTCHES) { Notch() }

    fun reset() {
        frame.fill(0f)
        framePos = 0
        fftRe.fill(0f)
        fftIm.fill(0f)
        magnitude.fill(0f)
        prevMagnitude.fill(0f)
        growFrames.fill(0)
        locked.fill(false)
        releaseCountdown.fill(0)
        notches.forEach { it.deactivate() }
    }

    /** 逐样本处理，内部自动攒帧检测并更新陷波。 */
    fun processSample(sample: Float): Float {
        var out = sample
        for (n in notches) {
            if (n.active && n.depth > DEPTH_EPS) {
                val y = n.b0 * out + n.b1 * n.x1 + n.b2 * n.x2 - n.a1 * n.y1 - n.a2 * n.y2
                n.x2 = n.x1
                n.x1 = out
                n.y2 = n.y1
                n.y1 = y
                // 深度混合，避免陷波切入/切出时产生咔哒声
                out += (y - out) * n.depth
            }
        }

        frame[framePos++] = out
        if (framePos == fftSize) {
            framePos = 0
            analyzeFrame()
        }
        return out
    }

    private fun analyzeFrame() {
        for (i in 0 until fftSize) {
            fftRe[i] = frame[i] * window[i]
            fftIm[i] = 0f
        }
        fft(fftRe, fftIm)

        var magnitudeSum = 0f
        var magnitudeMax = 0f
        for (k in 1 until binCount) {
            val m = sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k])
            magnitude[k] = m
            magnitudeSum += m
            if (m > magnitudeMax) magnitudeMax = m
        }

        val absoluteFloor = max(ABS_FLOOR, magnitudeSum / binCount * FLOOR_RATIO)
        val maxK = maxBin()
        val howling = BooleanArray(binCount)

        for (k in MIN_BIN..maxK) {
            val m = magnitude[k]
            val neighbor = (
                magnitude[k - 2] + magnitude[k - 1] +
                    magnitude[k + 1] + magnitude[k + 2]
                ) / 4f + 1e-6f
            val strong = m >= magnitudeMax * PEAK_RATIO &&
                m > neighbor * PROMINENCE_RATIO &&
                m > absoluteFloor
            val growing = m > prevMagnitude[k] * GROW_RATIO

            if (strong && growing) {
                growFrames[k]++
            } else if (!strong) {
                // 平台期缓慢衰减计数，能量消失则快速归零
                growFrames[k] = (growFrames[k] - 2).coerceAtLeast(0)
            }

            if (growFrames[k] >= GROW_FRAME_COUNT) locked[k] = true

            if (locked[k]) {
                if (strong) {
                    releaseCountdown[k] = RELEASE_FRAME_COUNT
                    howling[k] = true
                } else {
                    releaseCountdown[k]--
                    if (releaseCountdown[k] <= 0) {
                        locked[k] = false
                        growFrames[k] = 0
                    }
                }
            }
        }

        magnitude.copyInto(prevMagnitude)
        assignNotches(howling, maxK)
    }

    private fun assignNotches(howling: BooleanArray, maxK: Int) {
        // 已有陷波：邻近频点仍在啸叫则保持，否则淡出
        for (n in notches) {
            if (!n.active) continue
            val lo = (n.bin - 2).coerceAtLeast(MIN_BIN)
            val hi = (n.bin + 2).coerceAtMost(maxK)
            val stillHowling = (lo..hi).any { howling[it] }
            n.target = if (stillHowling) 1f else 0f
        }

        // 新啸叫频点：分配空闲槽位（必要时占用最浅的陷波）
        for (k in MIN_BIN..maxK) {
            if (!howling[k]) continue
            if (notches.any { it.active && abs(it.bin - k) <= 2 }) continue
            val slot = notches.minByOrNull { if (it.active) it.depth else -1f } ?: continue
            if (!slot.active || slot.depth < 0.5f) {
                val freqHz = sampleRate.toFloat() * k / fftSize
                slot.activate(k, freqHz, sampleRate)
            }
        }

        // 深度平滑与彻底释放
        for (n in notches) {
            n.depth += (n.target - n.depth) * DEPTH_SMOOTH
            if (!n.active && n.target == 0f && n.depth <= DEPTH_EPS) n.deactivate()
        }
    }

    private fun maxBin() = minOf(binCount - 3, MAX_FREQ * fftSize / sampleRate)

    private class Notch {
        var active = false
        var bin = -1
        var depth = 0f
        var target = 0f
        var b0 = 1f
        var b1 = 0f
        var b2 = 0f
        var a1 = 0f
        var a2 = 0f
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f

        /**
         * H(z) = (1 − 2cosω z⁻¹ + z⁻²) / (1 − 2r·cosω z⁻¹ + r²z⁻²)
         * r 越接近 1，陷波越窄越深。
         */
        fun activate(binIndex: Int, freqHz: Float, sampleRate: Int) {
            val omega = 2.0 * PI * freqHz / sampleRate
            val c = cos(omega).toFloat()
            active = true
            bin = binIndex
            b0 = 1f
            b1 = -2f * c
            b2 = 1f
            a1 = -2f * NOTCH_R * c
            a2 = NOTCH_R * NOTCH_R
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
            if (depth <= DEPTH_EPS) depth = 0.05f
            target = 1f
        }

        fun deactivate() {
            active = false
            bin = -1
            depth = 0f
            target = 0f
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }
    }

    /** 原地基-2 迭代 FFT */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wR0 = cos(angle)
            val wI0 = sin(angle)
            val half = len / 2
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until half) {
                    val idx1 = i + k
                    val idx2 = idx1 + half
                    val uR = re[idx1]
                    val uI = im[idx1]
                    val vR = (re[idx2] * wR - im[idx2] * wI).toFloat()
                    val vI = (re[idx2] * wI + im[idx2] * wR).toFloat()
                    re[idx1] = uR + vR
                    im[idx1] = uI + vI
                    re[idx2] = uR - vR
                    im[idx2] = uI - vI
                    val newWR = wR * wR0 - wI * wI0
                    wI = wR * wI0 + wI * wR0
                    wR = newWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        const val FFT_SIZE = 256
        const val MAX_NOTCHES = 6
        const val MIN_BIN = 6               // 约 1kHz 起步
        const val MAX_FREQ = 12000          // 手机扬声器有效上限附近
        const val GROW_FRAME_COUNT = 6      // 连续 ~35ms 正增长即锁定
        const val RELEASE_FRAME_COUNT = 90  // 约 0.5s 无强能量后释放
        const val GROW_RATIO = 1.12f
        const val PEAK_RATIO = 0.5f
        const val PROMINENCE_RATIO = 3.5f   // 峰值需高出邻域
        const val FLOOR_RATIO = 5f
        const val ABS_FLOOR = 0.02f
        const val NOTCH_R = 0.985f
        const val DEPTH_SMOOTH = 0.35f
        const val DEPTH_EPS = 0.01f
    }
}
