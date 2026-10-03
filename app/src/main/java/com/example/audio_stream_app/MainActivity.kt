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

    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var executorService: ExecutorService
    private lateinit var audioManager: AudioManager
    private val waveformController = WaveformController()
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
                                waveformController = waveformController,
                                onGainChange = { gainDb = it },
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
        val sampleRate = 44100
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
            val buffer = ShortArray(512)
            val processed = ShortArray(512)
            while (isRecording) {
                val readResult = audioRecord.read(buffer, 0, buffer.size)
                if (readResult > 0) {
                    applyGain(buffer, processed, readResult)
                    audioTrack.write(processed, 0, readResult)
                    waveformController.addSamples(buffer, processed, readResult)
                }
            }
        }
    }

    private fun applyGain(raw: ShortArray, out: ShortArray, count: Int) {
        val multiplier = 10.0.pow(gainDb / 20.0)
        for (i in 0 until count) {
            out[i] = (raw[i] * multiplier).toInt().coerceIn(-32768, 32767).toShort()
        }
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
}
