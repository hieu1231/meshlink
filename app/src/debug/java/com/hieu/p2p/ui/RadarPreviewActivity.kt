package com.hieu.p2p.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.routing.RouteInfo

/** Debug-only: the radar with a made-up crowd around Hoan Kiem lake, for design review. */
class RadarPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val me = GeoPoint.of(21.028511, 105.852350, 26f, System.currentTimeMillis())
        fun at(dNorth: Double, dEast: Double, acc: Float) = GeoPoint.of(
            me.lat + dNorth / 111_320.0, me.lon + dEast / (111_320.0 * Math.cos(Math.toRadians(me.lat))), acc,
            System.currentTimeMillis() - 4_000,
        )
        val ids = (1..7).map { NodeId(0x1000_0000_0000_0000L + it * 0x0111_2222_3333_4445L) }
        val routes = listOf(
            RouteInfo(ids[0], "Hieu", 1, null, 0, at(-3.0, -2.0, 26f), 64), // on the same desk, indoor GPS
            RouteInfo(ids[1], "Bình", 1, null, 0, at(-30.0, -22.0, 8f), 31),
            RouteInfo(ids[2], "Châu", 2, ids[0], 0, at(70.0, 40.0, 25f), 88),
            RouteInfo(ids[3], "Đội cứu hộ 2", 3, ids[1], 0, at(-110.0, 95.0, 12f), 12),
            RouteInfo(ids[4], "Lan", 2, ids[1], 0, null, 50),
            RouteInfo(ids[5], "Minh", 4, ids[0], 0, null, -1),
        )
        if (intent.getBooleanExtra("sheet", false)) {
            // The find-person sheet's layout with sample values.
            val b = com.hieu.p2p.databinding.BottomSheetPeerBinding.inflate(layoutInflater)
            b.avatar.bindAvatar("Hieu", ids[0])
            b.textName.text = "Hieu"
            b.textRoute.text = getString(com.hieu.p2p.R.string.find_route_direct)
            b.textDistance.text = Formats.distance(me.distanceTo(routes[2].position!!))
            b.textWhere.text = getString(com.hieu.p2p.R.string.find_where, "Phía trước, lệch phải", "Đông Bắc")
            b.badgeAccuracy.text = getString(com.hieu.p2p.R.string.find_distance_accuracy, "31 m")
            b.badgeBattery.text = getString(com.hieu.p2p.R.string.find_battery, 64)
            b.badgeSeen.text = getString(com.hieu.p2p.R.string.find_seen, "vừa xong")
            b.textNote.text = getString(com.hieu.p2p.R.string.find_calibrate)
            b.textRoute.text = getString(com.hieu.p2p.R.string.find_route_via, "Bình", 3)
            b.textTrace.text = getString(com.hieu.p2p.R.string.trace_done, 3, 184)
            b.buttonTrace.setText(com.hieu.p2p.R.string.trace_again)
            listOf(
                Triple("0", "Bạn", ""), Triple("1", "Bình", "38 ms"), Triple("2", "Châu", "97 ms"), Triple("3", "Hieu", "184 ms"),
            ).forEachIndexed { i, (n, name, rtt) ->
                val row = com.hieu.p2p.databinding.ItemTraceStepBinding.inflate(layoutInflater, b.traceSteps, false)
                row.step.text = n
                row.step.backgroundTintList = android.content.res.ColorStateList.valueOf(MeshRadarView.avatarColor(if (i == 0) ids[6] else ids[i]))
                row.name.text = name
                row.role.text = when (i) { 0 -> ""; 3 -> "người nhận"; else -> "chuyển tiếp" }
                row.rtt.text = rtt
                if (i == 0) (row.rail.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                    height = (22 * resources.displayMetrics.density).toInt()
                }
                b.traceSteps.addView(row.root)
            }
            setContentView(b.root)
            b.root.post { b.arrow.setAngle(intent.getFloatExtra("angle", 35f)) }
            return
        }
        val radar = MeshRadarView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            setPadding(0, 300, 0, 0)
            addView(radar)
        })
        val heading = intent.getFloatExtra("heading", -1f).takeIf { it >= 0 }
        radar.render(routes, true, "Lan", me, heading, if (intent.getBooleanExtra("select", false)) ids[2] else null) { it.name }
    }
}
