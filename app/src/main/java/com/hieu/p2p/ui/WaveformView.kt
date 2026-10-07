package com.hieu.p2p.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Voice-note waveform: one rounded bar per level byte, bars before [progress] in the played colour. */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var levels: ByteArray = ByteArray(0)

    var playedColor = 0
    var idleColor = 0

    /** 0..1 */
    var progress = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setLevels(bytes: ByteArray?) {
        levels = bytes ?: ByteArray(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val count = levels.size.takeIf { it > 0 } ?: 32
        val gap = 2f * dp
        val w = ((width - gap * (count - 1)) / count).coerceAtLeast(1.5f * dp)
        val minH = 3f * dp
        for (i in 0 until count) {
            val level = if (levels.isEmpty()) 0.15f else (levels[i].toInt() and 0xFF) / 255f
            val h = (minH + (height - minH) * level)
            val x = i * (w + gap)
            bar.set(x, (height - h) / 2, x + w, (height + h) / 2)
            paint.color = if ((i + 0.5f) / count <= progress) playedColor else idleColor
            canvas.drawRoundRect(bar, w / 2, w / 2, paint)
        }
    }
}
