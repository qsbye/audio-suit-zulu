package com.example.audio_stream_app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.audio_stream_app.desktop.ui.theme.CanadianLake
import com.example.audio_stream_app.desktop.ui.theme.EarthGray
import com.example.audio_stream_app.desktop.ui.theme.Khaki
import com.example.audio_stream_app.desktop.ui.theme.OliveGreen
import kotlin.math.max
import kotlin.math.min

private val RawWaveColor = EarthGray
private val ProcessedWaveColor = OliveGreen
private val AntiWaveColor = CanadianLake
private val GridColor = Khaki

@Composable
fun Waveform(
    controller: WaveformController,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        // 在绘制阶段订阅 version，音频线程写入时节流触发重绘，无需重组
        @Suppress("UNUSED_VARIABLE")
        val version = controller.version

        val midY = size.height / 2f
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        drawLine(GridColor, Offset(0f, midY), Offset(size.width, midY), 2f, pathEffect = dashEffect)
        drawLine(GridColor, Offset(0f, 0f), Offset(size.width, 0f), 2f, pathEffect = dashEffect)
        drawLine(
            GridColor, Offset(0f, size.height), Offset(size.width, size.height),
            2f, pathEffect = dashEffect
        )

        val (raw, processed) = controller.snapshot()
        drawWave(raw, RawWaveColor)
        drawWave(processed, ProcessedWaveColor)
    }
}

private fun DrawScope.drawWave(samples: List<Short>, color: Color) {
    val total = samples.size
    if (total < 2) return
    val stride = max(1, total / MAX_DRAW_POINTS)
    val n = (total + stride - 1) / stride

    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val midY = size.height / 2f
    val amplitude = size.height / 2f * 0.9f
    for (i in 0 until n) {
        xs[i] = i * size.width / (n - 1)
        ys[i] = midY - samples[i * stride] / MAX_SAMPLE * amplitude
    }

    val path = Path()
    path.moveTo(xs[0], ys[0])
    for (i in 0 until n - 1) {
        val p0 = max(i - 1, 0)
        val p3 = min(i + 2, n - 1)
        val c1x = xs[i] + (xs[i + 1] - xs[p0]) / 6f
        val c1y = ys[i] + (ys[i + 1] - ys[p0]) / 6f
        val c2x = xs[i + 1] - (xs[p3] - xs[i]) / 6f
        val c2y = ys[i + 1] - (ys[p3] - ys[i]) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
    }
    drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
}

/**
 * 双路波形绘制（消音器页）：灰=麦克风、湖蓝=反相波。
 */
@Composable
fun DualWaveform(
    controller: DualWaveformController,
    modifier: Modifier = Modifier,
    colorA: Color = RawWaveColor,
    colorB: Color = AntiWaveColor
) {
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val version = controller.version

        val midY = size.height / 2f
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        drawLine(GridColor, Offset(0f, midY), Offset(size.width, midY), 2f, pathEffect = dashEffect)
        drawLine(GridColor, Offset(0f, 0f), Offset(size.width, 0f), 2f, pathEffect = dashEffect)
        drawLine(
            GridColor, Offset(0f, size.height), Offset(size.width, size.height),
            2f, pathEffect = dashEffect
        )

        val (a, b) = controller.snapshot()
        drawWave(a, colorA)
        drawWave(b, colorB)
    }
}

private const val MAX_DRAW_POINTS = 360
private const val MAX_SAMPLE = 32768f
