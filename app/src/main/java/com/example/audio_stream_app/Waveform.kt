package com.example.audio_stream_app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.audio_stream_app.ui.theme.EarthGray
import com.example.audio_stream_app.ui.theme.Khaki
import com.example.audio_stream_app.ui.theme.OliveGreen
import kotlin.math.max
import kotlin.math.min

private val RawWaveColor = EarthGray
private val ProcessedWaveColor = OliveGreen
private val GridColor = Khaki

/**
 * 波形数据缓冲区，音频线程写入、Compose 绘制线程读取。
 */
class WaveformController {
    private val rawBuffer = ArrayDeque<Short>(CAPACITY)
    private val processedBuffer = ArrayDeque<Short>(CAPACITY)
    private var lastInvalidateAt = 0L

    /**
     * 每次节流刷新自增，在绘制阶段读取以触发重绘。
     */
    var version by mutableIntStateOf(0)
        private set

    fun addSamples(raw: ShortArray, processed: ShortArray, count: Int) {
        synchronized(rawBuffer) {
            for (i in 0 until count) {
                if (rawBuffer.size >= CAPACITY) rawBuffer.removeFirst()
                rawBuffer.addLast(raw[i])
            }
        }
        synchronized(processedBuffer) {
            for (i in 0 until count) {
                if (processedBuffer.size >= CAPACITY) processedBuffer.removeFirst()
                processedBuffer.addLast(processed[i])
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastInvalidateAt >= REFRESH_INTERVAL_MS) {
            lastInvalidateAt = now
            version++
        }
    }

    fun clear() {
        synchronized(rawBuffer) { rawBuffer.clear() }
        synchronized(processedBuffer) { processedBuffer.clear() }
        version++
    }

    fun snapshot(): Pair<List<Short>, List<Short>> {
        val raw: List<Short>
        val processed: List<Short>
        synchronized(rawBuffer) { raw = rawBuffer.toList() }
        synchronized(processedBuffer) { processed = processedBuffer.toList() }
        return raw to processed
    }

    private companion object {
        const val CAPACITY = 8192
        const val REFRESH_INTERVAL_MS = 33L
    }
}

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
        drawLine(
            color = GridColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 2f,
            pathEffect = dashEffect
        )
        drawLine(
            color = GridColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2f,
            pathEffect = dashEffect
        )
        drawLine(
            color = GridColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f,
            pathEffect = dashEffect
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
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 5f, cap = StrokeCap.Round)
    )
}

private const val MAX_DRAW_POINTS = 360
private const val MAX_SAMPLE = 32768f
