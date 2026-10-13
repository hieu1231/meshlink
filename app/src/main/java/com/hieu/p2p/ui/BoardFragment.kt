package com.hieu.p2p.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hieu.p2p.R
import com.hieu.p2p.data.Board
import com.hieu.p2p.data.BoardCategory
import com.hieu.p2p.data.BoardItem
import com.hieu.p2p.databinding.FragmentBoardBinding
import com.hieu.p2p.databinding.ItemBoardBinding
import com.hieu.p2p.databinding.SheetBoardItemBinding
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
    private var me: GeoPoint? = null
    private lateinit var adapter: BoardAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentBoardBinding.bind(view)
        adapter = BoardAdapter(::onItemClick)
        binding.listBoard.layoutManager = LinearLayoutManager(requireContext())
        binding.listBoard.adapter = adapter

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
        viewLifecycleOwner.collectWhileStarted(node.status) { binding.textStatus.bindStatusLine(it) }
        val boardItems = node.dataVersion.map { Board.list(node.data) }.flowOn(Dispatchers.Default)
        viewLifecycleOwner.collectWhileStarted(boardItems.combine(mesh.location.position) { list, me -> list to me }) { (list, me) ->
            items = list
            this.me = me
            adapter.me = me
            render()
        }
    }

    private fun render() {
        val shown = filter?.let { f -> items.filter { it.category == f } } ?: items
        adapter.submitList(shown)
        binding.emptyBoard.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Detail sheet: the whole post, then what you can do with it. */
    private fun onItemClick(item: BoardItem) {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val sheet = SheetBoardItemBinding.inflate(layoutInflater)
        bindCategory(sheet.textCategory, item.category)
        sheet.textTime.text = Formats.time(item.updatedAt)
        sheet.textTitle.text = item.title
        sheet.textDetail.text = item.detail
        sheet.textDetail.isVisible = item.detail.isNotBlank()
        bindLocation(sheet.textLocation, me, item)
        sheet.avatarAuthor.bindAvatar(item.authorName.ifBlank { item.author.short }, item.author)
        sheet.textAuthor.text = authorLine(ctx, item)

        if (item.author == mesh.myId) {
            sheet.buttonPrimary.setText(R.string.board_edit)
            sheet.buttonPrimary.setIconResource(R.drawable.ic_edit)
            sheet.buttonPrimary.setOnClickListener {
                dialog.dismiss()
                showEditor(item)
            }
            sheet.buttonSecondary.setText(R.string.board_delete)
            sheet.buttonSecondary.icon = null
            sheet.buttonSecondary.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.red_soft)
            sheet.buttonSecondary.setTextColor(ContextCompat.getColor(ctx, R.color.red))
            sheet.buttonSecondary.setOnClickListener {
                dialog.dismiss()
                confirmDelete(item)
            }
        } else {
            sheet.buttonPrimary.setText(R.string.board_message_author)
            sheet.buttonPrimary.setIconResource(R.drawable.ic_tab_chats)
            sheet.buttonPrimary.setOnClickListener {
                dialog.dismiss()
                ConversationActivity.openDirect(ctx, item.author)
            }
            sheet.buttonSecondary.setText(R.string.board_find_author)
            sheet.buttonSecondary.setIconResource(R.drawable.ic_navigation)
            sheet.buttonSecondary.setOnClickListener {
                dialog.dismiss()
                PeerSheet.newInstance(item.author).show(childFragmentManager, "peer")
            }
        }
        dialog.setContentView(sheet.root)
        dialog.show()
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
            bindCategory(b.textCategory, item.category)
            // SOS posts get a red edge so they stand out while scrolling.
            b.row.setBackgroundResource(if (item.category == BoardCategory.SOS) R.drawable.bg_card_sos else R.drawable.bg_card)
            b.textTitle.text = item.title
            b.textDetail.text = item.detail
            b.textDetail.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
            b.textTime.text = Formats.time(item.updatedAt)
            bindLocation(b.textLocation, me, item)
            b.avatarAuthor.bindAvatar(item.authorName.ifBlank { item.author.short }, item.author)
            b.textAuthor.text = authorLine(ctx, item)
            b.row.setOnClickListener { onClick(item) }
        }

        private object Diff : DiffUtil.ItemCallback<BoardItem>() {
            override fun areItemsTheSame(a: BoardItem, b: BoardItem) = a.key == b.key
            override fun areContentsTheSame(a: BoardItem, b: BoardItem) = a == b
        }
    }
}

private val BoardCategory.softColor: Int
    get() = when (this) {
        BoardCategory.SOS -> R.color.red_soft
        BoardCategory.SAFE -> R.color.green_soft
        BoardCategory.RESOURCE -> R.color.orange_soft
        BoardCategory.INFO -> R.color.indigo_soft
    }

/** Category as a tinted capsule. */
private fun bindCategory(tag: TextView, category: BoardCategory) {
    tag.setText(category.label)
    tag.setTextColor(ContextCompat.getColor(tag.context, category.color))
    tag.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(tag.context, category.softColor))
}

private fun bindLocation(view: TextView, me: GeoPoint?, item: BoardItem) {
    val ctx = view.context
    val there = item.position
    view.isVisible = me != null && there != null
    if (me == null || there == null) return
    view.text = if (!me.directionReliableTo(there)) ctx.getString(R.string.sos_near_line)
    else ctx.getString(R.string.sos_location_line, Formats.distance(me.distanceTo(there)), Formats.compass(ctx, me.bearingTo(there)))
}

private fun authorLine(ctx: Context, item: BoardItem) =
    ctx.getString(R.string.board_author, item.authorName.ifBlank { item.author.short }, item.author.short)
