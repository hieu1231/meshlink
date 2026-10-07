package com.hieu.p2p.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hieu.p2p.R
import com.hieu.p2p.data.PublicChat
import com.hieu.p2p.databinding.FragmentChatsBinding
import com.hieu.p2p.databinding.ItemConversationBinding
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.dm.DmKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** Conversation list: the mesh-wide public channel first, then end-to-end encrypted direct chats. */
class ChatsFragment : Fragment(R.layout.fragment_chats) {

    private data class Row(val peer: NodeId?, val title: String, val preview: String, val time: Long?)

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChatsBinding.bind(view)
        val adapter = ConversationAdapter { row ->
            if (row.peer == null) ConversationActivity.openPublic(requireContext())
            else ConversationActivity.openDirect(requireContext(), row.peer)
        }
        binding.listConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.listConversations.adapter = adapter

        val runtime = mesh
        val node = runtime.node
        val ctx = requireContext().applicationContext
        val rows = combine(node.dataVersion, node.messagesVersion, node.status) { _, _, _ ->
            val last = PublicChat.list(node.data).lastOrNull()
            val publicRow = Row(
                peer = null,
                title = ctx.getString(R.string.public_channel),
                preview = last?.let { ctx.getString(R.string.sender_prefix, it.authorName, it.text) }
                    ?: ctx.getString(R.string.public_channel_empty),
                time = last?.time,
            )
            val dmRows = node.conversations().map { m ->
                val body = when (m.kind) {
                    DmKind.TEXT -> m.text
                    DmKind.IMAGE -> if (m.text.isBlank()) ctx.getString(R.string.image_preview_line)
                    else ctx.getString(R.string.image_preview_caption, m.text)
                    DmKind.VOICE -> ctx.getString(R.string.preview_voice, Formats.duration(m.durationMs))
                    DmKind.VIDEO -> if (m.text.isBlank()) ctx.getString(R.string.preview_video)
                    else ctx.getString(R.string.preview_video_caption, m.text)
                }
                Row(
                    peer = m.peer,
                    title = runtime.nameOf(m.peer),
                    preview = if (m.outgoing) ctx.getString(R.string.you_prefix, body) else body,
                    time = m.timestamp,
                )
            }
            listOf(publicRow) + dmRows
        }.flowOn(Dispatchers.Default)

        viewLifecycleOwner.collectWhileStarted(rows) { list ->
            adapter.submitList(list)
            binding.hintDm.visibility = if (list.size == 1) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ConversationAdapter(private val onClick: (Row) -> Unit) :
        ListAdapter<Row, ConversationAdapter.Holder>(Diff) {

        class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val b = holder.binding
            b.textTitle.text = row.title
            b.textPreview.text = row.preview
            b.textTime.text = row.time?.let(Formats::time).orEmpty()
            if (row.peer == null) {
                b.avatar.text = ""
                b.avatar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(b.root.context, R.color.primary))
                b.avatarIcon.visibility = View.VISIBLE
            } else {
                b.avatar.bindAvatar(row.title, row.peer)
                b.avatarIcon.visibility = View.GONE
            }
            b.root.setOnClickListener { onClick(row) }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.peer == b.peer
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
