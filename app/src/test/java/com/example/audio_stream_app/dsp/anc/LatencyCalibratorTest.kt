package com.example.audio_stream_app.dsp.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LatencyCalibratorTest {

    private val sr = 44100

    /** 构造 ref 出现在指定延迟处、并叠加噪声的"录音"。 */
    private fun fakeRecording(ref: FloatArray, delay: Int, noiseAmp: Float = 0.05f): FloatArray {
        val total = 22050 // 0.5s，容纳最大 4096 + chirp 长度
        val rnd = Random(delay)
        val rec = FloatArray(total) { (rnd.nextFloat() - 0.5f) * 2f * noiseAmp }
        for (t in ref.indices) {
            if (delay + t < total) rec[delay + t] += ref[t]
        }
        return rec
    }

    @Test
    fun estimatesKnownDelaysWithinTwoSamples() {
        val ref = LatencyCalibrator.buildSignal(sr)
        for (delay in listOf(137, 1024, 4096)) {
            val r = LatencyCalibrator.estimateDelay(fakeRecording(ref, delay), ref)
            requireNotNull(r) { "延迟 $delay 未检出" }
            val err = kotlin.math.abs(r.delaySamples - delay)
            println("delay=$delay -> 估计 ${r.delaySamples}, 误差 $err, 置信度 ${r.score}")
            assertTrue("延迟 $delay 误差 $err 超过 2 采样", err <= 2)
            assertTrue(r.score >= LatencyCalibrator.PEAK_THRESHOLD)
        }
    }

    @Test
    fun returnsNullOnPureNoise() {
        val ref = LatencyCalibrator.buildSignal(sr)
        val rnd = Random(99)
        val rec = FloatArray(22050) { (rnd.nextFloat() - 0.5f) * 2f * 0.2f }
        assertNull(LatencyCalibrator.estimateDelay(rec, ref))
    }

    @Test
    fun calibrationSignalIsBounded() {
        val sig = LatencyCalibrator.buildSignal(sr)
        assertEquals((0.25 * sr).toInt(), sig.size)
        assertTrue(sig.all { it in -0.61f..0.61f })
    }
}
