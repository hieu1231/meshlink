package com.hieu.p2p.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.hieu.p2p.R
import com.hieu.p2p.data.BoardCategory
import com.hieu.p2p.databinding.FragmentMeshBinding
import com.hieu.p2p.databinding.ItemPeerBinding
import com.hieu.p2p.mesh.MeshStatus
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.routing.RouteInfo
import kotlinx.coroutines.flow.combine
import kotlin.math.abs

/** Home: live map of the mesh, the on/off switch, and the two emergency shortcuts. */
class MeshFragment : Fragment(R.layout.fragment_mesh) {

    private var _binding: FragmentMeshBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PeerAdapter
    private var heading: Float? = null
    private var listHeading: Float? = null
    private var selected: NodeId? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentMeshBinding.bind(view)
        val openPeer: (RouteInfo) -> Unit = { r ->
            selected = r.dest
            lastSnapshot?.let(::render) // highlight it and draw its path on the radar
            PeerSheet.newInstance(r.dest).show(childFragmentManager, "peer")
        }
        adapter = PeerAdapter(mesh::nameOf, openPeer)
        binding.listPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.listPeers.adapter = adapter
        binding.listPeers.itemAnimator = null
        binding.radar.onNodeClick = openPeer

        // The compass only runs while this screen is visible.
        viewLifecycleOwner.lifecycle.addObserver(HeadingSensor(requireContext()) { h ->
            heading = h
            binding.radar.setHeading(h)
            // Row arrows need far less precision than the radar; avoid rebinding every frame.
            val last = listHeading
            if (h == null || last == null || abs(angleDiff(h, last)) > 8f) {
                listHeading = h
                adapter.updateFrame(adapter.me, h)
            }
        })

        binding.textMyId.text = getString(R.string.my_id, mesh.myId.short)
        binding.rowMe.setOnClickListener { (requireActivity() as MainActivity).promptName(firstRun = false) }
        binding.buttonToggle.setOnClickListener { (requireActivity() as MainActivity).toggleMesh() }
        binding.switchShare.setOnCheckedChangeListener { _, on -> if (on != mesh.sharingLocation.value) mesh.setSharingLocation(on) }
        binding.buttonSos.setOnClickListener {
            BoardEditor.show(requireContext(), mesh, category = BoardCategory.SOS) {
                snack(if (mesh.isConnected) R.string.sos_posted else R.string.sos_posted_offline)
            }
        }
        binding.buttonSafe.setOnClickListener {
            val name = mesh.displayName.ifBlank { getString(R.string.default_name, mesh.myId.short) }
            mesh.postSafe(
                getString(R.string.safe_title, name),
                getString(R.string.safe_detail, Formats.time(System.currentTimeMillis())),
            )
            snack(if (mesh.isConnected) R.string.safe_posted else R.string.safe_posted_offline)
        }

        val state = combine(mesh.node.status, mesh.name, mesh.location.position, mesh.sharingLocation) { s, n, p, share ->
            Snapshot(s, n, p, share)
        }
        viewLifecycleOwner.collectWhileStarted(state) { render(it) }
    }

    private data class Snapshot(val status: MeshStatus, val name: String, val me: GeoPoint?, val sharing: Boolean)

    private var lastSnapshot: Snapshot? = null

    private fun render(snap: Snapshot) {
        lastSnapshot = snap
        val s = snap.status
        val shownName = snap.name.ifBlank { getString(R.string.default_name, mesh.myId.short) }
        binding.textMyName.text = getString(R.string.me_line, shownName)

        binding.textHeadline.text = when {
            !s.running -> getString(R.string.headline_off)
            s.links == 0 || s.links == s.outdatedNeighbors -> getString(R.string.headline_searching)
            else -> resources.getQuantityString(R.plurals.headline_on, s.links, s.links)
        }
        binding.textStatus.text = when {
            !s.running -> getString(R.string.status_off)
            s.outdatedNeighbors > 0 -> getString(R.string.status_outdated, s.outdatedNeighbors)
            s.links == 0 -> getString(R.string.status_searching)
            else -> getString(R.string.status_on, s.routes.size, s.stats.relayed)
        }
        binding.radar.render(s.routes, s.running, shownName, snap.me, heading, selected) { r ->
            r.name.ifBlank { mesh.nameOf(r.dest) }
        }

        binding.switchShare.isChecked = snap.sharing
        binding.textShare.text = getString(
            when {
                !snap.sharing -> R.string.share_location_off
                snap.me == null && s.running -> R.string.share_location_no_fix
                else -> R.string.share_location_on
            }
        )

        // On: the yellow switch steps back to a quiet button so SOS / Safe lead.
        binding.buttonToggle.setText(if (s.running) R.string.mesh_off else R.string.mesh_on)
        val bg = if (s.running) R.color.surface_high else R.color.signal
        val fg = if (s.running) R.color.ink else R.color.on_signal
        binding.buttonToggle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), bg)
        binding.buttonToggle.setTextColor(ContextCompat.getColor(requireContext(), fg))
        binding.buttonToggle.iconTint = ContextCompat.getColorStateList(requireContext(), fg)

        // Nearest first when distances are known, otherwise by hops.
        val sorted = s.routes.sortedWith(
            compareBy<RouteInfo> { r -> snap.me?.let { me -> r.position?.let { me.distanceTo(it) } } ?: Double.MAX_VALUE }
                .thenBy { it.hops }
        )
        adapter.updateFrame(snap.me, heading)
        adapter.submitList(sorted)
        binding.emptyPeers.visibility = if (s.routes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun snack(res: Int) {
        Snackbar.make(binding.root, res, Snackbar.LENGTH_LONG)
            .setAnchorView(requireActivity().findViewById(R.id.bottom_nav))
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

private class PeerAdapter(
    private val nameOf: (NodeId) -> String,
    private val onClick: (RouteInfo) -> Unit,
) : ListAdapter<RouteInfo, PeerAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPeerBinding) : RecyclerView.ViewHolder(binding.root)

    var me: GeoPoint? = null
        private set
    private var heading: Float? = null

    /** My position and compass heading, which every row's distance and arrow depend on. */
    fun updateFrame(me: GeoPoint?, heading: Float?) {
        if (me == this.me && heading == this.heading) return
        this.me = me
        this.heading = heading
        notifyItemRangeChanged(0, itemCount, FRAME)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindFrame(holder, getItem(position)) else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val route = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val name = route.name.ifBlank { nameOf(route.dest) }
        b.textName.text = name
        b.avatar.bindAvatar(name, route.dest)
        b.textBattery.visibility = if (route.batteryPercent >= 0) View.VISIBLE else View.GONE
        b.textBattery.text = ctx.getString(R.string.find_battery, route.batteryPercent)
        b.textBattery.setTextColor(
            if (route.batteryPercent in 0..15) ContextCompat.getColor(ctx, R.color.category_sos)
            else com.google.android.material.color.MaterialColors.getColor(b.textBattery, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        bindFrame(holder, route)
        b.root.setOnClickListener { onClick(route) }
    }

    private fun bindFrame(holder: Holder, route: RouteInfo) {
        val b = holder.binding
        val ctx = b.root.context
        val mine = me
        val theirs = route.position
        val hopText = if (route.isNeighbor || route.viaNode == null) ctx.getString(R.string.peer_direct)
        else ctx.getString(R.string.peer_via, nameOf(route.viaNode), route.hops)
        val via = route.viaNode?.takeIf { !route.isNeighbor }?.let(nameOf)
        if (mine != null && theirs != null && !mine.directionReliableTo(theirs)) {
            b.textDetail.text = if (via != null) ctx.getString(R.string.peer_near_via, via) else ctx.getString(R.string.peer_near)
            b.imageDirection.visibility = View.INVISIBLE
        } else if (mine != null && theirs != null) {
            val bearing = mine.bearingTo(theirs)
            val dist = Formats.distance(mine.distanceTo(theirs))
            b.textDetail.text = if (via != null) ctx.getString(R.string.peer_distance_via, dist, Formats.compass(ctx, bearing), via)
            else ctx.getString(R.string.peer_distance_line, dist, Formats.compass(ctx, bearing))
            b.imageDirection.visibility = View.VISIBLE
            b.imageDirection.rotation = (bearing - (heading ?: 0f)).toFloat()
        } else {
            b.textDetail.text = hopText
            b.imageDirection.visibility = View.INVISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<RouteInfo>() {
        override fun areItemsTheSame(a: RouteInfo, b: RouteInfo) = a.dest == b.dest
        override fun areContentsTheSame(a: RouteInfo, b: RouteInfo) = a == b
    }

    companion object {
        private val FRAME = Any()
    }
}
