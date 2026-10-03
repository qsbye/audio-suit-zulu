package com.example.audio_stream_app.dsp.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PeriodDetectorTest {

    private val sr = 44100

    /** 生成基频 + 多个谐波并叠加白噪的周期信号。 */
    private fun periodic(f0: Double, seconds: Double = 2.0, noiseAmp: Float = 0.08f): FloatArray {
        val n = (seconds * sr).toInt()
        val rnd = Random(42)
        val harmonics = listOf(1.0 to 1.0, 2.0 to 0.6, 3.0 to 0.35, 4.0 to 0.2)
        return FloatArray(n) { i ->
            var s = 0f
            for ((mult, amp) in harmonics) {
                s += (amp * sin(2.0 * PI * f0 * mult * i / sr)).toFloat()
            }
            (s / 2.2f) + rnd.nextFloat().let { (it - 0.5f) * 2f * noiseAmp }
        }
    }

    @Test
    fun detectsKnownFundamentalsWithinOnePercent() {
        for (f0 in listOf(60.0, 120.0, 400.0)) {
            val r = PeriodDetector.detect(periodic(f0), sr)
            requireNotNull(r) { "$f0 Hz 未检测到周期" }
            val errPct = kotlin.math.abs(r.f0Hz.toDouble() - f0) / f0 * 100.0
            println("f0=$f0 -> 估计 ${r.f0Hz} Hz, 周期 ${r.periodSamples} 采样, 置信度 ${r.confidence}")
            assertTrue(
                "f0=$f0 误差 ${errPct}% 超过 1%",
                errPct <= 1.0
            )
            assertTrue(
                "f0=$f0 置信度 ${r.confidence} 低于阈值",
                r.confidence >= PeriodDetector.CONFIDENCE_THRESHOLD
            )
        }
    }

    @Test
    fun rejectsWhiteNoise() {
        val rnd = Random(7)
        val noise = FloatArray(88200) { rnd.nextFloat() * 2f - 1f }
        val r = PeriodDetector.detect(noise, sr)
        println("白噪置信度: ${r?.confidence}")
        assertTrue("白噪不应被判为强周期", r == null || r.confidence < PeriodDetector.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun rejectsLinearChirp() {
        val n = 88200
        val tDur = n.toDouble() / sr
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            sin(2.0 * PI * (100.0 * t + 0.5 * (800.0 - 100.0) / tDur * t * t)).toFloat()
        }
        val r = PeriodDetector.detect(x, sr)
        println("扫频信号置信度: ${r?.confidence}")
        assertTrue("扫频信号不应被判为强周期", r == null || r.confidence < PeriodDetector.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun returnsNullOnSilence() {
        assertEquals(null, PeriodDetector.detect(FloatArray(4410), sr))
    }
}
