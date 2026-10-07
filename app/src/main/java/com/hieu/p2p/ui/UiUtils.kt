package com.hieu.p2p.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.data.MeshRuntime
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

/** Muted field-gear tones: distinct from each other, never competing with signal yellow or SOS red. */
internal val AVATAR_PALETTE = longArrayOf(
    0xFF2E5E8C, 0xFF7A4E9C, 0xFFB4532A, 0xFF2E7D6B, 0xFF8C6D1F, 0xFF5C6B7A, 0xFFA23E5C, 0xFF3F7A3A,
).map { it.toInt() }

/** Initial-letter avatar with a colour that is stable per node. */
fun TextView.bindAvatar(name: String, id: NodeId) {
    text = name.trim().firstOrNull()?.uppercase() ?: "?"
    backgroundTintList = ColorStateList.valueOf(MeshRadarView.avatarColor(id))
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
