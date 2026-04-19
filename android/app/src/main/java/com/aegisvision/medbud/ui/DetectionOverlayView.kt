package com.aegisvision.medbud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.aegisvision.medbud.perception.Detection

/**
 * Transparent overlay view that draws bounding boxes for the current VLM
 * detections on top of the video [SurfaceViewRenderer]. Coordinates on
 * the [Detection] entries are normalized [0,1] in the source frame; we
 * scale them to this view's bounds.
 *
 * Assumes the video underneath fills the view. If the renderer is
 * letterboxing, the boxes will be off by the letterbox offset — fine for
 * a demo, not good for production.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    @Volatile
    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0xFF, 0x2E, 0x2E)
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(0xCC, 0xFF, 0x2E, 0x2E)
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setDetections(list: List<Detection>) {
        detections = list
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = detections
        if (snap.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bounds = Rect()
        for (d in snap) {
            val x = (d.x * w).toFloat()
            val y = (d.y * h).toFloat()
            val boxW = (d.w * w).toFloat()
            val boxH = (d.h * h).toFloat()
            canvas.drawRect(x, y, x + boxW, y + boxH, boxPaint)

            val label = if (d.severity.isNotEmpty()) "${d.label} • ${d.severity}" else d.label
            labelTextPaint.getTextBounds(label, 0, label.length, bounds)
            val padding = 6f
            val bgTop = (y - bounds.height() - padding * 2).coerceAtLeast(0f)
            val bgBottom = bgTop + bounds.height() + padding * 2
            canvas.drawRect(x, bgTop, x + bounds.width() + padding * 2, bgBottom, labelBgPaint)
            canvas.drawText(label, x + padding, bgBottom - padding, labelTextPaint)
        }
    }
}
