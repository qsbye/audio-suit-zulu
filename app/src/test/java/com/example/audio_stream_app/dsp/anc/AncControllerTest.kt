package com.example.audio_stream_app.dsp.anc

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AncControllerTest {

    private val sr = 44100
    private val chunk = 512
    private val lead = 256

    /** 仿真声源电平：留出回声与反波余量，避免 Short 削波和 0.5 硬限幅干扰统计。 */
    private val level = 0.4f

    private fun periodicAt(f0: Double, i: Int): Float {
        var s = 0f
        listOf(1.0 to 1.0, 2.0 to 0.6, 3.0 to 0.35, 4.0 to 0.2).forEach { (mult, amp) ->
            s += (amp * sin(2.0 * PI * f0 * mult * i / sr)).toFloat()
        }
        return s / 2.2f
    }

    private fun trainedModel(): Quad {
        val f0 = 120.0
        val n = 2 * sr
        val x = FloatArray(n) { periodicAt(f0, it) }
        val period = PeriodDetector.detect(x, sr)!!
        val trainer = AdamTrainer(sr)
        val r = trainer.train(x, period)
        val (tpl, rms) = trainer.buildTemplate(x, period.periodSamples)
        return Quad(r.mlp, tpl, rms, r.periodSamples)
    }

    private data class Quad(
        val mlp: WaveformMlp, val tpl: FloatArray, val rms: Float, val p: Int
    )

    /** 带模拟声学回路的整段仿真：mic = 外噪 + 0.7×(lead 前播放) 。 */
    private fun simulate(seconds: Double, whiteAfterS: Double? = null): Pair<FloatArray, FloatArray> {
        val (mlp, tpl, rms, p) = trainedModel()
        val ctrl = AncController(sr, chunk)
        ctrl.attach(mlp, tpl, rms, p, lead)
        ctrl.setEnabled(true)

        val total = (seconds * sr).toInt()
        val rnd = Random(2026)
        val played = FloatArray(total)
        val ext = FloatArray(total)
        val mic = ShortArray(chunk)
        val out = ShortArray(chunk)

        var n = 0
        while (n + chunk <= total) {
            for (i in 0 until chunk) {
                val idx = n + i
                val source = if (whiteAfterS != null && idx >= (whiteAfterS * sr)) {
                    (rnd.nextFloat() - 0.5f) * 2f * 0.45f * level
                } else {
                    periodicAt(120.0, idx) * level
                }
                ext[idx] = source
                var m = source
                if (idx - lead >= 0) m += 0.7f * played[idx - lead]
                mic[i] = (m * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            ctrl.process(mic, out, chunk)
            for (i in 0 until chunk) {
                val v = out[i] / 32768f
                played[n + i] = v
                assertTrue("输出越界: $v", v in -0.5001f..0.5001f)
                assertTrue("出现 NaN", v == v)
            }
            n += chunk
        }
        return ext to played
    }

    @Test
    fun emitsAntiPhaseWhenLocked() {
        val (_, played) = simulate(3.0)
        // 回路模型 mic[t] = 噪声[t] + 0.7·played[t-lead]：t 时刻播放的反波要在
        // t+lead 时刻到达麦克，故 played[i] 必须与「未来 i+lead 处的噪声」反相。
        // 缓升约 1s，取 2.0–2.5s 稳定段比较。
        val a = (2.0 * sr).toInt()
        val b = (2.5 * sr).toInt()
        var mx = 0.0
        var my = 0.0
        for (i in a until b) {
            mx += played[i].toDouble()
            my += periodicAt(120.0, i + lead).toDouble()
        }
        val len = b - a
        mx /= len; my /= len
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in a until b) {
            val xx = played[i].toDouble() - mx
            val yy = periodicAt(120.0, i + lead).toDouble() - my
            cov += xx * yy; vx += xx * xx; vy += yy * yy
        }
        val corr = cov / kotlin.math.sqrt(vx * vy)
        println("稳态反波-未来噪声相关系数 = $corr（应 ≤ -0.9）")
        assertTrue("反波应与未来 i+lead 处噪声反相，实际 $corr", corr <= -0.9)
    }

    @Test
    fun fadesOutWithinHalfSecondWhenNoiseBecomesAperiodic() {
        val (_, played) = simulate(3.6, whiteAfterS = 3.0)
        fun rms(fromS: Double, toS: Double): Double {
            var s = 0.0
            var c = 0
            for (i in (fromS * sr).toInt() until (toS * sr).toInt()) {
                s += played[i].toDouble() * played[i]; c++
            }
            return kotlin.math.sqrt(s / c)
        }
        val steady = rms(2.6, 2.8)
        val after = rms(3.55, 3.6)
        println("稳态输出 RMS=$steady, 失锁 0.55s 后 RMS=$after, 比例=${after / steady}")
        assertTrue("稳态应有输出", steady > 0.05)
        assertTrue("失锁后应 ≤10%，实际 ${after / steady}", after <= steady * 0.1)
    }

    @Test
    fun debugDumpState() {
        val (mlp, tpl, rms, p) = trainedModel()
        val ctrl = AncController(sr, chunk)
        ctrl.attach(mlp, tpl, rms, p, lead)
        ctrl.setEnabled(true)
        val total = (3.6 * sr).toInt()
        val rnd = Random(2026)
        val played = FloatArray(total)
        val mic = ShortArray(chunk)
        val out = ShortArray(chunk)
        var n = 0
        var cIdx = 0
        while (n + chunk <= total) {
            for (i in 0 until chunk) {
                val idx = n + i
                val source = if (idx >= 3.0 * sr) (rnd.nextFloat() - 0.5f) * 2f * 0.45f * level
                else periodicAt(120.0, idx) * level
                var m = source
                if (idx - lead >= 0) m += 0.7f * played[idx - lead]
                mic[i] = (m * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            ctrl.process(mic, out, chunk)
            for (i in 0 until chunk) played[n + i] = out[i] / 32768f
            val t = n.toDouble() / sr
            if (cIdx % 10 == 0 || (t in 2.94..3.20)) {
                println("t=%.2f lock=%s conf=%.3f gate=%.3f amp=%.4f wsum=%.3f"
                    .format(t, ctrl.isLocked, ctrl.dbgConfidence, ctrl.dbgGate,
                        ctrl.dbgAmp, ctrl.dbgEchoWSum))
            }
            n += chunk; cIdx++
        }
    }

    @Test
    fun chunkProcessingIsFastEnough() {
        val (mlp, tpl, rms, p) = trainedModel()
        val ctrl = AncController(sr, chunk)
        ctrl.attach(mlp, tpl, rms, p, lead)
        ctrl.setEnabled(true)
        val mic = ShortArray(chunk)
        val out = ShortArray(chunk)
        // 预热至锁定
        for (c in 0 until 200) {
            for (i in 0 until chunk) {
                val idx = c * chunk + i
                mic[i] = (periodicAt(120.0, idx) * 32767f).toInt().toShort()
            }
            ctrl.process(mic, out, chunk)
        }
        val reps = 200
        val t0 = System.nanoTime()
        for (c in 200 until 200 + reps) {
            for (i in 0 until chunk) {
                val idx = c * chunk + i
                mic[i] = (periodicAt(120.0, idx) * 32767f).toInt().toShort()
            }
            ctrl.process(mic, out, chunk)
        }
        val ms = (System.nanoTime() - t0) / 1e6 / reps
        println("锁定后单 chunk($chunk 采样) 平均耗时 = ${"%.3f".format(ms)} ms（预算 11.6ms）")
        assertTrue("单 chunk 耗时需 <11.6ms", ms < 11.6)
    }
}
