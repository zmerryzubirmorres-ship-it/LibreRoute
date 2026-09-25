package io.github.p1neapplexpress.openflux.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Zero-allocation 30-sample rolling sparkline micro-chart for real-time
 * bandwidth visualization (rx = download, tx = upload).
 */
class SpeedSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxSamples = 30
    private val rxHistory = LongArray(maxSamples)
    private val txHistory = LongArray(maxSamples)
    private var writeIndex = 0
    private val rxPath = Path()
    private val txPath = Path()

    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2563EB.toInt() // M3 Primary Blue (Download)
    }

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF16A34A.toInt() // Success Green (Upload)
    }

    fun addSample(rxBytesSec: Long, txBytesSec: Long) {
        rxHistory[writeIndex] = rxBytesSec
        txHistory[writeIndex] = txBytesSec
        writeIndex = (writeIndex + 1) % maxSamples
        invalidate()
    }

    fun reset() {
        rxHistory.fill(0L)
        txHistory.fill(0L)
        writeIndex = 0
        rxPath.reset()
        txPath.reset()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (32 * resources.displayMetrics.density).toInt()
        val desiredWidth = (200 * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        var maxVal = 1024L * 10L // Minimum 10 KB/s ceiling
        for (i in 0 until maxSamples) {
            if (rxHistory[i] > maxVal) maxVal = rxHistory[i]
            if (txHistory[i] > maxVal) maxVal = txHistory[i]
        }

        buildPath(rxPath, rxHistory, maxVal, w, h)
        buildPath(txPath, txHistory, maxVal, w, h)

        canvas.drawPath(rxPath, rxPaint)
        canvas.drawPath(txPath, txPaint)
    }

    private fun buildPath(path: Path, history: LongArray, maxVal: Long, w: Float, h: Float) {
        path.reset()
        val pad = rxPaint.strokeWidth
        val availW = (w - 2f * pad).coerceAtLeast(1f)
        val availH = (h - 2f * pad).coerceAtLeast(1f)
        val stepX = availW / (maxSamples - 1)
        for (i in 0 until maxSamples) {
            val idx = (writeIndex + i) % maxSamples
            val x = pad + i * stepX
            val y = (h - pad) - (history[idx].toFloat() / maxVal.toFloat() * (availH * 0.90f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }
}
