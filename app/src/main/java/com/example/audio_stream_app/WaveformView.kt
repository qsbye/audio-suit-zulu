package com.example.audio_stream_app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_raw)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val processedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_processed)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val rawBuffer = ArrayDeque<Short>(CAPACITY)
    private val processedBuffer = ArrayDeque<Short>(CAPACITY)
    private var lastInvalidateAt = 0L

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
            postInvalidate()
        }
    }

    fun clear() {
        synchronized(rawBuffer) { rawBuffer.clear() }
        synchronized(processedBuffer) { processedBuffer.clear() }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val midY = height / 2f
        canvas.drawLine(0f, midY, width.toFloat(), midY, gridPaint)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, gridPaint)
        canvas.drawLine(0f, height.toFloat(), width.toFloat(), height.toFloat(), gridPaint)

        val raw: List<Short>
        val processed: List<Short>
        synchronized(rawBuffer) { raw = rawBuffer.toList() }
        synchronized(processedBuffer) { processed = processedBuffer.toList() }

        drawWave(canvas, raw, rawPaint)
        drawWave(canvas, processed, processedPaint)
    }

    private fun drawWave(canvas: Canvas, samples: List<Short>, paint: Paint) {
        val total = samples.size
        if (total < 2) return
        val stride = max(1, total / MAX_DRAW_POINTS)
        val n = (total + stride - 1) / stride

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val midY = height / 2f
        val amplitude = height / 2f * 0.9f
        for (i in 0 until n) {
            xs[i] = i * width.toFloat() / (n - 1)
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
        canvas.drawPath(path, paint)
    }

    companion object {
        private const val CAPACITY = 8192
        private const val MAX_DRAW_POINTS = 360
        private const val MAX_SAMPLE = 32768f
        private const val REFRESH_INTERVAL_MS = 33L
    }
}
