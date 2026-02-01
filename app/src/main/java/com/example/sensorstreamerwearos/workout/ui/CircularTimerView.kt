package com.example.sensorstreamerwearos.workout.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class CircularTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 1.0f // 0.0 to 1.0
    private var strokeWidthPx: Float = 20f
    private val paintBackground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintForeground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        // Defaults
        strokeWidthPx = 6f * resources.displayMetrics.density // Thinner elegant stroke
        val bgStrokeWidthPx = 4f * resources.displayMetrics.density // Even thinner background
        
        paintBackground.style = Paint.Style.STROKE
        paintBackground.strokeWidth = bgStrokeWidthPx
        paintBackground.color = Color.parseColor("#1AFFFFFF") // Even more subtle gray (10% opacity)
        paintBackground.strokeCap = Paint.Cap.ROUND

        paintForeground.style = Paint.Style.STROKE
        paintForeground.strokeWidth = strokeWidthPx
        paintForeground.color = Color.parseColor("#00E5FF") // High contrast Cyan/Blue
        paintForeground.strokeCap = Paint.Cap.ROUND
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setForegroundColor(color: Int) {
        paintForeground.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = strokeWidthPx / 2f
        rect.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background ring (full circle)
        canvas.drawOval(rect, paintBackground)

        // Draw progress arc
        // Start from top (-90 degrees)
        // Sweep angle based on progress
        val sweepAngle = 360f * progress
        canvas.drawArc(rect, -90f, sweepAngle, false, paintForeground)
    }
}
