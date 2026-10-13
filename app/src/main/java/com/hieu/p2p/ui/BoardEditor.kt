package com.hieu.p2p.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hieu.p2p.R
import com.hieu.p2p.data.BoardCategory
import com.hieu.p2p.data.BoardItem
import com.hieu.p2p.data.MeshRuntime
import com.hieu.p2p.databinding.SheetBoardEditorBinding

/**
 * Post or edit a board item, in a modal sheet with Cancel / title / Post across the top.
 * Shared by the board tab and the SOS shortcut on the mesh tab.
 */
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
        val form = SheetBoardEditorBinding.inflate(LayoutInflater.from(context))
        val start = item?.category ?: category
        form.chipsCategory.check(chipFor.getValue(start))
        form.inputTitle.setText(item?.title)
        form.inputDetail.setText(item?.detail)
        fun sync(c: BoardCategory) {
            form.layoutTitle.hint = context.getString(
                if (c == BoardCategory.SOS) R.string.board_title_hint_sos else R.string.board_title_hint
            )
            form.buttonPost.setText(if (c == BoardCategory.SOS && item == null) R.string.board_send_short else R.string.board_post_short)
        }
        sync(start)
        form.chipsCategory.setOnCheckedStateChangeListener { _, ids ->
            chipFor.entries.firstOrNull { it.value == ids.firstOrNull() }?.let { sync(it.key) }
        }
        form.textSheetTitle.setText(
            when {
                item != null -> R.string.board_edit
                category == BoardCategory.SOS -> R.string.board_new_sos
                else -> R.string.board_new
            }
        )

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(form.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        form.buttonCancel.setOnClickListener { dialog.dismiss() }
        // Validate without closing on error.
        form.buttonPost.setOnClickListener {
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
        dialog.show()
        form.inputTitle.requestFocus()
    }
}
