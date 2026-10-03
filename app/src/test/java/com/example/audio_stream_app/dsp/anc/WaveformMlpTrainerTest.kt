package com.example.audio_stream_app.dsp.anc

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class WaveformMlpTrainerTest {

    private val sr = 44100

    private fun periodic(f0: Double, seconds: Double = 2.0, noiseAmp: Float = 0.08f): FloatArray {
        val n = (seconds * sr).toInt()
        val rnd = Random(42)
        val harmonics = listOf(1.0 to 1.0, 2.0 to 0.6, 3.0 to 0.35, 4.0 to 0.2)
        return FloatArray(n) { i ->
            var s = 0f
            for ((mult, amp) in harmonics) {
                s += (amp * sin(2.0 * PI * f0 * mult * i / sr)).toFloat()
            }
            (s / 2.2f) + (rnd.nextFloat() - 0.5f) * 2f * noiseAmp
        }
    }

    @Test
    fun trainsAndPredictsPeriodicWaveform() {
        val f0 = 120.0
        val x = periodic(f0)
        val period = PeriodDetector.detect(x, sr)!!
        val trainer = AdamTrainer(sr)
        val result = trainer.train(x, period) { epoch, total, loss ->
            if (epoch == 1 || epoch % 50 == 0) println("epoch $epoch/$total loss=$loss")
        }

        println(
            "训练完成: epochs=${result.epochsRun}, 耗时=${result.elapsedMs}ms, " +
                "loss ${result.initialLoss} -> ${result.finalLoss}, K=${result.mlp.harmonics}, " +
                "权重=${result.mlp.parameterCount}"
        )
        assertTrue("权重数需 ≤3000", result.mlp.parameterCount <= 3000)

        val p = result.periodSamples
        // 重建归一化模板作为真值
        val (rawTemplate, rms) = trainer.buildTemplate(x, period.periodSamples)
        val truth = FloatArray(p) { rawTemplate[it] / rms }

        // NMSE：整周期逐相位推理 vs 归一化真值
        var num = 0.0
        var den = 0.0
        for (j in 0 until p) {
            val pred = result.mlp.predict(j.toFloat() / p)
            val e = pred - truth[j]
            num += e * e
            den += truth[j].toDouble() * truth[j]
        }
        val nmseDb = 10.0 * ln(num / den) / ln(10.0)
        println("整周期 NMSE = ${"%.2f".format(nmseDb)} dB")
        assertTrue("NMSE 需 ≤ -15dB，实际 $nmseDb", nmseDb <= -15.0)

        // 提前 lead=2048 采样：预测未来相位 vs 当前真值应高度相关（周期延拓）
        val lead = 2048
        var mx = 0.0
        var my = 0.0
        for (j in 0 until p) {
            val fi = (j + lead) % p
            mx += result.mlp.predict(fi.toFloat() / p).toDouble()
            my += truth[fi].toDouble()
        }
        mx /= p; my /= p
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (j in 0 until p) {
            val fi = (j + lead) % p
            val a = result.mlp.predict(fi.toFloat() / p).toDouble() - mx
            val b = truth[fi].toDouble() - my
            cov += a * b; vx += a * a; vy += b * b
        }
        val corr = cov / kotlin.math.sqrt(vx * vy)
        println("lead=$lead 预测-真值 相关系数 = $corr")
        assertTrue("提前预测相关系数需 ≥0.99，实际 $corr", corr >= 0.99)
    }

    @Test
    fun trainingIsCancellable() {
        val x = periodic(60.0, seconds = 2.0)
        val period = PeriodDetector.detect(x, sr)!!
        val trainer = AdamTrainer(sr)
        trainer.cancelled = true
        val t0 = System.currentTimeMillis()
        val result = trainer.train(x, period)
        assertTrue("取消后应立即返回", System.currentTimeMillis() - t0 < 1000)
        assertTrue(result.epochsRun == 0)
    }
}
