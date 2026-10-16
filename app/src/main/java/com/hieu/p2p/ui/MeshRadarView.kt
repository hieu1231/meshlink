package com.hieu.p2p.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.hieu.p2p.R
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.routing.RouteInfo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The mesh around you.
 *
 * - With GPS on both ends, phones sit at their real distance and direction: rings are in metres
 *   (auto-scaled to whoever is farthest), and with a compass the whole map turns so "up" is where
 *   you are pointing the phone, like holding a paper map in the right orientation.
 * - Phones that share no position sit on an outer dashed band, so they are visible but clearly
 *   not placed.
 * - With no GPS at all it falls back to rings by hop count.
 */
class MeshRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Placed(
        val route: RouteInfo, val x: Float, val y: Float, val placed: Boolean, val distance: Double?,
        /** Closer than GPS can resolve: shown hugging you, with no direction claimed. */
        val near: Boolean = false,
    )

    var onNodeClick: ((RouteInfo) -> Unit)? = null

    private var routes: List<RouteInfo> = emptyList()
    private var running = false
    private var myInitial = "?"
    private var me: GeoPoint? = null
    private var heading: Float? = null
    private var selected: NodeId? = null
    private var nameOf: (RouteInfo) -> String = { it.name.ifBlank { it.dest.short } }
    private var placed: List<Placed> = emptyList()
    private var ringStep = 0.0
    private var geoMode = false

    private val dp = resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.label)
    private val inkSoft = ContextCompat.getColor(context, R.color.label_secondary)
    private val ringColor = ContextCompat.getColor(context, R.color.separator)
    /** The radar sits on a card; labels get a backing of the same colour. */
    private val surface = ContextCompat.getColor(context, R.color.card)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val onAccent = ContextCompat.getColor(context, R.color.on_accent)
    private val offColor = ContextCompat.getColor(context, R.color.gray)
    private val sosRed = ContextCompat.getColor(context, R.color.red)
    private val bold = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_bold) ?: Typeface.DEFAULT_BOLD
    private val semibold = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold) ?: Typeface.DEFAULT_BOLD
    private val medium = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_medium) ?: Typeface.DEFAULT

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = ringColor }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = ringColor
        pathEffect = DashPathEffect(floatArrayOf(3f * dp, 5f * dp), 0f)
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND }
    private val dash = DashPathEffect(floatArrayOf(4f * dp, 5f * dp), 0f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = bold }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = semibold; textSize = 11.5f * dp; color = ink }
    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = medium; textSize = 10.5f * dp; color = inkSoft }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 10f * dp; color = inkSoft }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = bold; textSize = 11f * dp; color = sosRed }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cone = Path()
    private val oval = RectF()

    private var sweep = 0f
    private val sweeper = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    fun render(
        routes: List<RouteInfo>,
        running: Boolean,
        myName: String,
        myPosition: GeoPoint?,
        heading: Float?,
        selected: NodeId?,
        nameOf: (RouteInfo) -> String,
    ) {
        this.routes = routes
        this.running = running
        this.myInitial = myName.trim().firstOrNull()?.uppercase() ?: "?"
        this.me = myPosition
        this.heading = heading
        this.selected = selected
        this.nameOf = nameOf
        contentDescription = when {
            !running -> context.getString(R.string.radar_cd_off)
            routes.isEmpty() -> context.getString(R.string.radar_cd_searching)
            else -> context.getString(R.string.radar_cd_on, routes.count { it.isNeighbor }, routes.size)
        }
        layoutNodes()
        updateSweep()
        invalidate()
    }

    /** Heading changes many times a second; skip the rest of the work. */
    fun setHeading(heading: Float?) {
        if (heading == this.heading) return
        this.heading = heading
        layoutNodes()
        invalidate()
    }

    private fun updateSweep() {
        val animationsOn = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        val searching = running && routes.isEmpty() && isAttachedToWindow && animationsOn
        if (searching && !sweeper.isStarted) sweeper.start()
        if (!searching && sweeper.isStarted) {
            sweeper.cancel()
            sweep = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSweep()
    }

    override fun onDetachedFromWindow() {
        sweeper.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutNodes()

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val outer get() = min(width, height) / 2f - 24f * dp
    /** Placed phones live inside this; the band for unplaced ones sits just outside. */
    private val inner get() = outer - 30f * dp
    private val meR get() = 24f * dp
    private val nodeR get() = 17f * dp

    private fun screen(angleDeg: Double, radius: Float): Pair<Float, Float> {
        val a = Math.toRadians(angleDeg)
        return (cx + radius * sin(a)).toFloat() to (cy - radius * cos(a)).toFloat()
    }

    private fun layoutNodes() {
        if (width == 0) return
        val origin = me
        val located = if (origin == null) emptyList() else routes.filter { it.position != null }
        geoMode = located.isNotEmpty()
        val rotate = heading ?: 0f
        val result = mutableListOf<Placed>()

        if (geoMode) {
            val reliable = located.filter { origin!!.directionReliableTo(it.position!!) }
            val near = located - reliable.toSet()
            val far = reliable.maxOfOrNull { origin!!.distanceTo(it.position!!) }
                ?: near.maxOf { origin!!.uncertaintyTo(it.position!!).toDouble() }
            ringStep = niceStep(far / 3)
            spreadOnRing(near, meR + nodeR + 12f * dp, result, offsetTurns = 0.62, near = true)
            val geo = reliable.map { r ->
                val d = origin!!.distanceTo(r.position!!)
                val (x, y) = screen(origin.bearingTo(r.position!!) - rotate, geoRadius(d))
                Placed(r, x, y, placed = true, distance = d)
            }
            result += separate(geo)
            spreadOnRing(routes.filter { it !in located }, outer, result)
        } else {
            routes.groupBy { minOf(it.hops, 3) }.forEach { (ring, members) ->
                spreadOnRing(members, inner * ring / 3f, result, offsetTurns = ring * 0.45)
            }
        }
        placed = result
    }

    /**
     * Compressed radial scale (power 0.7): the near field, where you will actually walk to someone,
     * gets more room; far phones still fit. Rings use the same mapping, so ring labels stay true.
     */
    private fun geoRadius(metres: Double): Float {
        val range = ringStep * 3
        val minR = meR + nodeR + 8f * dp
        return (minR + (inner - minR) * Math.pow((metres / range).coerceIn(0.0, 1.0), 0.7)).toFloat()
    }

    /**
     * Nudges overlapping phones apart sideways (keeping their distance from you), so two people
     * standing together both stay tappable and labelled. Small moves only: direction stays honest.
     */
    private fun separate(nodes: List<Placed>): List<Placed> {
        if (nodes.size < 2) return nodes
        val xs = FloatArray(nodes.size) { nodes[it].x }
        val ys = FloatArray(nodes.size) { nodes[it].y }
        val gap = nodeR * 2 + 22f * dp // circle plus room for the name under it
        repeat(40) {
            var moved = false
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                val dx = xs[j] - xs[i]
                val dy = ys[j] - ys[i]
                val dist = hypot(dx, dy)
                if (dist >= gap) continue
                moved = true
                val push = (gap - dist) / 2 + 0.5f
                val ux = if (dist < 0.01f) 1f else dx / dist
                val uy = if (dist < 0.01f) 0f else dy / dist
                xs[i] -= ux * push; ys[i] -= uy * push
                xs[j] += ux * push; ys[j] += uy * push
            }
            // Re-project onto each phone's true distance ring.
            for (i in nodes.indices) {
                val want = geoRadius(nodes[i].distance!!)
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                val len = hypot(dx, dy).coerceAtLeast(0.01f)
                xs[i] = cx + dx / len * want
                ys[i] = cy + dy / len * want
            }
            if (!moved) return@repeat
        }
        return nodes.mapIndexed { i, p -> Placed(p.route, xs[i], ys[i], p.placed, p.distance) }
    }

    /** Evenly spaced, stable (ordered by id) placement for phones whose direction is unknown. */
    private fun spreadOnRing(
        members: List<RouteInfo>, radius: Float, into: MutableList<Placed>, offsetTurns: Double = 0.1, near: Boolean = false,
    ) {
        if (members.isEmpty()) return
        val sorted = members.sortedBy { it.dest.raw }
        sorted.forEachIndexed { i, r ->
            val angle = (offsetTurns + i.toDouble() / sorted.size) * 360
            val (x, y) = screen(angle, radius)
            into += Placed(r, x, y, placed = near, distance = null, near = near)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dim = if (running) 1f else 0.45f
        ringPaint.alpha = (255 * dim).toInt()

        // Rings: metres in geo mode, hops otherwise. Labels run along the diagonal farthest from north,
        // reading outward, so they never sit on the north marker wherever the compass turns it.
        val showNorth = geoMode || heading != null
        val north = -(heading ?: 0f).toDouble()
        val labelAngle = if (!showNorth) 135.0
        else listOf(45.0, 135.0, 225.0, 315.0).maxBy { a -> abs(((a - north) % 360 + 540) % 360 - 180) }
        ringLabelPaint.textAlign = if (labelAngle < 180) Paint.Align.LEFT else Paint.Align.RIGHT
        for (i in 1..3) {
            val radius = if (geoMode) geoRadius(ringStep * i) else inner * i / 3f
            canvas.drawCircle(cx, cy, radius, ringPaint)
            val label = if (geoMode) Formats.distance(ringStep * i)
            else context.getString(if (i < 3) R.string.radar_ring else R.string.radar_ring_more, i)
            val (lx, ly) = screen(labelAngle, radius)
            drawLabel(canvas, label, lx, ly + 4f * dp, ringLabelPaint)
        }
        if (geoMode && placed.any { !it.placed }) {
            canvas.drawCircle(cx, cy, outer, bandPaint)
            ringLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.radar_unknown_band), cx, cy + outer + 16f * dp, ringLabelPaint)
        }

        // North marker, turned with the compass.
        if (showNorth) {
            val (nx, ny) = screen(north, inner + 14f * dp)
            drawLabel(canvas, context.getString(R.string.dir_n), nx, ny + 4f * dp, northPaint)
        }

        if (sweeper.isStarted) {
            halo.color = accent
            halo.strokeWidth = 3f * dp
            halo.alpha = (200 * (1f - sweep)).toInt()
            canvas.drawCircle(cx, cy, inner * sweep, halo)
        }

        // Direct radio links from you; in hop mode also dashed "reached via" links.
        val byId = placed.associateBy { it.route.dest }
        for (p in placed) {
            val via = p.route.viaNode?.let { byId[it] }
            if (p.route.isNeighbor || via == null) {
                linkPaint.pathEffect = null
                linkPaint.color = ink
                linkPaint.alpha = if (geoMode) 70 else 140
                canvas.drawLine(cx, cy, p.x, p.y, linkPaint)
            } else if (!geoMode) {
                linkPaint.pathEffect = dash
                linkPaint.color = inkSoft
                linkPaint.alpha = 160
                canvas.drawLine(via.x, via.y, p.x, p.y, linkPaint)
            }
        }
        // On the GPS map the radio path is hidden to keep it calm; the selected phone shows it:
        // you → the neighbour it goes through (solid), neighbour → them (dashed).
        val chosen = placed.firstOrNull { it.route.dest == selected }
        val chosenVia = chosen?.route?.takeIf { !it.isNeighbor }?.viaNode?.let { byId[it] }
        if (geoMode && chosen != null && chosenVia != null) {
            linkPaint.color = ink
            linkPaint.alpha = 220
            linkPaint.strokeWidth = 2.6f * dp
            linkPaint.pathEffect = null
            canvas.drawLine(cx, cy, chosenVia.x, chosenVia.y, linkPaint)
            linkPaint.pathEffect = dash
            canvas.drawLine(chosenVia.x, chosenVia.y, chosen.x, chosen.y, linkPaint)
            linkPaint.strokeWidth = 2f * dp
        }

        // Where the phone points: a soft beam, as on map apps.
        if (heading != null && running) {
            val r = meR * 3.2f
            oval.set(cx - r, cy - r, cx + r, cy + r)
            cone.reset()
            cone.moveTo(cx, cy)
            cone.arcTo(oval, -90f - 28f, 56f)
            cone.close()
            fill.color = accent
            fill.alpha = 70
            canvas.drawPath(cone, fill)
            fill.alpha = 255
        }

        // GPS uncertainty around each placed phone.
        if (geoMode) {
            for (p in placed) {
                val acc = p.route.position?.accuracyM ?: continue
                val d = p.distance ?: continue
                val r = geoRadius(d + acc) - geoRadius(d)
                if (!p.placed || r <= nodeR) continue
                fill.color = MeshRadarView.avatarColor(p.route.dest)
                fill.alpha = 38
                canvas.drawCircle(p.x, p.y, r.coerceAtMost(inner), fill)
                fill.alpha = 255
            }
        }

        // Phones.
        for (p in placed) {
            val name = nameOf(p.route)
            val r = if (p.placed || !geoMode) nodeR else nodeR * 0.8f
            if (p.route.dest == selected) {
                stroke.color = accent
                stroke.strokeWidth = 4f * dp
                canvas.drawCircle(p.x, p.y, r + 4f * dp, stroke)
            }
            // A light rim lifts each phone off the rings and links, like a map pin.
            fill.color = surface
            canvas.drawCircle(p.x, p.y, r + 2f * dp, fill)
            fill.color = avatarColor(p.route.dest)
            fill.alpha = if (p.placed || !geoMode) 255 else 170
            canvas.drawCircle(p.x, p.y, r, fill)
            fill.alpha = 255
            initialPaint.color = 0xFFFFFFFF.toInt()
            initialPaint.textSize = (if (r < nodeR) 12f else 15f) * dp
            drawCentered(canvas, name.trim().firstOrNull()?.uppercase() ?: "?", p.x, p.y, initialPaint)

            val nameY = p.y + r + 14f * dp
            drawLabel(canvas, ellipsize(name, 12), p.x, nameY, namePaint)
            val second = p.distance?.let(Formats::distance) ?: if (p.near) context.getString(R.string.radar_near) else null
            second?.let { drawLabel(canvas, it, p.x, nameY + 13f * dp, distPaint) }
        }

        // You: the blue location dot with a white rim and a soft halo.
        if (running) {
            fill.color = accent
            fill.alpha = 40
            canvas.drawCircle(cx, cy, meR + 12f * dp, fill)
            fill.alpha = 255
        }
        fill.color = surface
        canvas.drawCircle(cx, cy, meR + 3.5f * dp, fill)
        fill.color = if (running) accent else offColor
        canvas.drawCircle(cx, cy, meR, fill)
        initialPaint.color = if (running) onAccent else 0xFFFFFFFF.toInt()
        initialPaint.textSize = 19f * dp
        drawCentered(canvas, myInitial, cx, cy, initialPaint)
    }

    /** Text with a surface-coloured backing so it stays legible over rings and links. */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint) {
        val width = paint.measureText(text)
        val left = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.RIGHT -> x - width
            else -> x - width / 2
        }
        val m = paint.fontMetrics
        labelBg.color = surface
        labelBg.alpha = 210
        canvas.drawRoundRect(left - 4f * dp, baseline + m.ascent - 1f * dp, left + width + 4f * dp, baseline + m.descent + 1f * dp, 4f * dp, 4f * dp, labelBg)
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val m = paint.fontMetrics
        canvas.drawText(text, x, y - (m.ascent + m.descent) / 2f, paint)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return placed.isNotEmpty()
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = placed.minByOrNull { hypot(it.x - event.x, it.y - event.y) }
            if (hit != null && hypot(hit.x - event.x, hit.y - event.y) < 32f * dp) {
                performClick()
                onNodeClick?.invoke(hit.route)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = min(w, (340 * dp).toInt())
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    companion object {
        fun avatarColor(id: NodeId): Int = AVATAR_PALETTE[((id.raw ushr 1) % AVATAR_PALETTE.size).toInt()]

        private val STEPS = doubleArrayOf(5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0, 2000.0, 2500.0, 5000.0)

        /** Ring spacing from a 1-2-2.5-5 series, so ring labels are round numbers. */
        fun niceStep(min: Double): Double =
            STEPS.firstOrNull { it >= min } ?: (ceil(min / 10_000) * 10_000)
    }
}
