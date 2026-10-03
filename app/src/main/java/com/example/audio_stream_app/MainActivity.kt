package com.example.audio_stream_app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.audio_stream_app.dsp.HowlingSuppressor
import com.example.audio_stream_app.dsp.NlmsAec
import com.example.audio_stream_app.ui.theme.AudioSuitZuluTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private var isRecording by mutableStateOf(false)
    private var gainDb by mutableDoubleStateOf(0.0)
    private var selectedTab by mutableIntStateOf(0)
    private var volume by mutableIntStateOf(0)
    private var maxVolume by mutableIntStateOf(1)
    private var aecEnabled by mutableStateOf(true)
    private var howlingEnabled by mutableStateOf(true)

    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var executorService: ExecutorService
    private lateinit var audioManager: AudioManager
    private val waveformController = WaveformController()
    private val aec = NlmsAec()
    private val howlingSuppressor = HowlingSuppressor(SAMPLE_RATE)

    // 音频线程读取的使能标志（由 UI 状态镜像写入）
    @Volatile
    private var aecOn = true

    @Volatile
    private var howlingOn = true

    private val handler = Handler(Looper.getMainLooper())

    private val volumePoller = object : Runnable {
        override fun run() {
            updateVolumeDisplay()
            handler.postDelayed(this, 500)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) initializeAudioComponents()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AudioManager::class.java)
        executorService = Executors.newSingleThreadExecutor()

        setContent {
            AudioSuitZuluTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("扩音器") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("关于") }
                            )
                        }

                        when (selectedTab) {
                            0 -> LoudspeakerPage(
                                isRecording = isRecording,
                                volume = volume,
                                maxVolume = maxVolume,
                                gainDb = gainDb,
                                aecEnabled = aecEnabled,
                                howlingEnabled = howlingEnabled,
                                waveformController = waveformController,
                                onGainChange = { gainDb = it },
                                onAecEnabledChange = {
                                    aecEnabled = it
                                    aecOn = it
                                },
                                onHowlingEnabledChange = {
                                    howlingEnabled = it
                                    howlingOn = it
                                },
                                onRecordStart = { startStreaming() },
                                onRecordStop = { stopStreaming() }
                            )
                            1 -> AboutPage(versionName = BuildConfig.VERSION_NAME)
                        }
                    }
                }
            }
        }

        updateVolumeDisplay()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initializeAudioComponents()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initializeAudioComponents() {
        val sampleRate = SAMPLE_RATE
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun updateVolumeDisplay() {
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private fun startStreaming() {
        if (!::audioRecord.isInitialized || !::audioTrack.isInitialized) return
        isRecording = true
        audioRecord.startRecording()
        audioTrack.play()
        updateVolumeDisplay()
        executorService.submit {
            val buffer = ShortArray(CHUNK_SAMPLES)
            val processed = ShortArray(CHUNK_SAMPLES)
            // DSP 使能边沿跟踪与参考信号都限定在音频线程内，无需加锁
            var aecActive = aecOn
            var howlingActive = howlingOn
            aec.reset()
            howlingSuppressor.reset()
            var reference = 0f
            while (isRecording) {
                val readResult = audioRecord.read(buffer, 0, buffer.size)
                if (readResult > 0) {
                    if (aecOn != aecActive) {
                        aec.reset()
                        reference = 0f
                        aecActive = aecOn
                    }
                    if (howlingOn != howlingActive) {
                        howlingSuppressor.reset()
                        howlingActive = howlingOn
                    }
                    reference = processBuffer(
                        buffer, processed, readResult, aecActive, howlingActive, reference
                    )
                    audioTrack.write(processed, 0, readResult)
                    waveformController.addSamples(buffer, processed, readResult)
                }
            }
            // 停止后清空回声路径估计与陷波，避免下次启动出现瞬态
            aec.reset()
            howlingSuppressor.reset()
        }
    }

    /**
     * 处理一帧：回声消除 → 防啸叫陷波 → 增益。
     * AEC 的参考信号是真正送往扬声器的播放信号（增益后），返回本帧最后一个参考采样。
     */
    private fun processBuffer(
        raw: ShortArray,
        out: ShortArray,
        count: Int,
        aecActive: Boolean,
        howlingActive: Boolean,
        referenceIn: Float
    ): Float {
        val multiplier = 10.0.pow(gainDb / 20.0).toFloat()
        var reference = referenceIn
        for (i in 0 until count) {
            var sample = raw[i] / MAX_SAMPLE
            if (aecActive) sample = aec.processSample(sample, reference)
            if (howlingActive) sample = howlingSuppressor.processSample(sample)
            val played = (sample * multiplier).coerceIn(-1f, 1f)
            out[i] = (played * 32767f).toInt().toShort()
            reference = played
        }
        return reference
    }

    private fun stopStreaming() {
        if (!::audioRecord.isInitialized) return
        isRecording = false
        audioRecord.stop()
        audioTrack.stop()
        waveformController.clear()
        updateVolumeDisplay()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(volumePoller)
        updateVolumeDisplay()
        handler.post(volumePoller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(volumePoller)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executorService.shutdownNow()
        if (::audioRecord.isInitialized) audioRecord.release()
        if (::audioTrack.isInitialized) audioTrack.release()
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHUNK_SAMPLES = 512
        const val MAX_SAMPLE = 32768f
    }
}
