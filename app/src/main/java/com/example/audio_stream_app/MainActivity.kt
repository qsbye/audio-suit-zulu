package com.example.audio_stream_app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    @Volatile
    private var isRecording = false
    @Volatile
    private var gainDb = 0.0
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var executorService: ExecutorService
    private lateinit var recordButton: Button
    private lateinit var volumeText: TextView
    private lateinit var volumeProgress: ProgressBar
    private lateinit var muteWarning: TextView
    private lateinit var gainLabel: TextView
    private lateinit var gainSeekBar: SeekBar
    private lateinit var waveformView: WaveformView
    private lateinit var audioManager: AudioManager
    private var defaultButtonTint: ColorStateList? = null
    private val handler = Handler(Looper.getMainLooper())

    private val volumePoller = object : Runnable {
        override fun run() {
            updateVolumeDisplay()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AudioManager::class.java)
        recordButton = findViewById(R.id.recordButton)
        volumeText = findViewById(R.id.volumeText)
        volumeProgress = findViewById(R.id.volumeProgress)
        muteWarning = findViewById(R.id.muteWarning)
        gainLabel = findViewById(R.id.gainLabel)
        gainSeekBar = findViewById(R.id.gainSeekBar)
        waveformView = findViewById(R.id.waveformView)
        defaultButtonTint = recordButton.backgroundTintList
        executorService = Executors.newSingleThreadExecutor()

        setupTabs()
        findViewById<TextView>(R.id.versionText).text = "版本: ${BuildConfig.VERSION_NAME}"

        gainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress - GAIN_RANGE_DB
                gainDb = db.toDouble()
                gainLabel.text = "增益: ${if (db >= 0) "+" else ""}$db dB"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startStreaming()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopStreaming()
            }
            true
        }

        updateVolumeDisplay()
        checkPermissions()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val pageLoudspeaker = findViewById<View>(R.id.pageLoudspeaker)
        val pageAbout = findViewById<View>(R.id.pageAbout)
        tabLayout.addTab(tabLayout.newTab().setText("扩音器"))
        tabLayout.addTab(tabLayout.newTab().setText("关于"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pageLoudspeaker.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                pageAbout.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initializeAudioComponents()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initializeAudioComponents()
        }
    }

    private fun initializeAudioComponents() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun updateVolumeDisplay() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeProgress.max = max
        volumeProgress.progress = current
        volumeText.text = "音量: $current / $max"
        if (current == 0) {
            muteWarning.visibility = View.VISIBLE
            volumeText.setTextColor(ContextCompat.getColor(this, R.color.red))
        } else {
            muteWarning.visibility = View.GONE
            volumeText.setTextColor(ContextCompat.getColor(this, R.color.black))
        }
    }

    private fun setButtonRecording(recording: Boolean) {
        if (recording) {
            recordButton.text = "Recording..."
            recordButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.recording_red))
        } else {
            recordButton.text = "Record"
            recordButton.backgroundTintList = defaultButtonTint
        }
    }

    private fun startStreaming() {
        if (!::audioRecord.isInitialized || !::audioTrack.isInitialized) return
        isRecording = true
        audioRecord.startRecording()
        audioTrack.play()
        setButtonRecording(true)
        updateVolumeDisplay()
        executorService.submit {
            val buffer = ShortArray(512)
            val processed = ShortArray(512)
            while (isRecording) {
                val readResult = audioRecord.read(buffer, 0, buffer.size)
                if (readResult > 0) {
                    applyGain(buffer, processed, readResult)
                    audioTrack.write(processed, 0, readResult)
                    waveformView.addSamples(buffer, processed, readResult)
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
        waveformView.clear()
        setButtonRecording(false)
        updateVolumeDisplay()
    }

    override fun onResume() {
        super.onResume()
        if (::volumeText.isInitialized) {
            updateVolumeDisplay()
            handler.post(volumePoller)
        }
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

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val GAIN_RANGE_DB = 20
    }
}
