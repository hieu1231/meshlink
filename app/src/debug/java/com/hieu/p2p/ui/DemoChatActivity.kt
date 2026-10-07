package com.hieu.p2p.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import com.hieu.p2p.data.FileAttachmentStore
import com.hieu.p2p.data.MeshDatabase
import com.hieu.p2p.data.VoiceRecorder
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.dm.DirectMessage
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.DmStatus
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/**
 * Debug-only: seeds a sample conversation (text, photo, voice, video) for design review, then
 * opens it. Must run in a fresh process (adb `am start -S`) before the mesh runtime loads.
 */
class DemoChatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val peer = NodeId(0x34fdc2f71df8c90cL)
        getSharedPreferences("mesh", MODE_PRIVATE).edit().putString("peer_name_${peer.hex}", "Hieu").apply()
        val db = MeshDatabase(this).messages
        val files = FileAttachmentStore(this)
        val now = System.currentTimeMillis()
        val y = now - 26 * 3_600_000L
        var id = 0x51_0000L

        fun msg(out: Boolean, text: String, at: Long, status: DmStatus, kind: DmKind = DmKind.TEXT, bytes: Int = 0,
                chunks: Int = 0, progress: Int = 0, duration: Int = 0, preview: ByteArray? = null): Long {
            id++
            db.upsert(DirectMessage(id, peer, out, text, at, status, kind = kind, imageBytes = bytes, chunks = chunks,
                progress = progress, durationMs = duration, preview = preview))
            return id
        }

        msg(false, "Bạn ở đâu rồi?", y, DmStatus.RECEIVED)
        msg(true, "Mình đang ở trường tiểu học, có 12 người", y + 60_000, DmStatus.DELIVERED)
        msg(true, "Nước lên tới tầng 1 rồi", y + 70_000, DmStatus.DELIVERED)
        msg(false, "Đội mình đang tới, khoảng 20 phút nữa", now - 40 * 60_000, DmStatus.RECEIVED)
        msg(false, "Giữ máy luôn bật nhé", now - 39 * 60_000, DmStatus.RECEIVED)
        val photo = msg(true, "Cổng sau, chỗ cây bàng", now - 30 * 60_000, DmStatus.DELIVERED, DmKind.IMAGE, 160_000, 10)
        files.save(photo, sampleJpeg(800, 600))
        msg(false, "", now - 25 * 60_000, DmStatus.RECEIVED, DmKind.VOICE, 70_000, 5, 5, 23_000, wave(1))
        msg(true, "", now - 20 * 60_000, DmStatus.DELIVERED, DmKind.VOICE, 30_000, 2, duration = 9_000, preview = wave(2))
        msg(false, "Đường vào làng từ phía bắc", now - 6 * 60_000, DmStatus.RECEIVING, DmKind.VIDEO, 1_900_000, 116, 47, 28_000,
            sampleJpeg(240, 135, quality = 60))
        msg(true, "", now - 2 * 60_000, DmStatus.SENT, DmKind.VIDEO, 1_200_000, 74, duration = 17_000,
            preview = sampleJpeg(240, 135, quality = 60, hue = 2))
        msg(true, "Bạn thấy video không?", now - 60_000, DmStatus.QUEUED)

        startActivity(android.content.Intent(this, ConversationActivity::class.java).putExtra("peer", peer.hex))
        finish()
    }

    private fun wave(seed: Int): ByteArray {
        val levels = List(160) { i -> (12_000 * (0.35 + 0.65 * kotlin.math.abs(sin(i * 0.21 * seed + seed)))).toInt() }
        return VoiceRecorder.waveform(levels)
    }

    private fun sampleJpeg(w: Int, h: Int, quality: Int = 80, hue: Int = 1): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val top = if (hue == 1) Color.rgb(96, 140, 170) else Color.rgb(170, 120, 80)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, Color.rgb(60, 80, 70), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val ground = Paint().apply { color = Color.rgb(70, 95, 60) }
        c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), ground)
        val tree = Paint().apply { color = Color.rgb(40, 70, 40); isAntiAlias = true }
        c.drawCircle(w * 0.3f, h * 0.5f, h * 0.22f, tree)
        c.drawCircle(w * 0.72f, h * 0.55f, h * 0.16f, tree)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
