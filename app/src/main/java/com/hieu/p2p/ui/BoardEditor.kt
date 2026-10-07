package com.hieu.p2p.ui

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hieu.p2p.R
import com.hieu.p2p.data.BoardCategory
import com.hieu.p2p.data.BoardItem
import com.hieu.p2p.data.MeshRuntime
import com.hieu.p2p.databinding.DialogBoardItemBinding

/** Post or edit a board item. Shared by the board tab and the SOS shortcut on the mesh tab. */
object BoardEditor {
    private val chipFor = mapOf(
        BoardCategory.SOS to R.id.cat_sos,
        BoardCategory.SAFE to R.id.cat_safe,
        BoardCategory.RESOURCE to R.id.cat_resource,
        BoardCategory.INFO to R.id.cat_info,
    )

    fun show(
        context: Context,
        mesh: MeshRuntime,
        item: BoardItem? = null,
        category: BoardCategory = BoardCategory.SOS,
        onPosted: (BoardCategory) -> Unit = {},
    ) {
        val form = DialogBoardItemBinding.inflate(LayoutInflater.from(context))
        val start = item?.category ?: category
        form.chipsCategory.check(chipFor.getValue(start))
        form.inputTitle.setText(item?.title)
        form.inputDetail.setText(item?.detail)
        fun syncHint(c: BoardCategory) {
            form.layoutTitle.hint = context.getString(
                if (c == BoardCategory.SOS) R.string.board_title_hint_sos else R.string.board_title_hint
            )
        }
        syncHint(start)
        form.chipsCategory.setOnCheckedStateChangeListener { _, ids ->
            chipFor.entries.firstOrNull { it.value == ids.firstOrNull() }?.let { syncHint(it.key) }
        }

        val title = when {
            item != null -> R.string.board_edit
            category == BoardCategory.SOS -> R.string.board_new_sos
            else -> R.string.board_new
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(form.root)
            .setPositiveButton(if (category == BoardCategory.SOS && item == null) R.string.action_sos else R.string.board_post, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        form.inputTitle.requestFocus()
        // Validate without dismissing on error.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val text = form.inputTitle.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                form.layoutTitle.error = context.getString(R.string.board_title_required)
                return@setOnClickListener
            }
            val chosen = chipFor.entries.first { it.value == form.chipsCategory.checkedChipId }.key
            mesh.postBoard(item?.key, chosen, text, form.inputDetail.text?.toString()?.trim().orEmpty())
            dialog.dismiss()
            onPosted(chosen)
        }
    }
}
