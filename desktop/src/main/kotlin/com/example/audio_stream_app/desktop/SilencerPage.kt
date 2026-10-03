package com.example.audio_stream_app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio_stream_app.desktop.dsp.anc.PeriodDetector
import com.example.audio_stream_app.desktop.ui.theme.CanadianLake
import com.example.audio_stream_app.desktop.ui.theme.EarthGray
import com.example.audio_stream_app.desktop.ui.theme.Khaki
import com.example.audio_stream_app.desktop.ui.theme.OliveGreen
import com.example.audio_stream_app.desktop.ui.theme.TerraCotta

private val PanelTint = Color(0x14836539)

/**
 * 消音器页（需求 FR-1 ~ FR-9）：录制 → 端上训练 → 回路标定 → 实时消音，
 * 四步纵向排布，状态驱动控件使能。
 */
@Composable
fun SilencerPage(
    engine: SilencerEngine,
    modifier: Modifier = Modifier
) {
    val phase = engine.phase
    val busy = phase == SilencerEngine.Phase.TRAINING ||
        phase == SilencerEngine.Phase.CALIBRATING

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        StepHeader(phase)

        if (engine.notice.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = engine.notice,
                fontSize = 13.sp,
                color = TerraCotta
            )
        }

        // ---------- 步骤 1：录制 ----------
        Spacer(Modifier.height(12.dp))
        Section(title = "1. 录制规律噪音（2–6 秒）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = engine::toggleRecording,
                    enabled = !busy && phase != SilencerEngine.Phase.ACTIVE
                ) {
                    Text(
                        when (phase) {
                            SilencerEngine.Phase.RECORDING ->
                                "■ 停止录制 %.1fs".format(engine.recordMs / 1000f)
                            SilencerEngine.Phase.IDLE -> "开始录制噪音"
                            else -> "重新录制"
                        }
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = engine::togglePlayback,
                    enabled = !busy && phase != SilencerEngine.Phase.ACTIVE &&
                        (phase == SilencerEngine.Phase.RECORDED ||
                            phase == SilencerEngine.Phase.READY) &&
                        engine.recordMs > 0L
                ) {
                    Text(if (engine.playing) "停止试听" else "试听回放")
                }
            }

            if (phase == SilencerEngine.Phase.RECORDING) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "录制中 %.1fs / 6.0s".format(engine.recordMs / 1000f),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = engine.recordLevel.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(text = "请把手机麦克风靠近风扇、电机等持续声源", fontSize = 12.sp)
            } else if (engine.recordMs > 0L) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已录制 %.1f 秒%s".format(
                        engine.recordMs / 1000f,
                        if (engine.recordingEnough) "" else "（不足 2 秒，请重录）"
                    ),
                    fontSize = 13.sp,
                    color = if (engine.recordingEnough) MaterialTheme.colorScheme.onSurface
                    else TerraCotta
                )
            }
        }

        // ---------- 步骤 2：训练 ----------
        Spacer(Modifier.height(12.dp))
        Section(title = "2. 手机端训练消音网络") {
            if (engine.analyzing) {
                Text(text = "正在分析噪音规律性…", fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (engine.f0Hz > 0f) {
                Text(
                    text = "基频 %.1f Hz　规律性 %.2f".format(engine.f0Hz, engine.confidence),
                    fontSize = 14.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = engine::startTraining,
                    enabled = !busy && phase != SilencerEngine.Phase.ACTIVE &&
                        engine.recordingEnough && engine.f0Hz > 0f
                ) {
                    Text(
                        when {
                            engine.f0Hz > 0f &&
                                engine.confidence < PeriodDetector.CONFIDENCE_THRESHOLD ->
                                "仍要训练（规律性偏低）"
                            else -> "训练消音网络"
                        }
                    )
                }
                if (phase == SilencerEngine.Phase.TRAINING) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = engine::cancelTraining) {
                        Text("取消", color = TerraCotta)
                    }
                }
            }

            if (phase == SilencerEngine.Phase.TRAINING) {
                Spacer(Modifier.height(6.dp))
                val progress = if (engine.trainTotal > 0) {
                    engine.trainEpoch.toFloat() / engine.trainTotal
                } else 0f
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 %d/%d 轮　loss %s".format(
                        engine.trainEpoch,
                        engine.trainTotal,
                        fmtLoss(engine.trainLoss)
                    ),
                    fontSize = 12.sp
                )
            } else if (engine.trainFinalLoss > 0f &&
                (phase == SilencerEngine.Phase.READY || phase == SilencerEngine.Phase.ACTIVE)
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "loss：%s → %s，用时 %.1f 秒（纯端上神经网络）".format(
                        fmtLoss(engine.trainInitialLoss),
                        fmtLoss(engine.trainFinalLoss),
                        engine.trainElapsedMs / 1000f
                    ),
                    fontSize = 12.sp
                )
                val overlay = remember(phase, engine.f0Hz) { engine.overlaySamples() }
                if (overlay != null) {
                    Spacer(Modifier.height(8.dp))
                    OverlayPlot(
                        raw = overlay.first,
                        fit = overlay.second,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x0D836539))
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(text = "灰：录音波形　湖蓝：网络拟合", fontSize = 11.sp)
                }
            }
        }

        // ---------- 步骤 3：标定 ----------
        Spacer(Modifier.height(12.dp))
        Section(title = "3. 回路时延标定") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = engine::calibrate,
                    enabled = phase == SilencerEngine.Phase.READY
                ) {
                    Text(if (phase == SilencerEngine.Phase.CALIBRATING) "标定中…" else "开始标定")
                }
                if (phase == SilencerEngine.Phase.CALIBRATING) {
                    Spacer(Modifier.width(12.dp))
                    LinearProgressIndicator(modifier = Modifier.width(120.dp))
                }
            }
            if (engine.calibratedMs >= 0f) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "标定回路时延 %.1f ms，实际提前量 %.1f ms".format(
                        engine.calibratedMs, engine.totalLeadMs
                    ),
                    fontSize = 13.sp
                )
                val adjustEnabled = phase == SilencerEngine.Phase.READY ||
                    phase == SilencerEngine.Phase.ACTIVE
                Text(
                    text = "手动微调：%+d ms".format(engine.leadAdjustMs.toInt()),
                    fontSize = 13.sp
                )
                Slider(
                    value = engine.leadAdjustMs,
                    onValueChange = engine::adjustLead,
                    valueRange = -50f..50f,
                    steps = 99,
                    enabled = adjustEnabled
                )
                Text(
                    text = "消音不足或有余音时左右微调，实际提前量随之变化",
                    fontSize = 11.sp
                )
            } else if (phase == SilencerEngine.Phase.READY) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "标定后将播放一声短促提示音，请保持环境噪音持续",
                    fontSize = 12.sp
                )
            }
        }

        // ---------- 步骤 4：消音 ----------
        Spacer(Modifier.height(12.dp))
        Section(title = "4. 开启消音") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = phase == SilencerEngine.Phase.ACTIVE,
                    onCheckedChange = engine::setActive,
                    enabled = phase == SilencerEngine.Phase.READY ||
                        phase == SilencerEngine.Phase.ACTIVE
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (phase == SilencerEngine.Phase.ACTIVE) "消音运行中" else "开启消音",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(12.dp))
                if (phase == SilencerEngine.Phase.ACTIVE) {
                    LockBadge(locked = engine.locked)
                }
            }

            Text(
                text = "反噪输出上限：%d%%".format((engine.antiLevel * 100).toInt()),
                fontSize = 13.sp
            )
            Slider(
                value = engine.antiLevel * 100f,
                onValueChange = { engine.adjustAntiLevel(it / 100f) },
                valueRange = 10f..50f,
                steps = 7,
                enabled = phase == SilencerEngine.Phase.READY ||
                    phase == SilencerEngine.Phase.ACTIVE
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = engine.howlingGuard,
                    onCheckedChange = engine::setHowlingGuardEnabled,
                    enabled = phase == SilencerEngine.Phase.READY ||
                        phase == SilencerEngine.Phase.ACTIVE
                )
                Text(text = "防啸叫兜底陷波", fontSize = 14.sp)
            }

            if (phase == SilencerEngine.Phase.ACTIVE) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricText(
                        label = "实时基频",
                        value = "%.1f Hz".format(engine.instantF0)
                    )
                    MetricText(
                        label = "反噪增益",
                        value = "%.0f%%".format(engine.antiGain * 100)
                    )
                    MetricText(
                        label = "周期带能量",
                        value = if (engine.metricsValid)
                            "%+.1f dB".format(engine.bandDeltaDb) else "统计中…"
                    )
                }

                Spacer(Modifier.height(8.dp))
                DualWaveform(
                    controller = engine.waveform,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelTint)
                )
                Spacer(Modifier.height(2.dp))
                Text(text = "灰：麦克风原始波形　湖蓝：反相播放波", fontSize = 11.sp)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "开启后显示锁定状态、实时基频、反噪增益与麦克风处周期带能量变化",
                    fontSize = 12.sp
                )
            }
        }

        // ---------- 功能说明（FR-9） ----------
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelTint)
                .padding(12.dp)
        ) {
            Text(
                text = "说明：本功能仅对规律、持续的噪音（风扇、电机、变压器嗡鸣等）有效；" +
                    "人声、音乐等非周期声音会自动判定失锁并静音反波。物理静区位于手机自身" +
                    "麦克风处，请将手机尽量靠近目标位置。实验性功能，消音深度受手机扬声器" +
                    "与麦克风硬件限制。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepHeader(phase: SilencerEngine.Phase) {
    val current = when (phase) {
        SilencerEngine.Phase.IDLE,
        SilencerEngine.Phase.RECORDING -> 0
        SilencerEngine.Phase.RECORDED,
        SilencerEngine.Phase.TRAINING -> 1
        SilencerEngine.Phase.CALIBRATING,
        SilencerEngine.Phase.READY -> 2              // READY 后下一步是标定
        SilencerEngine.Phase.ACTIVE -> 3
    }
    val labels = listOf("录制", "训练", "标定", "消音")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == current
            Text(
                text = "${i + 1}. $label",
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else EarthGray
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelTint)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun LockBadge(locked: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (locked) OliveGreen else TerraCotta)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (locked) "● 已锁定" else "○ 搜索中",
            fontSize = 12.sp,
            color = Color.White
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MetricText(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(text = label, fontSize = 11.sp, color = EarthGray)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 训练结果叠加预览：录音（灰）与网络拟合（湖蓝），折线直连即可。 */
@Composable
private fun OverlayPlot(raw: FloatArray, fit: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val midY = size.height / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        drawLine(
            color = Khaki,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.5f,
            pathEffect = dash
        )
        // 麦克风信号通常远低于满量程，按两条曲线的共同峰值归一化再绘制
        var peak = 1e-9f
        for (v in raw) peak = maxOf(peak, kotlin.math.abs(v))
        for (v in fit) peak = maxOf(peak, kotlin.math.abs(v))
        drawSeries(raw, EarthGray, 3f, 1f / peak)
        drawSeries(fit, CanadianLake, 3f, 1f / peak)
    }
}

private fun DrawScope.drawSeries(values: FloatArray, color: Color, strokeWidth: Float, norm: Float = 1f) {
    if (values.size < 2) return
    val stride = maxOf(1, values.size / 360)
    val amp = size.height / 2f * 0.9f
    val path = Path()
    var started = false
    var i = 0
    while (i < values.size) {
        val x = i.toFloat() / (values.size - 1) * size.width
        val y = size.height / 2f - values[i] * norm * amp
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        i += stride
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}

private fun fmtLoss(v: Float): String =
    if (v <= 0f || v.isNaN()) "—"
    else if (v < 0.01f) "%.2e".format(v)
    else "%.4f".format(v)
