package com.hieu.p2p.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hieu.p2p.mesh.dm.AttachmentStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Attachments (photos, voice notes, videos) as files in app-private storage, one per message id.
 * Decoders sniff the format from the content, so one neutral extension serves every kind;
 * photos saved by older versions keep their .jpg name.
 */
class FileAttachmentStore(context: Context) : AttachmentStore {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }

    fun file(id: Long): File {
        val hex = java.lang.Long.toHexString(id)
        val legacy = File(dir, "$hex.jpg")
        return if (legacy.exists()) legacy else File(dir, "$hex.media")
    }

    override fun save(id: Long, bytes: ByteArray) {
        val target = file(id)
        val tmp = File(dir, target.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
    }

    override fun load(id: Long): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()
}

/**
 * Shrinks a picked photo to something the mesh can carry: upright (EXIF applied), longest side
 * at most [MAX_SIDE] px, JPEG re-encoded until it fits [TARGET_BYTES]. Over Bluetooth a 200 KB
 * image takes a few seconds per hop; a raw 4 MB camera photo would take minutes.
 */
object ImageCompressor {
    const val MAX_SIDE = 1280
    const val TARGET_BYTES = 200 * 1024

    fun compress(context: Context, uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        // Bounds-only decode always returns null by design; the size lands in [bounds].
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0
        var bitmap = scaleAndRotate(decoded, MAX_SIDE, rotation)

        var quality = 82
        while (true) {
            val bytes = encode(bitmap, quality)
            if (bytes.size <= TARGET_BYTES) return bytes
            if (quality > 50) {
                quality -= 10
            } else {
                // Still too big: shrink dimensions and try again.
                val side = (max(bitmap.width, bitmap.height) * 0.8f).roundToInt()
                if (side < 320) return bytes
                bitmap = scaleAndRotate(bitmap, side, 0)
                quality = 70
            }
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int) =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun scaleAndRotate(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / max(src.width, src.height))
        if (scale == 1f && rotation == 0) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Decodes a stored image for display, sampled down to roughly [targetPx] on its longest side. */
    fun decodeForDisplay(file: File, targetPx: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
