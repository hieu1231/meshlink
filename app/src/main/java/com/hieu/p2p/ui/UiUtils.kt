package com.hieu.p2p.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.format.DateUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.data.MeshRuntime
import com.hieu.p2p.mesh.MeshStatus
import com.hieu.p2p.mesh.core.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val Fragment.mesh: MeshRuntime get() = (requireActivity().application as P2pApp).mesh

fun <T> LifecycleOwner.collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } }
    }
}

/** Avatar gradients, light top to deeper base; the base is the node's colour everywhere else. */
private val AVATAR_TOPS = longArrayOf(
    0xFFFF8E86, 0xFFFFC266, 0xFFB9A2FF, 0xFFA2DE7A, 0xFF6FE3D4, 0xFF7CCBFF, 0xFFF7A2D6, 0xFFA9B4C2,
).map { it.toInt() }
internal val AVATAR_PALETTE = longArrayOf(
    0xFFE8524A, 0xFFF2892E, 0xFF7A63E6, 0xFF4DB34A, 0xFF2CB5A6, 0xFF2F8CEB, 0xFFDB5BAA, 0xFF6C7A8C,
).map { it.toInt() }

private fun avatarIndex(id: NodeId) = ((id.raw ushr 1) % AVATAR_PALETTE.size).toInt()

private fun gradientOval(top: Int, bottom: Int) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply { shape = GradientDrawable.OVAL }

/** Initial-letter avatar on a gradient that is stable per node. */
fun TextView.bindAvatar(name: String, id: NodeId) {
    text = name.trim().firstOrNull()?.uppercase() ?: "?"
    val i = avatarIndex(id)
    backgroundTintList = null
    background = gradientOval(AVATAR_TOPS[i], AVATAR_PALETTE[i])
}

/** The public channel's avatar: accent gradient, with a globe drawn on top by the layout. */
fun TextView.bindChannelAvatar() {
    text = ""
    backgroundTintList = null
    background = gradientOval(0xFF6AC8FF.toInt(), ContextCompat.getColor(context, R.color.accent))
}

/** A node's colour for text (sender names): deeper in light mode, lighter in dark mode, so it reads on bubbles. */
fun avatarTextColor(context: Context, id: NodeId): Int {
    val i = avatarIndex(id)
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return if (night) AVATAR_TOPS[i] else ColorUtils.blendARGB(AVATAR_PALETTE[i], 0xFF000000.toInt(), 0.18f)
}

/**
 * A soft shadow under a floating translucent capsule (tab bar). A see-through background reports
 * a transparent outline, which casts no shadow, so the outline is given here instead.
 */
fun View.capsuleShadow() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            outline.alpha = 0.6f
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineAmbientShadowColor = 0x40000000
        outlineSpotShadowColor = 0x40000000
    }
}

/** Hairline between rows, starting under the row's text as in iOS lists; none after the last row. */
fun insetDivider(context: Context, startDp: Int) =
    MaterialDividerItemDecoration(context, LinearLayoutManager.VERTICAL).apply {
        val density = context.resources.displayMetrics.density
        dividerColor = ContextCompat.getColor(context, R.color.separator)
        dividerThickness = (0.5f * density).roundToInt().coerceAtLeast(1)
        dividerInsetStart = (startDp * density).roundToInt()
        isLastItemDecorated = false
    }

/** One-line connection state with a coloured dot, for screen headers. */
fun TextView.bindStatusLine(s: MeshStatus) {
    val (res, color) = when {
        !s.running -> context.getString(R.string.toolbar_offline) to R.color.gray
        s.links == 0 -> context.getString(R.string.toolbar_searching) to R.color.orange
        else -> context.getString(R.string.toolbar_online, s.links, s.routes.size) to R.color.green
    }
    text = res
    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(context, color)))
}

object Formats {
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTime = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun time(ms: Long): String =
        if (DateUtils.isToday(ms)) time.format(Date(ms)) else dateTime.format(Date(ms))

    fun logTime(ms: Long): String = logTime.format(Date(ms))

    /** "8 m", "120 m", "1,4 km" — rounded to what GPS can honestly resolve. */
    fun distance(metres: Double): String = when {
        metres < 10 -> "${metres.roundToInt()} m"
        metres < 1000 -> "${(metres / 5).roundToInt() * 5} m"
        metres < 10_000 -> String.format(Locale("vi"), "%.1f km", metres / 1000)
        else -> "${(metres / 1000).roundToInt()} km"
    }

    /** "0:07", "1:42" */
    fun duration(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** "640 KB", "1,8 MB" */
    fun bytes(n: Int): String =
        if (n < 1024 * 1024) "${(n + 1023) / 1024} KB" else String.format(Locale("vi"), "%.1f MB", n / (1024.0 * 1024.0))

    /** "vừa xong", "2 phút trước", ... */
    fun ago(context: Context, then: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            s < 15 -> context.getString(R.string.ago_now)
            s < 60 -> context.getString(R.string.ago_seconds, s)
            s < 3600 -> context.getString(R.string.ago_minutes, s / 60)
            else -> context.getString(R.string.ago_hours, s / 3600)
        }
    }

    private val compassNames = intArrayOf(
        R.string.dir_n, R.string.dir_ne, R.string.dir_e, R.string.dir_se,
        R.string.dir_s, R.string.dir_sw, R.string.dir_w, R.string.dir_nw,
    )

    /** 8-point compass name for an absolute bearing. */
    fun compass(context: Context, bearing: Double): String =
        context.getString(compassNames[(((bearing % 360) + 360 + 22.5) / 45).toInt() % 8])

    /** Where something is relative to where the phone points: bearing minus heading. */
    fun relative(context: Context, relativeDeg: Double): String {
        val d = ((relativeDeg % 360) + 360) % 360
        return context.getString(
            when {
                d < 15 || d > 345 -> R.string.rel_ahead
                d < 60 -> R.string.rel_ahead_right
                d < 120 -> R.string.rel_right
                d < 165 -> R.string.rel_behind_right
                d <= 195 -> R.string.rel_behind
                d < 240 -> R.string.rel_behind_left
                d < 300 -> R.string.rel_left
                else -> R.string.rel_ahead_left
            }
        )
    }
}
