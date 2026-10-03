package com.example.audio_stream_app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio_stream_app.desktop.ui.theme.CanadianLake
import com.example.audio_stream_app.desktop.ui.theme.TerraCotta
import kotlinx.coroutines.isActive
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape

private val PanelTint = Color(0x14836539)

@Composable
fun LoudspeakerPage(
    engine: AudioEngine,
    isRecording: Boolean,
    gainDb: Double,
    aecEnabled: Boolean,
    howlingEnabled: Boolean,
    onGainChange: (Double) -> Unit,
    onAecEnabledChange: (Boolean) -> Unit,
    onHowlingEnabledChange: (Boolean) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 桌面端没有系统媒体音量概念，改为实时显示麦克风输入电平
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isRecording) {
        while (isActive) {
            level = if (isRecording) engine.level01 else 0f
            kotlinx.coroutines.delay(100)
        }
    }
    val levelPercent = (level * 100).roundToInt()

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(width = 237.dp, height = 136.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    if (isRecording) TerraCotta else MaterialTheme.colorScheme.primary
                )
                .indication(interactionSource, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { pressOffset ->
                            val press = PressInteraction.Press(pressOffset)
                            interactionSource.emit(press)
                            onRecordStart()
                            val released = tryAwaitRelease()
                            interactionSource.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press)
                            )
                            onRecordStop()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isRecording) "Recording..." else "Record",
                fontSize = 18.sp,
                color = Color.White
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "输入电平: $levelPercent%",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = level,
            modifier = Modifier.width(237.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = KhakiTrack
        )

        Spacer(Modifier.height(24.dp))

        val gainInt = gainDb.toInt()
        Text(
            text = "增益: ${if (gainInt >= 0) "+" else ""}$gainInt dB",
            fontSize = 18.sp
        )

        Spacer(Modifier.height(8.dp))

        Slider(
            value = (gainDb + GAIN_RANGE_DB).toFloat(),
            onValueChange = { onGainChange((it.toInt() - GAIN_RANGE_DB).toDouble()) },
            valueRange = 0f..(GAIN_RANGE_DB * 2).toFloat(),
            steps = GAIN_RANGE_DB * 2 - 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "左滑降低音量(减轻回声)  右滑扩大音量",
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // DSP 开关：回声消除与防啸叫，可随时勾选/取消
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = aecEnabled,
                    onCheckedChange = onAecEnabledChange
                )
                Text(text = "回声消除", fontSize = 14.sp)
            }
            Spacer(Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = howlingEnabled,
                    onCheckedChange = onHowlingEnabledChange
                )
                Text(text = "防啸叫", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        Waveform(
            controller = engine.waveform,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(PanelTint)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "灰:原始波形  绿:处理后波形",
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

private val KhakiTrack = Color(0xFFD9C8B4)

@Composable
fun AboutPage(
    versionName: String,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val linkUrl = "https://github.com/qsbye/audio-suit-zulu/tree/android"
    val linkText = buildAnnotatedString {
        pushStyle(SpanStyle(color = CanadianLake))
        append(linkUrl)
        pop()
        addStringAnnotation(tag = "URL", annotation = linkUrl, start = 0, end = linkUrl.length)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        val icon = remember { loadAppIcon() }
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = "AudioSuitZulu",
                modifier = Modifier.size(96.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "AudioSuitZulu音函",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "版本: $versionName (macOS)",
            fontSize = 14.sp
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "开源地址",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        ClickableText(
            text = linkText,
            modifier = Modifier.padding(8.dp),
            style = TextStyle(fontSize = 14.sp),
            onClick = { offset ->
                linkText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()
                    ?.let { uriHandler.openUri(it.item) }
            }
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "开源协议: MIT License",
            fontSize = 14.sp
        )
    }
}

private fun loadAppIcon(): ImageBitmap? = runCatching {
    val stream = object {}.javaClass.getResourceAsStream("/waveform.png") ?: return null
    stream.use { ImageIO.read(it)?.toComposeImageBitmap() }
}.getOrNull()

private const val GAIN_RANGE_DB = 20
