package com.example.audio_stream_app.dsp.anc

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 波形拟合小网络（纯 Kotlin MLP，无任何第三方 NN 依赖）。
 *
 * 设计参考 docs/rnnoise-learning-noise-suppression.md 的混合思路：
 * 确定性的 Fourier 相位特征由信号处理给出，网络只学"一个周期内波形形状"
 * 这一件小事，因此网络可以非常小（约 1.7k 参数）并在手机上快速训练。
 *
 * 输入：相位 φ∈[0,1) 的 Fourier 特征 sin/cos(2πkφ), k=1..K；
 * 结构：Dense(32,tanh) → Dense(32,tanh) → Dense(1,linear)；
 * 输出：单位 RMS 归一化后的该相位噪声波形值（真实幅度由外部 RMS 跟踪还原）。
 *
 * 周期信号的未来相位已知，实时消音时直接对 φ(n+lead) 推理即可"预测未来"，
 * 这是本方案能提前播放反向波形、绕开音频回路时延的关键。
 */
class WaveformMlp(
    val harmonics: Int,
    random: Random = Random(0x5A17C)
) {
    private val inputSize = harmonics * 2
    private val h = HIDDEN

    // 权一维展开：w1[in*h 布局]、w2[h*h]、w3[h]
    private val w1 = FloatArray(inputSize * h)
    private val b1 = FloatArray(h)
    private val w2 = FloatArray(h * h)
    private val b2 = FloatArray(h)
    private val w3 = FloatArray(h)
    private var b3 = 0f

    // Adam 状态（与参数一一对应）
    private val pm = FloatArray(w1.size + b1.size + w2.size + b2.size + w3.size + 1)
    private val pv = FloatArray(pm.size)

    // 单样本推理工作区（调用方保证同一时刻单线程使用）
    private val feat = FloatArray(inputSize)
    private val a1 = FloatArray(h)
    private val a2 = FloatArray(h)

    val parameterCount: Int get() = pm.size

    /** 导出全部参数（顺序 w1,b1,w2,b2,w3,b3），供训练器保存最优权重。 */
    fun exportParams(): FloatArray {
        val out = FloatArray(parameterCount)
        var p = 0
        w1.copyInto(out, p); p += w1.size
        b1.copyInto(out, p); p += b1.size
        w2.copyInto(out, p); p += w2.size
        b2.copyInto(out, p); p += b2.size
        w3.copyInto(out, p); p += w3.size
        out[p] = b3
        return out
    }

    /** 恢复 [exportParams] 导出的参数。 */
    fun importParams(params: FloatArray) {
        require(params.size == parameterCount)
        var p = 0
        params.copyInto(w1, 0, p, p + w1.size); p += w1.size
        params.copyInto(b1, 0, p, p + b1.size); p += b1.size
        params.copyInto(w2, 0, p, p + w2.size); p += w2.size
        params.copyInto(b2, 0, p, p + b2.size); p += b2.size
        params.copyInto(w3, 0, p, p + w3.size); p += w3.size
        b3 = params[p]
    }

    init {
        // He 初始化
        val g1 = random
        for (i in w1.indices) w1[i] = g1.nextFloat().let { (it - 0.5f) * 2f } * sqrt(2f / inputSize)
        for (i in w2.indices) w2[i] = g1.nextFloat().let { (it - 0.5f) * 2f } * sqrt(2f / h)
        for (i in w3.indices) w3[i] = g1.nextFloat().let { (it - 0.5f) * 2f } * sqrt(2f / h)
    }

    private fun phaseFeatures(phase: Float): FloatArray {
        val base = (phase - kotlin.math.floor(phase)) * 2.0 * Math.PI
        for (k in 1..harmonics) {
            val ang = base * k
            feat[(k - 1) * 2] = kotlin.math.sin(ang).toFloat()
            feat[(k - 1) * 2 + 1] = cos(ang).toFloat()
        }
        return feat
    }

    /** 对给定相位推理波形值（无堆分配，供音频线程逐采样调用）。 */
    fun predict(phase: Float): Float {
        val x = phaseFeatures(phase)
        for (j in 0 until h) {
            var z = b1[j]
            val off = j * inputSize
            for (i in 0 until inputSize) z += w1[off + i] * x[i]
            a1[j] = tanh(z)
        }
        for (j in 0 until h) {
            var z = b2[j]
            val off = j * h
            for (i in 0 until h) z += w2[off + i] * a1[i]
            a2[j] = tanh(z)
        }
        var y = b3
        for (j in 0 until h) y += w3[j] * a2[j]
        return y
    }

    /**
     * 一个 mini-batch 的前向+反向+Adam 更新，返回该批 MSE。
     * @param phases 批内各样本相位
     * @param targets 目标波形（单位 RMS）
     * @param start 批起始下标
     * @param count 批大小
     */
    fun trainBatch(
        phases: FloatArray,
        targets: FloatArray,
        start: Int,
        count: Int,
        lr: Float,
        timeStep: Long
    ): Float {
        val b = count
        val xBuf = Array(b) { FloatArray(inputSize) }
        val z1 = Array(b) { FloatArray(h) }
        val aa1 = Array(b) { FloatArray(h) }
        val z2 = Array(b) { FloatArray(h) }
        val aa2 = Array(b) { FloatArray(h) }
        val yy = FloatArray(b)

        var se = 0.0
        for (s in 0 until b) {
            val x = phaseFeatures(phases[start + s])
            xBuf[s] = x
            for (j in 0 until h) {
                var z = b1[j]
                val off = j * inputSize
                for (i in 0 until inputSize) z += w1[off + i] * x[i]
                z1[s][j] = z
                aa1[s][j] = tanh(z)
            }
            for (j in 0 until h) {
                var z = b2[j]
                val off = j * h
                for (i in 0 until h) z += w2[off + i] * aa1[s][i]
                z2[s][j] = z
                aa2[s][j] = tanh(z)
            }
            var y = b3
            for (j in 0 until h) y += w3[j] * aa2[s][j]
            yy[s] = y
            val e = y - targets[start + s]
            se += e.toDouble() * e
        }

        // 梯度累积
        val gW1 = FloatArray(w1.size)
        val gB1 = FloatArray(h)
        val gW2 = FloatArray(w2.size)
        val gB2 = FloatArray(h)
        val gW3 = FloatArray(h)
        var gB3 = 0f

        for (s in 0 until b) {
            val d3 = (yy[s] - targets[start + s]) / b
            for (j in 0 until h) gW3[j] += d3 * aa2[s][j]
            gB3 += d3

            val d2 = FloatArray(h)
            for (j in 0 until h) {
                d2[j] = (w3[j] * d3) * (1f - aa2[s][j] * aa2[s][j])
                gB2[j] += d2[j]
            }
            for (j in 0 until h) {
                val off = j * h
                for (i in 0 until h) gW2[off + i] += d2[j] * aa1[s][i]
            }
            val d1 = FloatArray(h)
            for (i in 0 until h) {
                var acc = 0f
                for (j in 0 until h) acc += w2[j * h + i] * d2[j]
                d1[i] = acc * (1f - aa1[s][i] * aa1[s][i])
                gB1[i] += d1[i]
            }
            for (j in 0 until h) {
                val off = j * inputSize
                for (i in 0 until inputSize) gW1[off + i] += d1[j] * xBuf[s][i]
            }
        }

        // 合并为参数序并做 Adam 更新
        var p = 0
        p = adamStep(w1, gW1, p, lr, timeStep)
        p = adamStep(b1, gB1, p, lr, timeStep)
        p = adamStep(w2, gW2, p, lr, timeStep)
        p = adamStep(b2, gB2, p, lr, timeStep)
        p = adamStep(w3, gW3, p, lr, timeStep)
        b3 = adamScalar(b3, gB3, p, lr, timeStep)
        return (se / b).toFloat()
    }

    private fun adamStep(
        w: FloatArray,
        g: FloatArray,
        offset: Int,
        lr: Float,
        t: Long
    ): Int {
        for (i in w.indices) {
            val gi = g[i]
            val mi = B1 * pm[offset + i] + (1f - B1) * gi
            val vi = B2 * pv[offset + i] + (1f - B2) * gi * gi
            pm[offset + i] = mi
            pv[offset + i] = vi
            val mHat = mi / (1f - powB1(t))
            val vHat = vi / (1f - powB2(t))
            w[i] -= lr * mHat / (sqrt(vHat) + EPS)
        }
        return offset + w.size
    }

    private fun adamScalar(w: Float, g: Float, offset: Int, lr: Float, t: Long): Float {
        val mi = B1 * pm[offset] + (1f - B1) * g
        val vi = B2 * pv[offset] + (1f - B2) * g * g
        pm[offset] = mi
        pv[offset] = vi
        val mHat = mi / (1f - powB1(t))
        val vHat = vi / (1f - powB2(t))
        return w - lr * mHat / (sqrt(vHat) + EPS)
    }

    private fun tanh(x: Float): Float {
        if (x > 4.5f) return 1f
        if (x < -4.5f) return -1f
        val e2 = exp(2f * x)
        return ((e2 - 1f) / (e2 + 1f))
    }

    private fun powB1(t: Long) = powTable[((t - 1).toInt()).coerceIn(0, powTable.size - 1)]
    private fun powB2(t: Long) = powTable2[((t - 1).toInt()).coerceIn(0, powTable2.size - 1)]

    companion object {
        const val HIDDEN = 32
        private const val B1 = 0.9f
        private const val B2 = 0.999f
        private const val EPS = 1e-8f
        // β^t 查表，避免每步 pow；最多支持 100000 步
        private val powTable = FloatArray(100000) { Math.pow(B1.toDouble(), it + 1.0).toFloat() }
        private val powTable2 = FloatArray(100000) { Math.pow(B2.toDouble(), it + 1.0).toFloat() }
    }
}
