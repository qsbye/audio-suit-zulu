package com.example.audio_stream_app.dsp.anc

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 端上训练器：周期对齐平均去噪 + mini-batch Adam 训练 [WaveformMlp]。
 *
 * 训练目标不是整段录音，而是按基频周期把若干个周期折叠平均得到的"单周期模板"
 * （即 ANC 译文 II.A 的波形合成思想：周期波形只需学习一个周期）。
 * 平均会大幅压掉非周期噪声，训练样本量也从几十万降到 ≤P（≤1470），
 * 使真机端上训练在数十秒内收敛。
 */
class AdamTrainer(
    private val sampleRate: Int,
    private val maxEpochs: Int = 600,
    private val batchSize: Int = 256
) {

    data class Result(
        val mlp: WaveformMlp,
        val periodSamples: Int,
        val templateRms: Float,
        val initialLoss: Float,
        val finalLoss: Float,
        val epochsRun: Int,
        val elapsedMs: Long
    )

    @Volatile
    var cancelled = false

    /**
     * 按周期折叠平均得到去噪单周期模板，返回（模板, RMS）。
     *
     * 真实基频周期几乎从不是整数采样（如 44100Hz 下 120Hz = 367.5 采样），
     * 若直接用 i % round(P) 折叠，每个周期残留的半采样误差会沿数百个周期
     * 累积，把模板平均模糊掉。这里在长度 p 的均匀相位网格上按浮点周期步进，
     * 每个采样按线性插值分摊到相邻两个网格点，消除亚采样折叠模糊。
     */
    fun buildTemplate(x: FloatArray, periodSamples: Float): Pair<FloatArray, Float> {
        val p = periodSamples.roundToInt().coerceAtLeast(2)
        val sums = DoubleArray(p)
        val weights = DoubleArray(p)
        val inc = p / periodSamples   // 每真实采样在 p 点相位网格上的步进
        var pos = 0.0
        for (v in x) {
            val f = ((pos % p) + p) % p
            val j0 = kotlin.math.floor(f).toInt()
            val w1 = f - j0
            val j1 = (j0 + 1) % p
            val d = v.toDouble()
            sums[j0] += d * (1.0 - w1)
            weights[j0] += 1.0 - w1
            sums[j1] += d * w1
            weights[j1] += w1
            pos += inc
        }
        var power = 0.0
        val tpl = FloatArray(p) { j ->
            val v = (sums[j] / weights[j].coerceAtLeast(1e-9)).toFloat()
            power += v.toDouble() * v
            v
        }
        val rms = sqrt(power / p).toFloat().coerceAtLeast(1e-6f)
        return tpl to rms
    }

    /**
     * 执行训练（应放在后台线程调用）。
     *
     * @param onEpoch 每轮结束回调（epoch 从 1 起、总轮次、本轮平均 MSE）
     */
    fun train(
        x: FloatArray,
        period: PeriodDetector.Result,
        onEpoch: ((epoch: Int, total: Int, loss: Float) -> Unit)? = null
    ): Result {
        val started = System.currentTimeMillis()
        val p = period.periodSamples.roundToInt().coerceAtLeast(2)
        val (rawTemplate, rms) = buildTemplate(x, period.periodSamples)
        val target = FloatArray(p) { rawTemplate[it] / rms }
        val phases = FloatArray(p) { it.toFloat() / p }

        val nyquist = sampleRate / 2f
        val k = (nyquist / period.f0Hz).toInt().coerceIn(1, 10)
        val mlp = WaveformMlp(k)

        val order = IntArray(p) { it }
        val rnd = Random(1234)
        var step = 0L
        var initialLoss = Float.NaN
        var bestLoss = Float.MAX_VALUE
        var bestParams: FloatArray? = null
        var noImprove = 0
        var lastLoss = Float.MAX_VALUE
        var epoch = 0

        while (epoch < maxEpochs && !cancelled) {
            epoch++
            // Fisher-Yates 打乱
            for (i in p - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val tmp = order[i]; order[i] = order[j]; order[j] = tmp
            }
            // 按打乱顺序重排本 epoch 的相位/目标
            val ep = FloatArray(p) { phases[order[it]] }
            val et = FloatArray(p) { target[order[it]] }

            var lossSum = 0.0
            var batches = 0
            var start = 0
            while (start < p) {
                val count = minOf(batchSize, p - start)
                val lr = learningRateAt(epoch)
                lossSum += mlp.trainBatch(ep, et, start, count, lr, ++step).toDouble()
                batches++
                start += count
            }
            lastLoss = (lossSum / batches).toFloat()
            if (epoch == 1) initialLoss = lastLoss
            onEpoch?.invoke(epoch, maxEpochs, lastLoss)

            if (lastLoss < bestLoss) {
                // 保存本轮最优权重，Adam 偶发尖峰时可回退
                bestParams = mlp.exportParams()
                bestLoss = lastLoss
                noImprove = 0
            } else {
                noImprove++
            }
            // 已到 -37dB 量级或 60 轮无新低则提前结束
            if (bestLoss <= 2e-4f || noImprove >= 60) break
        }

        // 恢复训练过程中的最优权重
        bestParams?.let {
            mlp.importParams(it)
            lastLoss = bestLoss
        }

        return Result(
            mlp = mlp,
            periodSamples = p,
            templateRms = rms,
            initialLoss = initialLoss,
            finalLoss = lastLoss,
            epochsRun = epoch,
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    private fun learningRateAt(epoch: Int): Float = when {
        epoch < maxEpochs * 40 / 100 -> 0.01f
        epoch < maxEpochs * 75 / 100 -> 0.004f
        else -> 0.001f
    }
}
