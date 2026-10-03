package com.example.audio_stream_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.audio_stream_app.dsp.HowlingSuppressor
import com.example.audio_stream_app.dsp.anc.AdamTrainer
import com.example.audio_stream_app.dsp.anc.AncController
import com.example.audio_stream_app.dsp.anc.LatencyCalibrator
import com.example.audio_stream_app.dsp.anc.PeriodDetector
import com.example.audio_stream_app.dsp.anc.WaveformMlp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 消音器页音频引擎（需求 FR-2 ~ FR-7）。
 *
 * 独立持有 AudioRecord/AudioTrack 会话（按需打开、用完即释放），与扩音器页
 * 互斥；内部单线程执行器串行执行 录制 / 分析 / 训练 / 标定 / 实时消音。
 *
 * 状态机：IDLE → RECORDING → RECORDED → TRAINING → READY → CALIBRATING → ACTIVE。
 * 所有以 Compose state 暴露的字段只在主线程做分组写入（音频线程通过 [main]
 * Handler post），波形节流沿用 [DualWaveformController] 自身机制。
 */
class SilencerEngine(private val context: Context) {

    enum class Phase { IDLE, RECORDING, RECORDED, TRAINING, READY, CALIBRATING, ACTIVE }

    // ---- 对 UI 暴露的可观察状态 ----
    var phase by mutableStateOf(Phase.IDLE); private set
    var notice by mutableStateOf(""); private set

    // 录制
    var recordMs by mutableLongStateOf(0L); private set
    var recordLevel by mutableFloatStateOf(0f); private set
    var recordingEnough by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set

    // 分析 / 训练
    var analyzing by mutableStateOf(false); private set
    var f0Hz by mutableFloatStateOf(0f); private set
    var confidence by mutableFloatStateOf(0f); private set
    var trainEpoch by mutableIntStateOf(0); private set
    var trainTotal by mutableIntStateOf(0); private set
    var trainLoss by mutableFloatStateOf(0f); private set
    var trainInitialLoss by mutableFloatStateOf(0f); private set
    var trainFinalLoss by mutableFloatStateOf(0f); private set
    var trainElapsedMs by mutableLongStateOf(0L); private set

    // 标定 / 微调
    var calibratedMs by mutableFloatStateOf(-1f); private set
    var leadAdjustMs by mutableFloatStateOf(0f); private set
    var totalLeadMs by mutableFloatStateOf(0f); private set

    // 实时消音
    var locked by mutableStateOf(false); private set
    var instantF0 by mutableFloatStateOf(0f); private set
    var antiGain by mutableFloatStateOf(0f); private set
    var bandDeltaDb by mutableFloatStateOf(0f); private set
    var metricsValid by mutableStateOf(false); private set
    var antiLevel by mutableFloatStateOf(0.5f); private set
    var howlingGuard by mutableStateOf(true); private set

    val waveform = DualWaveformController()

    // ---- 线程与音频会话 ----
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ---- 录音产物（最多 6s，约 529KB）----
    private val recorded = ShortArray(MAX_RECORD_SAMPLES)
    private var recordFrames = 0

    @Volatile private var recording = false
    @Volatile private var playback = false
    @Volatile private var active = false
    @Volatile private var howlingOn = true
    private var trainer: AdamTrainer? = null

    // ---- 训练产物 ----
    private var mlp: WaveformMlp? = null
    private var template = FloatArray(0)
    private var templateRms = 1f
    private var periodInt = 0
    private var detect: PeriodDetector.Result? = null

    private val controller = AncController(SAMPLE_RATE, CHUNK_SAMPLES)
    private val howling = HowlingSuppressor(SAMPLE_RATE)

    // ===================================================================
    // 步骤 1：录制 / 回放
    // ===================================================================

    /** 录制按钮：IDLE/RECORDED/READY 时开始（重录会清空旧模型），RECORDING 时停止。 */
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
                val ar = audioRecord ?: return@execute
                val buf = ShortArray(CHUNK_SAMPLES)
                ar.startRecording()
                var level = 0f
                var lastPost = 0L
                while (recording && recordFrames < MAX_RECORD_SAMPLES) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val take = minOf(n, MAX_RECORD_SAMPLES - recordFrames)
                    buf.copyInto(recorded, recordFrames, 0, take)
                    recordFrames += take
                    var peak = 0
                    for (i in 0 until take) {
                        val a = abs(buf[i].toInt())
                        if (a > peak) peak = a
                    }
                    // 峰值保持 + 衰减，电平表看起来稳定不跳
                    level = max(peak / 32768f, level * 0.6f)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPost >= LEVEL_REFRESH_MS) {
                        lastPost = now
                        val ms = recordFrames * 1000L / SAMPLE_RATE
                        main.post { recordMs = ms; recordLevel = level }
                    }
                    if (recordFrames >= MAX_RECORD_SAMPLES) recording = false
                }
                ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "recording failed", t)
            }
            closeAudio()
            recording = false
            val frames = recordFrames
            main.post {
                recordMs = frames * 1000L / SAMPLE_RATE
                recordLevel = 0f
                recordingEnough = frames >= MIN_RECORD_SAMPLES
                if (!ok) {
                    phase = Phase.IDLE
                    notice = "录音失败，请重试"
                    return@post
                }
                phase = Phase.RECORDED
                notice = if (frames < MIN_RECORD_SAMPLES) {
                    "录制不足 2 秒，请重新录制"
                } else ""
                if (frames >= MIN_RECORD_SAMPLES) analyzePeriod()
            }
        }
    }

    /** 试听 / 停止试听录制的噪音。 */
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
                val at = audioTrack!!
                at.play()
                var off = 0
                while (playback && off < recordFrames) {
                    val c = minOf(CHUNK_SAMPLES, recordFrames - off)
                    at.write(recorded, off, c)
                    off += c
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playback failed", t)
            }
            closeAudio()
            main.post {
                playing = false
                playback = false
            }
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
            val r = PeriodDetector.detect(x, SAMPLE_RATE)
            main.post {
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
    }

    fun startTraining() {
        val det = detect ?: return
        if (phase != Phase.RECORDED && phase != Phase.READY) return
        if (recordFrames < MIN_RECORD_SAMPLES) return
        phase = Phase.TRAINING
        trainEpoch = 0
        trainTotal = 0
        trainLoss = 0f
        notice = ""
        val x = FloatArray(recordFrames) { recorded[it] / 32768f }
        val tr = AdamTrainer(SAMPLE_RATE)
        trainer = tr
        io.execute {
            val res = tr.train(x, det) { ep, total, loss ->
                main.post {
                    trainEpoch = ep
                    trainTotal = total
                    trainLoss = loss
                }
            }
            // 与训练内部相同的确定性折叠，补取控制器所需原始模板
            val tplPair: Pair<FloatArray, Float>? =
                if (!tr.cancelled) tr.buildTemplate(x, det.periodSamples) else null
            main.post {
                trainer = null
                if (tr.cancelled) {
                    phase = Phase.RECORDED
                    notice = "训练已取消"
                    return@post
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
    }

    fun cancelTraining() {
        trainer?.cancelled = true
    }

    /**
     * 训练完成后的录音 vs 网络拟合叠加预览（FR-3）。
     * 取前若干个周期（最多 [maxPoints] 点），拟合相位与折叠网格对齐
     * （buildTemplate 从采样 0 开始铺相位，故采样 i 的相位即 i mod P）。
     */
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
            try {
                if (openAudio()) {
                    val ar = audioRecord!!
                    val at = audioTrack!!
                    val chirp = LatencyCalibrator.buildSignal(SAMPLE_RATE)
                    val prefix = (0.20 * SAMPLE_RATE).toInt()
                    val suffix = (0.35 * SAMPLE_RATE).toInt()
                    val play = ShortArray(prefix + chirp.size + suffix)
                    for (i in chirp.indices) {
                        play[prefix + i] = (chirp[i] * 32767f).toInt().toShort()
                    }
                    // 多录一个 chunk 做尾部余量
                    val rec = ShortArray(play.size + CHUNK_SAMPLES)
                    ar.startRecording()
                    at.play()
                    // 每个块先写再读：write 在管线有空位时即时入队，read 阻塞一个
                    // chunk 时长，两条时间线在块边界对齐，误差远小于一个 chunk
                    val chunks = (rec.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
                    var ci = 0
                    while (ci < chunks) {
                        val off = ci * CHUNK_SAMPLES
                        if (off < play.size) {
                            at.write(play, off, minOf(CHUNK_SAMPLES, play.size - off))
                        }
                        val rn = ar.read(rec, off, minOf(CHUNK_SAMPLES, rec.size - off))
                        if (rn < 0) break
                        ci++
                    }
                    val rf = FloatArray(rec.size) { rec[it] / 32768f }
                    // 夹 0–500ms（estimateDelay 内部再夹一次）
                    val est = LatencyCalibrator.estimateDelay(rf, chirp, MAX_DELAY_SAMPLES)
                    if (est != null) {
                        delay = est.delaySamples
                        score = est.score
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "calibration failed", t)
            }
            closeAudio()
            val d = delay
            val s = score
            main.post {
                phase = Phase.READY
                if (d >= 0) {
                    calibratedMs = d * 1000f / SAMPLE_RATE
                    val lead = totalLeadSamples()
                    totalLeadMs = lead * 1000f / SAMPLE_RATE
                    controller.updateLead(lead)
                    notice = ""
                    Log.i(
                        TAG,
                        "标定成功 延迟=%.1fms 相关峰=%.2f 实际提前=%.1fms".format(
                            calibratedMs, s, totalLeadMs
                        )
                    )
                } else {
                    calibratedMs = -1f
                    totalLeadMs = 0f
                    notice = "标定失败：未检测到标定信号，请保持环境噪音持续后重试"
                }
            }
        }
    }

    /** ±50ms 手动微调，实时更新提前量。 */
    fun setLeadAdjustMs(v: Float) {
        if (calibratedMs < 0f) return
        leadAdjustMs = v.coerceIn(-50f, 50f)
        val lead = totalLeadSamples()
        totalLeadMs = lead * 1000f / SAMPLE_RATE
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
                notice = "无法打开麦克风，请检查录音权限"
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
        var guard = howlingOn
        howling.reset()
        controller.setEnabled(true)
        controller.reset()
        var frames = 0L
        var lastUi = 0L
        var lastLog = 0L
        try {
            val ar = audioRecord!!
            val at = audioTrack!!
            ar.startRecording()
            at.play()
            while (active) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) break
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
                at.write(out, 0, n)
                waveform.addSamples(buf, out, n)
                frames += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= METRICS_REFRESH_MS) {
                    lastUi = now
                    val lk = controller.isLocked
                    val f = controller.instantF0
                    val g = controller.antiGain
                    val db = controller.bandEnergyDeltaDb
                    // 基线 0.3s + 再 0.5s 平滑后指标才可信
                    val valid = frames > BASELINE_VALID_FRAMES
                    main.post {
                        locked = lk
                        instantF0 = f
                        antiGain = g
                        bandDeltaDb = db
                        metricsValid = valid
                    }
                }
                if (now - lastLog >= LOG_REFRESH_MS) {
                    lastLog = now
                    Log.i(
                        TAG,
                        "消音中 locked=%s f0=%.1f gain=%.2f bandΔ=%.1fdB".format(
                            controller.isLocked,
                            controller.instantF0,
                            controller.antiGain,
                            controller.bandEnergyDeltaDb
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "active loop ended", t)
        }
        controller.setEnabled(false)
        closeAudio()
        main.post {
            if (phase == Phase.ACTIVE) phase = Phase.READY
            locked = false
            antiGain = 0f
        }
    }

    fun setAntiLevel(v: Float) {
        antiLevel = v.coerceIn(0.1f, 0.5f)
        controller.setMaxAntiLevel(antiLevel)
    }

    fun setHowlingGuard(on: Boolean) {
        howlingGuard = on
        howlingOn = on
    }

    // ===================================================================
    // 生命周期：切页 / 退后台 / 销毁
    // ===================================================================

    /**
     * 停止一切占用音频设备的活动（线程安全）：直接 stop/release 音频对象，
     * 阻塞中的 read/write 立即抛错或返回负值，工作线程随后退出。
     * 训练在内存中继续跑完（无音频占用），训练完会回到 RECORDED/READY。
     */
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
        val cal = if (calibratedMs >= 0f) calibratedMs * SAMPLE_RATE / 1000f else 0f
        val adj = leadAdjustMs * SAMPLE_RATE / 1000f
        return (cal + adj).roundToInt().coerceIn(0, MAX_DELAY_SAMPLES)
    }

    @Synchronized
    private fun openAudio(): Boolean {
        if (audioRecord != null && audioTrack != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notice = "需要麦克风权限"
            return false
        }
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = max(CHUNK_BYTES * 4, if (minBuf > 0) minBuf else CHUNK_BYTES * 4)
            val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

            var rec: AudioRecord? = buildRecord(bufBytes, lowLatency)
            if (rec?.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                rec = buildRecord(bufBytes, false)
            }
            var trk: AudioTrack? = buildTrack(bufBytes, lowLatency)
            if (trk?.state != AudioTrack.STATE_INITIALIZED) {
                trk?.release()
                trk = buildTrack(bufBytes, false)
            }
            if (rec?.state != AudioRecord.STATE_INITIALIZED ||
                trk?.state != AudioTrack.STATE_INITIALIZED
            ) {
                rec?.release()
                trk?.release()
                notice = "音频设备打开失败"
                false
            } else {
                audioRecord = rec
                audioTrack = trk
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openAudio failed", t)
            notice = "音频设备打开失败：${t.message}"
            false
        }
    }

    private fun buildFormat(input: Boolean, lowLatency: Boolean): AudioFormat =
        AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(if (input) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_OUT_MONO)
            .apply {
                // API26+ 走低延迟路径，失败由调用方回退普通模式（NFR-3）
                if (lowLatency) setPerformanceMode(AudioFormat.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .build()

    private fun buildRecord(bufBytes: Int, lowLatency: Boolean): AudioRecord? = try {
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(buildFormat(input = true, lowLatency = lowLatency))
            .setBufferSizeInBytes(bufBytes)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "buildRecord lowLatency=$lowLatency failed", t)
        null
    }

    private fun buildTrack(bufBytes: Int, lowLatency: Boolean): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(buildFormat(input = false, lowLatency = lowLatency))
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "buildTrack lowLatency=$lowLatency failed", t)
        null
    }

    @Synchronized
    private fun closeAudio() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // 未在录音时 stop 会抛异常，忽略
        }
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // 未在播放时 stop 会抛异常，忽略
        }
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
    }

    @Suppress("unused")
    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private companion object {
        const val TAG = "SilencerEngine"
        const val SAMPLE_RATE = 44100
        const val CHUNK_SAMPLES = 512
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
        const val MIN_RECORD_SAMPLES = (2.0 * SAMPLE_RATE).toInt()
        const val MAX_RECORD_SAMPLES = 6 * SAMPLE_RATE
        const val MAX_DELAY_SAMPLES = SAMPLE_RATE / 2              // 500ms
        const val LEVEL_REFRESH_MS = 50L
        const val METRICS_REFRESH_MS = 100L
        const val LOG_REFRESH_MS = 500L
        // 基线约 0.3s（26 块）+ 再 0.5s 平滑
        const val BASELINE_VALID_FRAMES = 26 * CHUNK_SAMPLES + SAMPLE_RATE / 2
    }
}
