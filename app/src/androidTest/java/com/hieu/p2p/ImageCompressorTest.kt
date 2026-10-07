package com.hieu.p2p

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hieu.p2p.data.ImageCompressor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

@RunWith(AndroidJUnit4::class)
class ImageCompressorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A noisy 4000x3000 "camera photo" (noise defeats JPEG, like real photos do). */
    private fun cameraPhoto(rotate: Boolean): Uri {
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val random = Random(3)
        repeat(4000) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextFloat() * 4000, random.nextFloat() * 3000, 10f + random.nextFloat() * 60, paint)
        }
        val file = File(context.cacheDir, "photo-$rotate.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (rotate) ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue(file.length() > 1_000_000)
        return Uri.fromFile(file)
    }

    @Test
    fun largePhotoIsShrunkUnderTarget() {
        val out = ImageCompressor.compress(context, cameraPhoto(rotate = false))
        assertNotNull(out)
        assertTrue("size ${out!!.size}", out.size <= ImageCompressor.TARGET_BYTES)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue(maxOf(decoded.width, decoded.height) <= ImageCompressor.MAX_SIDE)
        assertTrue(decoded.width > decoded.height)
    }

    @Test
    fun exifRotationIsApplied() {
        val out = ImageCompressor.compress(context, cameraPhoto(rotate = true))!!
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue("portrait expected", decoded.height > decoded.width)
    }
}
