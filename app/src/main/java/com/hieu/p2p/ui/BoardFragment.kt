package com.hieu.p2p.ui

import android.content.res.ColorStateList
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.hieu.p2p.R
import com.hieu.p2p.data.Board
import com.hieu.p2p.data.BoardCategory
import com.hieu.p2p.data.BoardItem
import com.hieu.p2p.databinding.FragmentBoardBinding
import com.hieu.p2p.databinding.ItemBoardBinding
import com.hieu.p2p.mesh.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Community board (SOS, "I'm safe", shared resources, notices). Every item is a LWW register in the
 * replicated CRDT, so it survives partitions and converges on every phone without a server.
 */
class BoardFragment : Fragment(R.layout.fragment_board) {

    private var _binding: FragmentBoardBinding? = null
    private val binding get() = _binding!!
    private var items: List<BoardItem> = emptyList()
    private var filter: BoardCategory? = null
    private lateinit var adapter: BoardAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentBoardBinding.bind(view)
        adapter = BoardAdapter(::onItemClick)
        binding.listBoard.layoutManager = LinearLayoutManager(requireContext())
        binding.listBoard.adapter = adapter
        binding.listBoard.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL).apply {
                dividerInsetStart = resources.getDimensionPixelSize(R.dimen.screen_margin)
                dividerInsetEnd = resources.getDimensionPixelSize(R.dimen.screen_margin)
                isLastItemDecorated = false
            }
        )

        binding.chipsFilter.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chip_sos -> BoardCategory.SOS
                R.id.chip_safe -> BoardCategory.SAFE
                R.id.chip_resource -> BoardCategory.RESOURCE
                R.id.chip_info -> BoardCategory.INFO
                else -> null
            }
            render()
        }
        binding.fabPost.setOnClickListener { showEditor(null) }

        val node = mesh.node
        val boardItems = node.dataVersion.map { Board.list(node.data) }.flowOn(Dispatchers.Default)
        viewLifecycleOwner.collectWhileStarted(boardItems.combine(mesh.location.position) { list, me -> list to me }) { (list, me) ->
            items = list
            adapter.me = me
            render()
        }
    }

    private fun render() {
        val shown = filter?.let { f -> items.filter { it.category == f } } ?: items
        adapter.submitList(shown)
        binding.emptyBoard.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onItemClick(item: BoardItem) {
        val mine = item.author == mesh.myId
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.title)
            .setMessage(item.detail.ifBlank { null })
        if (mine) {
            dialog.setPositiveButton(R.string.board_edit) { _, _ -> showEditor(item) }
            dialog.setNeutralButton(R.string.board_delete) { _, _ -> confirmDelete(item) }
        } else {
            dialog.setPositiveButton(R.string.board_message_author) { _, _ ->
                ConversationActivity.openDirect(requireContext(), item.author)
            }
            dialog.setNeutralButton(R.string.board_find_author) { _, _ ->
                PeerSheet.newInstance(item.author).show(childFragmentManager, "peer")
            }
        }
        dialog.setNegativeButton(R.string.close, null).show()
    }

    private fun confirmDelete(item: BoardItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.board_delete_confirm)
            .setPositiveButton(R.string.board_delete) { _, _ -> mesh.deleteBoard(item.key) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditor(item: BoardItem?) {
        BoardEditor.show(requireContext(), mesh, item, filter ?: BoardCategory.SOS)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class BoardAdapter(private val onClick: (BoardItem) -> Unit) :
        ListAdapter<BoardItem, BoardAdapter.Holder>(Diff) {

        var me: GeoPoint? = null
            set(value) {
                if (field == value) return
                field = value
                notifyItemRangeChanged(0, itemCount)
            }

        class Holder(val binding: ItemBoardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemBoardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            val ctx = b.root.context
            val color = ContextCompat.getColor(ctx, item.category.color)
            b.textCategory.setText(item.category.label)
            b.textCategory.setTextColor(color)
            b.rule.backgroundTintList = ColorStateList.valueOf(color)
            b.row.setBackgroundColor(
                if (item.category == BoardCategory.SOS) ContextCompat.getColor(ctx, R.color.sos_wash) else 0
            )
            b.textTitle.text = item.title
            b.textDetail.text = item.detail
            b.textDetail.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
            b.textTime.text = Formats.time(item.updatedAt)
            val mine = me
            val there = item.position
            if (mine != null && there != null && !mine.directionReliableTo(there)) {
                b.textLocation.visibility = View.VISIBLE
                b.textLocation.text = ctx.getString(R.string.sos_near_line)
            } else if (mine != null && there != null) {
                b.textLocation.visibility = View.VISIBLE
                b.textLocation.text = ctx.getString(
                    R.string.sos_location_line, Formats.distance(mine.distanceTo(there)), Formats.compass(ctx, mine.bearingTo(there)),
                )
            } else {
                b.textLocation.visibility = View.GONE
            }
            b.textAuthor.text = ctx.getString(R.string.board_author, item.authorName.ifBlank { item.author.short }, item.author.short)
            b.root.setOnClickListener { onClick(item) }
        }

        private object Diff : DiffUtil.ItemCallback<BoardItem>() {
            override fun areItemsTheSame(a: BoardItem, b: BoardItem) = a.key == b.key
            override fun areContentsTheSame(a: BoardItem, b: BoardItem) = a == b
        }
    }
}
