package com.hieu.p2p.data

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.hieu.p2p.R
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.crdt.CrdtStore
import org.json.JSONObject

/** Public channel: a grow-only set of messages replicated through the CRDT, so late joiners get history. */
data class ChatMessage(val key: String, val author: NodeId, val authorName: String, val text: String, val time: Long)

object PublicChat {
    const val COLLECTION = "chat"

    fun encode(authorName: String, text: String): String =
        JSONObject().put("n", authorName).put("t", text).toString()

    fun list(store: CrdtStore): List<ChatMessage> = store.entries(COLLECTION).mapNotNull { e ->
        runCatching {
            val json = JSONObject(e.value)
            ChatMessage(e.key, e.hlc.node, json.optString("n"), json.getString("t"), e.hlc.wall)
        }.getOrNull()
    }.sortedBy { it.time }
}

enum class BoardCategory(val code: String, @StringRes val label: Int, @ColorRes val color: Int) {
    SOS("sos", R.string.category_sos, R.color.category_sos),
    SAFE("safe", R.string.category_safe, R.color.category_safe),
    RESOURCE("resource", R.string.category_resource, R.color.category_resource),
    INFO("info", R.string.category_info, R.color.category_info);

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: INFO
    }
}

/** Community board item: a LWW register per item, editable/deletable and conflict-free. */
data class BoardItem(
    val key: String,
    val category: BoardCategory,
    val title: String,
    val detail: String,
    val author: NodeId,
    val authorName: String,
    val updatedAt: Long,
    /** Where it was posted from, if the author shared location. */
    val position: GeoPoint? = null,
)

object Board {
    const val COLLECTION = "board"

    fun encode(
        category: BoardCategory, title: String, detail: String, author: NodeId, authorName: String,
        position: GeoPoint? = null,
    ): String = JSONObject()
        .put("c", category.code).put("ti", title).put("d", detail)
        .put("a", author.hex).put("n", authorName)
        .apply {
            if (position != null) put("la", position.latE6).put("lo", position.lonE6).put("ac", position.accuracyM)
        }
        .toString()

    fun list(store: CrdtStore): List<BoardItem> = store.entries(COLLECTION).mapNotNull { e ->
        runCatching {
            val json = JSONObject(e.value)
            BoardItem(
                e.key, BoardCategory.of(json.optString("c")), json.getString("ti"), json.optString("d"),
                NodeId.fromHex(json.getString("a")), json.optString("n"), e.hlc.wall,
                if (json.has("la")) GeoPoint(json.getInt("la"), json.getInt("lo"), json.optInt("ac"), e.hlc.wall) else null,
            )
        }.getOrNull()
    }.sortedWith(compareBy<BoardItem> { it.category != BoardCategory.SOS }.thenByDescending { it.updatedAt })
}
