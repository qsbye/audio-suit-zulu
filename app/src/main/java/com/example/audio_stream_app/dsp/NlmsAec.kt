package com.example.audio_stream_app.dsp

import kotlin.math.abs

/**
 * 基于 NLMS（归一化最小均方）自适应滤波器的声学回声消除器。
 *
 * 参考 docs/aec-principle-and-implementation-cnblogs.md：
 *  - x(n) 参考信号：实际送往扬声器播放的信号
 *  - d(n) 麦克风信号：近端人声 + 扬声器经房间耦合回来的回声
 *  - ŷ(n) = ŵ(n)·x(n) 为估计回声，残差 e(n) = d(n) − ŷ(n) ≈ 近端人声
 *  - 系数更新：ŵ(n+1) = ŵ(n) + μ·e(n)·x(n) / (xᵀx + β)
 *
 * 附带 Geigel 双讲检测（DTD）：当麦克风信号幅度显著大于参考回声的窗口峰值时，
 * 判定为近端直达声占主导，本采样点冻结滤波器系数，避免滤波器"调歪"发散。
 *
 * 所有采样均为归一化浮点（-1f..1f），逐样本处理、无内存分配。
 *
 * @param filterLength 滤波器阶数；512 点 @44.1kHz 约覆盖 11.6ms 回声路径
 * @param stepSize 步长 μ（0<μ<2），越大收敛越快、稳态误差越大
 * @param regularization 正则项 β，防止参考能量过小时步长爆炸
 */
class NlmsAec(
    private val filterLength: Int = 512,
    private val stepSize: Float = 0.3f,
    private val regularization: Float = 1e-4f
) {
    private val weights = FloatArray(filterLength)
    private val referenceLine = FloatArray(filterLength)

    fun reset() {
        weights.fill(0f)
        referenceLine.fill(0f)
    }

    /**
     * @param mic 麦克风当前采样 d(n)
     * @param reference 实际播放出去的参考采样 x(n)（取增益后、写入扬声器的信号）
     * @return 回声消除后的残差信号 e(n)
     */
    fun processSample(mic: Float, reference: Float): Float {
        // 参考延迟线右移一位
        System.arraycopy(referenceLine, 0, referenceLine, 1, filterLength - 1)
        referenceLine[0] = reference

        var estimatedEcho = 0f
        var energy = 0f
        var maxRef = 0f
        for (i in 0 until filterLength) {
            val xi = referenceLine[i]
            estimatedEcho += weights[i] * xi
            energy += xi * xi
            val ax = abs(xi)
            if (ax > maxRef) maxRef = ax
        }

        val error = mic - estimatedEcho

        // Geigel DTD：|d| 超过参考窗口峰值的一半时视为近端讲话，冻结更新
        val doubleTalk = maxRef > 1e-4f && abs(mic) >= DTD_THRESHOLD * maxRef
        if (!doubleTalk) {
            val gain = stepSize * error / (energy + regularization)
            for (i in 0 until filterLength) {
                weights[i] += gain * referenceLine[i]
            }
        }
        return error
    }

    private companion object {
        const val DTD_THRESHOLD = 0.5f
    }
}
