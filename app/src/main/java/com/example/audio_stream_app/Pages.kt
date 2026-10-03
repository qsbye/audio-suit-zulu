package com.example.audio_stream_app

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio_stream_app.ui.theme.CanadianLake
import com.example.audio_stream_app.ui.theme.TerraCotta

private val PanelTint = Color(0x14836539)

@Composable
fun LoudspeakerPage(
    isRecording: Boolean,
    volume: Int,
    maxVolume: Int,
    gainDb: Double,
    aecEnabled: Boolean,
    howlingEnabled: Boolean,
    waveformController: WaveformController,
    onGainChange: (Double) -> Unit,
    onAecEnabledChange: (Boolean) -> Unit,
    onHowlingEnabledChange: (Boolean) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val muted = volume == 0
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        // 不使用 Button，避免其内部 clickable 与按住手势竞争；
        // 手动管理按压状态与水波纹，保证按下即开始、松开/取消即停止
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
            text = "音量: $volume / $maxVolume",
            fontSize = 18.sp,
            color = if (muted) TerraCotta else MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = if (maxVolume == 0) 0f else volume.toFloat() / maxVolume,
            modifier = Modifier.width(237.dp)
        )

        if (muted) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "⚠ 已静音,听不到扩音声音",
                fontSize = 14.sp,
                color = TerraCotta,
                textAlign = TextAlign.Center
            )
        }

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
            controller = waveformController,
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

        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(96.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_name),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "版本: $versionName",
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

private const val GAIN_RANGE_DB = 20
