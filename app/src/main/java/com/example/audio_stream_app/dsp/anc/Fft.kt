package com.example.audio_stream_app.dsp.anc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 消音器算法包共用的原地基-2 FFT（纯 Kotlin，无第三方依赖）。
 * 与 dsp/HowlingSuppressor 中的实现同源，独立放置以便 ANC 各模块复用。
 */
internal object Fft {

    /** 原地复数基-2 FFT，长度必须为 2 的幂；inverse=true 时为逆变换（含 1/N 归一）。 */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        // 位逆序置换
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
            val sign = if (inverse) 1.0 else -1.0
            val angle = sign * 2.0 * PI / len
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
        if (inverse) {
            for (i in 0 until n) {
                re[i] = re[i] / n
                im[i] = im[i] / n
            }
        }
    }

    /**
     * 线性互相关 c[τ] = Σ_t a[t+τ]·b[t]（仅返回 τ≥0 部分，长度 a.size）。
     * 内部零填充到 ≥N+M-1 的 2 的幂，避免循环混叠。a 为较长的被搜索信号。
     */
    fun crossCorrelate(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= b.size) { "crossCorrelate 要求 a.size >= b.size" }
        var nfft = 1
        while (nfft < a.size + b.size - 1) nfft = nfft shl 1
        val ar = FloatArray(nfft)
        val ai = FloatArray(nfft)
        val br = FloatArray(nfft)
        val bi = FloatArray(nfft)
        System.arraycopy(a, 0, ar, 0, a.size)
        System.arraycopy(b, 0, br, 0, b.size)
        transform(ar, ai, false)
        transform(br, bi, false)
        // C = A · conj(B)
        val cr = FloatArray(nfft)
        val ci = FloatArray(nfft)
        for (k in 0 until nfft) {
            cr[k] = ar[k] * br[k] + ai[k] * bi[k]
            ci[k] = ai[k] * br[k] - ar[k] * bi[k]
        }
        transform(cr, ci, true)
        return FloatArray(a.size) { cr[it] }
    }
}
