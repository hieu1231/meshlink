package com.hieu.p2p.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hieu.p2p.R
import com.hieu.p2p.databinding.BottomSheetPeerBinding
import com.hieu.p2p.databinding.ItemTraceStepBinding
import com.hieu.p2p.mesh.TraceResult
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.routing.RouteInfo
import kotlinx.coroutines.flow.combine

/**
 * "Find this person", modelled on precision-finding UIs: one big arrow, one big distance, and the
 * direction in words. Everything updates live from GPS (both ends) and this phone's compass.
 */
class PeerSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPeerBinding? = null
    private val binding get() = _binding!!
    private val peer by lazy { NodeId.fromHex(requireArguments().getString(ARG_PEER)!!) }

    private var heading: Float? = null
    private var hasCompass = true
    private var compassUnreliable = false
    private var route: RouteInfo? = null
    private var me: GeoPoint? = null
    private var lastBucket = Int.MAX_VALUE

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetPeerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sensor = HeadingSensor(requireContext(), onAccuracy = { low ->
            compassUnreliable = low
            render()
        }) { h ->
            heading = h
            render()
        }
        hasCompass = sensor.available
        viewLifecycleOwner.lifecycle.addObserver(sensor)

        binding.arrow.onAligned = { binding.arrow.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        binding.buttonTrace.setOnClickListener { mesh.node.trace(peer) }
        viewLifecycleOwner.collectWhileStarted(mesh.node.trace) { renderTrace(it) }

        binding.buttonClose.setOnClickListener { dismiss() }
        binding.buttonMessage.setOnClickListener {
            ConversationActivity.openDirect(requireContext(), peer)
            dismiss()
        }

        viewLifecycleOwner.collectWhileStarted(mesh.node.status.combine(mesh.location.position) { s, p -> s to p }) { (s, p) ->
            route = s.routes.firstOrNull { it.dest == peer }
            me = p
            render()
        }
    }

    private fun render() {
        val b = _binding ?: return
        val ctx = requireContext()
        val r = route
        val name = r?.name?.ifBlank { null } ?: mesh.nameOf(peer)
        b.textName.text = name
        b.avatar.bindAvatar(name, peer)
        b.textRoute.text = when {
            r == null -> getString(R.string.conversation_dm_no_route)
            r.isNeighbor || r.viaNode == null -> getString(R.string.find_route_direct)
            else -> getString(R.string.find_route_via, mesh.nameOf(r.viaNode), r.hops)
        }

        b.textNote.visibility = View.VISIBLE
        b.badgeBattery.visibility = if ((r?.batteryPercent ?: -1) >= 0) View.VISIBLE else View.GONE
        r?.batteryPercent?.takeIf { it >= 0 }?.let { b.badgeBattery.text = getString(R.string.find_battery, it) }

        val theirs = r?.position
        val mine = me
        b.badgeSeen.visibility = if (theirs != null) View.VISIBLE else View.GONE
        theirs?.let { b.badgeSeen.text = getString(R.string.find_seen, Formats.ago(ctx, it.fixedAt)) }

        if (theirs == null || mine == null) {
            b.arrow.setAngle(null)
            b.textDistance.text = "—"
            b.textWhere.text = ""
            b.badgeAccuracy.visibility = View.GONE
            b.textNote.text = when {
                theirs == null -> getString(R.string.find_no_peer_fix, name, r?.hops ?: 0)
                else -> getString(R.string.find_no_my_fix)
            }
            return
        }

        val distance = mine.distanceTo(theirs)
        val bearing = mine.bearingTo(theirs)
        val accuracy = mine.uncertaintyTo(theirs).toDouble()
        b.badgeAccuracy.visibility = View.VISIBLE
        b.badgeAccuracy.text = getString(R.string.find_distance_accuracy, Formats.distance(accuracy))

        if (!mine.directionReliableTo(theirs)) {
            // Inside the GPS error: any arrow would be a guess, possibly the opposite way.
            b.arrow.setAngle(null)
            b.textDistance.text = getString(R.string.find_near)
            b.textWhere.text = getString(R.string.find_near_where, Formats.distance(accuracy))
            b.textNote.text = getString(R.string.find_near_note)
            lastBucket = Int.MAX_VALUE
            return
        }
        b.textDistance.text = Formats.distance(distance)

        val h = heading
        if (h != null) {
            val relative = bearing - h
            b.arrow.setAngle(relative.toFloat())
            b.textWhere.text = getString(R.string.find_where, Formats.relative(ctx, relative).replaceFirstChar { it.uppercase() }, Formats.compass(ctx, bearing))
            b.textNote.text = if (compassUnreliable) getString(R.string.find_calibrate) else ""
            b.textNote.visibility = if (compassUnreliable) View.VISIBLE else View.GONE
        } else {
            // No compass: north-up arrow, stated plainly.
            b.arrow.setAngle(bearing.toFloat())
            b.textWhere.text = Formats.compass(ctx, bearing)
            b.textNote.text = getString(R.string.find_where_no_compass)
        }

        // A light tick every 10 m closer, so you can walk without staring at the screen.
        val bucket = (distance / 10).toInt()
        if (bucket < lastBucket && lastBucket != Int.MAX_VALUE) b.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastBucket = bucket
    }

    /** The traced path as a numbered rail: you, each relay that answered, then the person. */
    private fun renderTrace(trace: TraceResult?) {
        val b = _binding ?: return
        val t = trace?.takeIf { it.target == peer }
        b.traceSteps.removeAllViews()
        b.buttonTrace.isEnabled = t == null || t.done
        b.buttonTrace.setText(if (t?.done == true) R.string.trace_again else R.string.trace_button)
        if (t == null) {
            b.textTrace.text = getString(R.string.trace_hint)
            return
        }
        if (t.noRoute) {
            b.textTrace.text = getString(R.string.trace_no_route)
            return
        }
        val last = t.hops.lastOrNull()
        b.textTrace.text = when {
            t.reached && last != null -> getString(R.string.trace_done, last.index, last.rttMs.toInt())
            t.done -> getString(R.string.trace_failed, mesh.nameOf(peer))
            else -> getString(R.string.trace_running, t.hops.size)
        }

        addStep(b, "0", getString(R.string.trace_you), null, null, MeshRadarView.avatarColor(mesh.myId), first = true)
        val byIndex = t.hops.associateBy { it.index }
        val top = maxOf(t.hops.maxOfOrNull { it.index } ?: 0, if (t.done) 0 else (route?.hops ?: 0))
        for (i in 1..top) {
            val hop = byIndex[i]
            val isTarget = hop?.node == peer
            when {
                hop != null -> addStep(
                    b, i.toString(), mesh.nameOf(hop.node),
                    getString(if (isTarget) R.string.trace_target_tag else R.string.trace_relay_tag),
                    getString(R.string.trace_rtt, hop.rttMs.toInt()), MeshRadarView.avatarColor(hop.node),
                )
                t.done -> addStep(b, i.toString(), getString(R.string.trace_unknown), null, null, null)
                else -> addStep(b, i.toString(), "…", null, null, null)
            }
        }
        // The rail ends at the last step's dot.
        b.traceSteps.getChildAt(b.traceSteps.childCount - 1)?.takeIf { b.traceSteps.childCount > 1 }?.let { last ->
            (ItemTraceStepBinding.bind(last).rail.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                height = (22 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun addStep(
        b: BottomSheetPeerBinding, step: String, name: String, role: String?, rtt: String?, color: Int?, first: Boolean = false,
    ) {
        val row = ItemTraceStepBinding.inflate(layoutInflater, b.traceSteps, false)
        row.step.text = step
        row.step.backgroundTintList = android.content.res.ColorStateList.valueOf(
            color ?: androidx.core.content.ContextCompat.getColor(requireContext(), R.color.gray)
        )
        row.name.text = name
        row.role.text = role.orEmpty()
        row.rtt.text = rtt.orEmpty()
        // The rail joins steps; the first row's rail starts at its own dot.
        if (first) (row.rail.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
            height = (22 * resources.displayMetrics.density).toInt()
        }
        b.traceSteps.addView(row.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PEER = "peer"

        fun newInstance(peer: NodeId) = PeerSheet().apply {
            arguments = Bundle().apply { putString(ARG_PEER, peer.hex) }
        }
    }
}
