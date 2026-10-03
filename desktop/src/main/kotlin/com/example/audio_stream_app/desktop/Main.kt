package com.example.audio_stream_app.desktop

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.audio_stream_app.desktop.ui.theme.AudioSuitZuluTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val APP_VERSION = "1.0.0"

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(430.dp, 820.dp))
    Window(
        onCloseRequest = {
            runCatching { engineRef?.stop() }
            exitApplication()
        },
        state = windowState,
        title = "AudioSuitZulu音函 (macOS)"
    ) {
        App()
    }
}

/** 进程级单例，保证窗口关闭回调可访问到正在运行的引擎 */
private var engineRef: AudioEngine? = null

@androidx.compose.runtime.Composable
private fun App() {
    val scope = rememberCoroutineScope()
    val engine = remember { AudioEngine().also { engineRef = it } }
    var isRecording by remember { mutableStateOf(false) }
    var gainDb by remember { mutableDoubleStateOf(0.0) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
                        engine = engine,
                        isRecording = isRecording,
                        gainDb = gainDb,
                        onGainChange = {
                            gainDb = it
                            engine.gainDb = it
                        },
                        onRecordStart = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { engine.start() }
                                        .onSuccess { isRecording = true }
                                        .onFailure {
                                            System.err.println("麦克风启动失败: ${it.message}")
                                            it.printStackTrace()
                                        }
                                }
                            }
                        },
                        onRecordStop = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { engine.stop() }
                                    isRecording = false
                                }
                            }
                        }
                    )
                    1 -> AboutPage(versionName = appVersion())
                }
            }
        }
    }
}

private fun appVersion(): String =
    runCatching {
        AudioEngine::class.java.`package`?.implementationVersion
    }.getOrNull() ?: APP_VERSION
