package com.example.audio_stream_app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.audio_stream_app.desktop.dsp.HowlingSuppressor
import com.example.audio_stream_app.desktop.dsp.anc.AdamTrainer
import com.example.audio_stream_app.desktop.dsp.anc.AncController
import com.example.audio_stream_app.desktop.dsp.anc.LatencyCalibrator
import com.example.audio_stream_app.desktop.dsp.anc.PeriodDetector
import com.example.audio_stream_app.desktop.dsp.anc.WaveformMlp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 桌面端消音器引擎（需求 FR-8，与 Android SilencerEngine 状态机/控件一一对应）。
 *
 * 音频后端为 JDK 自带 javax.sound.sampled：TargetDataLine 采集、SourceDataLine
 * 播放；优先 44100Hz，实际打开的采样率传给 DSP。单线程执行器串行执行
 * 录制 / 分析 / 训练 / 标定 / 实时消音，与扩音器页 [AudioEngine] 互斥。
 */
class SilencerEngine {

    enum class Phase { IDLE, RECORDING, RECORDED, TRAINING, READY, CALIBRATING, ACTIVE }

    // ---- 对 UI 暴露的可观察状态（与 Android 端同名同义）----
    var phase by mutableStateOf(Phase.IDLE); private set
    var notice by mutableStateOf(""); private set

    var recordMs by mutableLongStateOf(0L); private set
    var recordLevel by mutableFloatStateOf(0f); private set
    var recordingEnough by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set

    var analyzing by mutableStateOf(false); private set
    var f0Hz by mutableFloatStateOf(0f); private set
    var confidence by mutableFloatStateOf(0f); private set
    var trainEpoch by mutableIntStateOf(0); private set
    var trainTotal by mutableIntStateOf(0); private set
    var trainLoss by mutableFloatStateOf(0f); private set
    var trainInitialLoss by mutableFloatStateOf(0f); private set
    var trainFinalLoss by mutableFloatStateOf(0f); private set
    var trainElapsedMs by mutableLongStateOf(0L); private set

    var calibratedMs by mutableFloatStateOf(-1f); private set
    var leadAdjustMs by mutableFloatStateOf(0f); private set
    var totalLeadMs by mutableFloatStateOf(0f); private set

    var locked by mutableStateOf(false); private set
    var instantF0 by mutableFloatStateOf(0f); private set
    var antiGain by mutableFloatStateOf(0f); private set
    var bandDeltaDb by mutableFloatStateOf(0f); private set
    var metricsValid by mutableStateOf(false); private set
    var antiLevel by mutableFloatStateOf(0.5f); private set
    var howlingGuard by mutableStateOf(true); private set

    val waveform = DualWaveformController()

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var targetLine: TargetDataLine? = null
    private var sourceLine: SourceDataLine? = null

    /** 实际打开的采样率（44100/48000），首次打开后确定 */
    private var sampleRate = PREFERRED_RATES[0]

    private val recorded = ShortArray(6 * PREFERRED_RATES.max() + CHUNK_SAMPLES)
    private var recordFrames = 0

    @Volatile private var recording = false
    @Volatile private var playback = false
    @Volatile private var active = false
    @Volatile private var howlingOn = true
    private var trainer: AdamTrainer? = null

    private var mlp: WaveformMlp? = null
    private var template = FloatArray(0)
    private var templateRms = 1f
    private var periodInt = 0
    private var detect: PeriodDetector.Result? = null

    // 采样率在首次打开音频后才确定，故延迟创建 DSP
    private var dspRate = 0
    private var controller = AncController(PREFERRED_RATES[0], CHUNK_SAMPLES)
    private var howling = HowlingSuppressor(PREFERRED_RATES[0])

    // ===================================================================
    // 步骤 1：录制 / 回放
    // ===================================================================

    fun toggleRecording() {
        if (phase == Phase.RECORDING) {
            recording = false
            return
        }
        if (phase == Phase.TRAINING || phase == Phase.CALIBRATING || phase == Phase.ACTIVE) return
        startRecording()
    }

    private fun startRecording() {
        resetTrainingArtifact()
        if (!openAudio()) return
        phase = Phase.RECORDING
        recordMs = 0L
        recordLevel = 0f
        recordFrames = 0
        recordingEnough = false
        notice = ""
        recording = true
        io.execute {
            var ok = false
            try {
                val line = targetLine ?: return@execute
                val inBytes = ByteArray(CHUNK_SAMPLES * 2)
                val buf = ShortArray(CHUNK_SAMPLES)
                line.start()
                var level = 0f
                var lastPost = 0L
                val maxSamples = 6 * sampleRate
                while (recording && recordFrames < maxSamples) {
                    val rn = line.read(inBytes, 0, inBytes.size)
                    if (rn <= 0) break
                    val n = rn / 2
                    decodeLittleEndian(inBytes, n, buf)
                    val take = minOf(n, maxSamples - recordFrames)
                    buf.copyInto(recorded, recordFrames, 0, take)
                    recordFrames += take
                    var peak = 0
                    for (i in 0 until take) {
                        val a = abs(buf[i].toInt())
                        if (a > peak) peak = a
                    }
                    level = max(peak / 32768f, level * 0.6f)
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= LEVEL_REFRESH_MS) {
                        lastPost = now
                        recordMs = recordFrames * 1000L / sampleRate
                        recordLevel = level
                    }
                    if (recordFrames >= maxSamples) recording = false
                }
                ok = true
            } catch (t: Throwable) {
                log("recording failed: ${t.message}")
            }
            closeAudio()
            recording = false
            val frames = recordFrames
            recordMs = frames * 1000L / sampleRate
            recordLevel = 0f
            recordingEnough = frames >= 2 * sampleRate
            if (!ok) {
                phase = Phase.IDLE
                notice = "录音失败，请检查系统麦克风权限后重试"
            } else {
                phase = Phase.RECORDED
                notice = if (frames < 2 * sampleRate) "录制不足 2 秒，请重新录制" else ""
                if (frames >= 2 * sampleRate) analyzePeriod()
            }
        }
    }

    fun togglePlayback() {
        if (playing) {
            playback = false
            return
        }
        if (phase != Phase.RECORDED && phase != Phase.READY) return
        if (recordFrames <= 0) return
        if (!openAudio()) return
        playing = true
        playback = true
        io.execute {
            try {
                val line = sourceLine!!
                val bytes = ByteArray(CHUNK_SAMPLES * 2)
                line.start()
                var off = 0
                while (playback && off < recordFrames) {
                    val c = minOf(CHUNK_SAMPLES, recordFrames - off)
                    encodeLittleEndian(recorded, off, c, bytes)
                    line.write(bytes, 0, c * 2)
                    off += c
                }
                line.drain()
            } catch (t: Throwable) {
                log("playback failed: ${t.message}")
            }
            closeAudio()
            playing = false
            playback = false
        }
    }

    // ===================================================================
    // 步骤 2：规律性分析 + 端上训练
    // ===================================================================

    private fun analyzePeriod() {
        analyzing = true
        notice = "正在分析噪音规律性…"
        val x = FloatArray(recordFrames) { recorded[it] / 32768f }
        io.execute {
            val r = PeriodDetector.detect(x, sampleRate)
            analyzing = false
            detect = r
            if (r != null) {
                f0Hz = r.f0Hz
                confidence = r.confidence
                notice = if (r.confidence < PeriodDetector.CONFIDENCE_THRESHOLD) {
                    "噪音规律性偏低，仍可强制训练，但消音效果可能不佳"
                } else ""
            } else {
                f0Hz = 0f
                confidence = 0f
                notice = "未检测到稳定周期，请重录更规律的噪音（风扇/电机/嗡鸣）"
            }
        }
    }

    fun startTraining() {
        val det = detect ?: return
        if (phase != Phase.RECORDED && phase != Phase.READY) return
        if (recordFrames < 2 * sampleRate) return
        phase = Phase.TRAINING
        trainEpoch = 0
        trainTotal = 0
        trainLoss = 0f
        notice = ""
        val x = FloatArray(recordFrames) { recorded[it] / 32768f }
        val tr = AdamTrainer(sampleRate)
        trainer = tr
        io.execute {
            val res = tr.train(x, det) { ep, total, loss ->
                trainEpoch = ep
                trainTotal = total
                trainLoss = loss
            }
            val tplPair: Pair<FloatArray, Float>? =
                if (!tr.cancelled) tr.buildTemplate(x, det.periodSamples) else null
            trainer = null
            if (tr.cancelled) {
                phase = Phase.RECORDED
                notice = "训练已取消"
                return@execute
            }
            val (tpl, rms) = tplPair!!
            mlp = res.mlp
            template = tpl
            templateRms = rms
            periodInt = res.periodSamples
            trainInitialLoss = res.initialLoss
            trainFinalLoss = res.finalLoss
            trainElapsedMs = res.elapsedMs
            calibratedMs = -1f
            leadAdjustMs = 0f
            totalLeadMs = 0f
            attachModel(0)
            phase = Phase.READY
            notice = "训练完成，请进行回路时延标定"
        }
    }

    fun cancelTraining() {
        trainer?.cancelled = true
    }

    fun overlaySamples(maxPoints: Int = 900): Pair<FloatArray, FloatArray>? {
        val net = mlp ?: return null
        if (periodInt < 2 || recordFrames < periodInt) return null
        val n = minOf(maxPoints, periodInt * 4, recordFrames)
        val raw = FloatArray(n) { recorded[it] / 32768f }
        val fit = FloatArray(n) { i ->
            net.predict((i % periodInt).toFloat() / periodInt) * templateRms
        }
        return raw to fit
    }

    // ===================================================================
    // 步骤 3：回路时延标定
    // ===================================================================

    fun calibrate() {
        if (phase != Phase.READY || mlp == null) return
        phase = Phase.CALIBRATING
        notice = ""
        io.execute {
            var delay = -1
            var score = 0f
            var plant = Float.NaN
            try {
                if (openAudio()) {
                    val target = targetLine!!
                    val source = sourceLine!!
                    val chirp = LatencyCalibrator.buildSignal(sampleRate)
                    val prefix = (0.20 * sampleRate).toInt()
                    val suffix = (0.35 * sampleRate).toInt()
                    val play = ShortArray(prefix + chirp.size + suffix)
                    for (i in chirp.indices) {
                        play[prefix + i] = (chirp[i] * 32767f).toInt().toShort()
                    }
                    val rec = ShortArray(play.size + CHUNK_SAMPLES)
                    val pb = ByteArray(CHUNK_SAMPLES * 2)
                    val rb = ByteArray(CHUNK_SAMPLES * 2)
                    target.start()
                    source.start()
                    // 每轮写一块、再嵌套读到恰好一块（TargetDataLine 可能短读），
                    // 避免录音索引出现空洞导致互相关峰错位
                    var off = 0
                    while (off < rec.size) {
                        if (off < play.size) {
                            val c = minOf(CHUNK_SAMPLES, play.size - off)
                            encodeLittleEndian(play, off, c, pb)
                            source.write(pb, 0, c * 2)
                        }
                        val want = minOf(CHUNK_SAMPLES, rec.size - off)
                        var have = 0
                        while (have < want) {
                            val rbN = target.read(rb, 0, minOf(rb.size, (want - have) * 2))
                            if (rbN <= 0) break
                            val got = rbN / 2
                            decodeLittleEndian(rb, got, rec, off + have)
                            have += got
                        }
                        off += want
                    }
                    val rf = FloatArray(rec.size) { rec[it] / 32768f }
                    val est = LatencyCalibrator.estimateDelay(rf, chirp, sampleRate / 2)
                    if (est != null) {
                        delay = est.delaySamples
                        score = est.score
                        plant = est.gain
                    }
                }
            } catch (t: Throwable) {
                log("calibration failed: ${t.message}")
            }
            closeAudio()
            phase = Phase.READY
            if (delay >= 0) {
                calibratedMs = delay * 1000f / sampleRate
                val lead = totalLeadSamples()
                totalLeadMs = lead * 1000f / sampleRate
                controller.updateLead(lead)
                if (!plant.isNaN()) controller.setCalibratedPlant(plant)
                notice = ""
                log("标定成功 延迟=%.1fms 相关峰=%.2f 植物增益=%.3f 实际提前=%.1fms".format(
                    calibratedMs, score, plant, totalLeadMs
                ))
            } else {
                calibratedMs = -1f
                totalLeadMs = 0f
                notice = "标定失败：未检测到标定信号，请保持环境噪音持续后重试"
            }
        }
    }

    fun adjustLead(v: Float) {
        if (calibratedMs < 0f) return
        leadAdjustMs = v.coerceIn(-50f, 50f)
        val lead = totalLeadSamples()
        totalLeadMs = lead * 1000f / sampleRate
        if (mlp != null) controller.updateLead(lead)
    }

    // ===================================================================
    // 步骤 4：实时消音
    // ===================================================================

    fun setActive(on: Boolean) {
        if (on) {
            if (phase != Phase.READY || mlp == null) return
            if (calibratedMs < 0f) {
                notice = "请先完成回路时延标定"
                return
            }
            if (!openAudio()) {
                notice = "无法打开麦克风，请检查系统音频设备与权限"
                return
            }
            phase = Phase.ACTIVE
            active = true
            locked = false
            metricsValid = false
            bandDeltaDb = 0f
            antiGain = 0f
            instantF0 = f0Hz
            waveform.clear()
            io.execute { activeLoop() }
        } else {
            active = false
        }
    }

    private fun activeLoop() {
        val buf = ShortArray(CHUNK_SAMPLES)
        val out = ShortArray(CHUNK_SAMPLES)
        val inBytes = ByteArray(CHUNK_SAMPLES * 2)
        val outBytes = ByteArray(CHUNK_SAMPLES * 2)
        var guard = howlingOn
        howling.reset()
        controller.setEnabled(true)
        controller.reset()
        var frames = 0L
        var lastUi = 0L
        try {
            val target = targetLine!!
            val source = sourceLine!!
            target.start()
            source.start()
            while (active) {
                val rn = target.read(inBytes, 0, inBytes.size)
                if (rn <= 0) break
                val n = rn / 2
                decodeLittleEndian(inBytes, n, buf)
                controller.process(buf, out, n)
                if (howlingOn != guard) {
                    howling.reset()
                    guard = howlingOn
                }
                if (guard) {
                    for (i in 0 until n) {
                        val s = howling.processSample(out[i] / 32768f).coerceIn(-1f, 1f)
                        out[i] = (s * 32767f).toInt().toShort()
                    }
                }
                encodeLittleEndian(out, n, outBytes)
                source.write(outBytes, 0, n * 2)
                waveform.addSamples(buf, out, n)
                frames += n
                val now = System.currentTimeMillis()
                if (now - lastUi >= METRICS_REFRESH_MS) {
                    lastUi = now
                    locked = controller.isLocked
                    instantF0 = controller.instantF0
                    antiGain = controller.antiGain
                    bandDeltaDb = controller.bandEnergyDeltaDb
                    metricsValid = frames > 26L * CHUNK_SAMPLES + sampleRate / 2
                }
            }
        } catch (t: Throwable) {
            log("active loop ended: ${t.message}")
        }
        controller.setEnabled(false)
        closeAudio()
        if (phase == Phase.ACTIVE) phase = Phase.READY
        locked = false
        antiGain = 0f
    }

    fun adjustAntiLevel(v: Float) {
        antiLevel = v.coerceIn(0.1f, 0.5f)
        controller.setMaxAntiLevel(antiLevel)
    }

    fun setHowlingGuardEnabled(on: Boolean) {
        howlingGuard = on
        howlingOn = on
    }

    // ===================================================================
    // 生命周期
    // ===================================================================

    fun stopAll() {
        recording = false
        playback = false
        active = false
        closeAudio()
        when (phase) {
            Phase.RECORDING -> phase = Phase.IDLE
            Phase.CALIBRATING, Phase.ACTIVE -> phase = if (mlp != null) Phase.READY else Phase.IDLE
            else -> Unit
        }
        playing = false
        locked = false
        antiGain = 0f
        recordLevel = 0f
    }

    fun destroy() {
        stopAll()
        trainer?.cancelled = true
        io.shutdownNow()
    }

    // ===================================================================
    // 内部工具
    // ===================================================================

    private fun resetTrainingArtifact() {
        mlp = null
        template = FloatArray(0)
        templateRms = 1f
        periodInt = 0
        detect = null
        f0Hz = 0f
        confidence = 0f
        trainEpoch = 0
        trainTotal = 0
        trainLoss = 0f
        trainInitialLoss = 0f
        trainFinalLoss = 0f
        trainElapsedMs = 0L
        calibratedMs = -1f
        leadAdjustMs = 0f
        totalLeadMs = 0f
        locked = false
        instantF0 = 0f
        antiGain = 0f
        bandDeltaDb = 0f
        metricsValid = false
    }

    private fun attachModel(lead: Int) {
        val net = mlp ?: return
        controller.attach(net, template, templateRms, periodInt, lead)
        controller.setMaxAntiLevel(antiLevel)
    }

    private fun totalLeadSamples(): Int {
        val cal = if (calibratedMs >= 0f) calibratedMs * sampleRate / 1000f else 0f
        val adj = leadAdjustMs * sampleRate / 1000f
        return (cal + adj).roundToInt().coerceIn(0, sampleRate / 2)
    }

    @Synchronized
    private fun openAudio(): Boolean {
        if (targetLine != null && sourceLine != null) return true
        return try {
            val format = supportedFormat()
            val target = AudioSystem.getLine(
                DataLine.Info(TargetDataLine::class.java, format)
            ) as TargetDataLine
            val source = AudioSystem.getLine(
                DataLine.Info(SourceDataLine::class.java, format)
            ) as SourceDataLine
            target.open(format, LINE_BUFFER_BYTES)
            source.open(format, LINE_BUFFER_BYTES)
            targetLine = target
            sourceLine = source
            sampleRate = format.sampleRate.toInt()
            ensureDsp(sampleRate)
            true
        } catch (t: Throwable) {
            log("openAudio failed: ${t.message}")
            notice = "音频设备打开失败：${t.message}"
            closeAudio()
            false
        }
    }

    private fun supportedFormat(): AudioFormat {
        for (rate in PREFERRED_RATES) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (AudioSystem.isLineSupported(info)) return format
        }
        return AudioFormat(PREFERRED_RATES[0].toFloat(), 16, 1, true, false)
    }

    private fun ensureDsp(rate: Int) {
        if (rate == dspRate) return
        dspRate = rate
        controller = AncController(rate, CHUNK_SAMPLES)
        howling = HowlingSuppressor(rate)
        // 若已有训练产物，用新采样率无法直接复用（周期采样数变了），清空
        if (mlp != null) resetTrainingArtifact()
    }

    @Synchronized
    private fun closeAudio() {
        runCatching { targetLine?.stop() }
        runCatching { targetLine?.close() }
        runCatching { sourceLine?.stop() }
        runCatching { sourceLine?.close() }
        targetLine = null
        sourceLine = null
    }

    /** 16-bit little-endian 有符号 PCM → ShortArray（写入 [dst] 前 [count] 项）。 */
    private fun decodeLittleEndian(bytes: ByteArray, count: Int, dst: ShortArray, dstOffset: Int = 0) {
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            dst[dstOffset + i] = ((hi shl 8) or lo).toShort()
        }
    }

    private fun encodeLittleEndian(src: ShortArray, offset: Int, count: Int, dst: ByteArray) {
        for (i in 0 until count) {
            val v = src[offset + i].toInt()
            dst[i * 2] = (v and 0xFF).toByte()
            dst[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    private fun encodeLittleEndian(src: ShortArray, count: Int, dst: ByteArray) =
        encodeLittleEndian(src, 0, count, dst)

    private fun log(msg: String) = println("[$TAG] $msg")

    private companion object {
        const val TAG = "SilencerEngine"
        const val CHUNK_SAMPLES = 512
        const val LINE_BUFFER_BYTES = CHUNK_SAMPLES * 2 * 4
        const val LEVEL_REFRESH_MS = 50L
        const val METRICS_REFRESH_MS = 100L
        val PREFERRED_RATES = intArrayOf(44100, 48000)
    }
}
