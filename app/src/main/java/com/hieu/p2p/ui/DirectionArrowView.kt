package com.hieu.p2p.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.hieu.p2p.R
import kotlin.math.abs
import kotlin.math.min

/**
 * One big arrow that points at the person you are looking for. The disc turns green when you are
 * facing them (as in precision-finding UIs), so "which way?" is readable at a glance, even running.
 */
class DirectionArrowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Called once each time the arrow swings into line (for a haptic tick). */
    var onAligned: () -> Unit = {}

    private val dp = resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.accent)
    private val track = ContextCompat.getColor(context, R.color.fill_strong)
    private val alignedDisc = ContextCompat.getColor(context, R.color.green)
    private val alignedArrow = ContextCompat.getColor(context, R.color.white)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()

    private var shown = 0f
    private var target: Float? = null
    private var isAligned = false
    private var animator: ValueAnimator? = null

    /** Angle relative to the top of the phone, degrees clockwise; null hides the arrow. */
    fun setAngle(angle: Float?) {
        target = angle
        if (angle == null) {
            invalidate()
            return
        }
        // Take the short way round.
        var delta = (angle - shown) % 360
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        animator?.cancel()
        val from = shown
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = from + delta * (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
        val normalized = ((angle % 360) + 540) % 360 - 180 // -180..180, 0 = straight ahead
        val nowAligned = abs(normalized) < ALIGNED_DEG
        if (nowAligned && !isAligned) onAligned()
        isAligned = nowAligned
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 4 * dp
        paint.style = Paint.Style.FILL
        paint.color = if (target != null && isAligned) alignedDisc else track
        canvas.drawCircle(cx, cy, r, paint)
        if (target == null) return

        canvas.save()
        canvas.rotate(shown, cx, cy)
        val h = r * 0.62f
        val w = r * 0.42f
        arrow.reset()
        arrow.moveTo(cx, cy - h)
        arrow.lineTo(cx + w, cy + h * 0.55f)
        arrow.lineTo(cx, cy + h * 0.25f)
        arrow.lineTo(cx - w, cy + h * 0.55f)
        arrow.close()
        paint.color = if (isAligned) alignedArrow else ink
        canvas.drawPath(arrow, paint)
        canvas.restore()
    }

    companion object {
        const val ALIGNED_DEG = 12f
    }
}
