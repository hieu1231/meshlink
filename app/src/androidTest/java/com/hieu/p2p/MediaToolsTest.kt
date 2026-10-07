package com.hieu.p2p

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hieu.p2p.data.VideoCompressor
import com.hieu.p2p.data.VoiceRecorder
import com.hieu.p2p.mesh.dm.DmKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun voiceNoteIsSmallAndHasWaveform() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        lateinit var result: VoiceRecorder.Result
        val recorder = VoiceRecorder(context)
        instrumentation.runOnMainSync { assertTrue("start", recorder.start()) }
        Thread.sleep(1_500)
        instrumentation.runOnMainSync { result = recorder.stop(keep = true)!! }
        assertTrue("duration ${result.durationMs}", result.durationMs in 1_300..2_500)
        // AAC 24 kbps: ~3 KB per second (+ container header).
        assertTrue("size ${result.bytes.size}", result.bytes.size in 1_000..12_000)
        assertTrue(result.waveform.size == VoiceRecorder.WAVEFORM_BARS)
    }

    /** Builds a 6 s 1080p H.264 clip from a still image, like a phone camera would hand us. */
    private fun sourceClip(): File {
        val frame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(90, 130, 160))
            val p = Paint().apply { color = Color.rgb(40, 80, 40) }
            repeat(40) { i -> drawCircle((i * 97 % 1920).toFloat(), (i * 53 % 1080).toFloat(), 60f + i, p) }
        }
        val png = File(context.cacheDir, "frame.png").apply { outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val out = File(context.cacheDir, "source.mp4").apply { delete() }
        val done = CountDownLatch(1)
        var error: Exception? = null
        instrumentation.runOnMainSync {
            val image = MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(6_000).build()
            val item = EditedMediaItem.Builder(image).setFrameRate(30).build()
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException
                        done.countDown()
                    }
                })
                .build()
                .start(item, out.path)
        }
        assertTrue("timeout building source", done.await(90, TimeUnit.SECONDS))
        error?.let { throw it }
        return out
    }

    @Test
    fun videoIsShrunkToMeshSize() = runBlocking {
        val source = sourceClip()
        val result = VideoCompressor.compress(context, Uri.fromFile(source))
        assertNotNull("compress failed", result)
        result!!
        assertTrue("size ${result.bytes.size}", result.bytes.size <= DmKind.VIDEO.maxBytes)
        assertTrue("duration ${result.durationMs}", result.durationMs in 5_000..7_000)
        val thumb = result.thumbnail
        assertNotNull(thumb)
        assertTrue("thumb ${thumb!!.size}", thumb.size <= 12 * 1024)

        val check = File(context.cacheDir, "check.mp4").apply { writeBytes(result.bytes) }
        val height = MediaMetadataRetriever().run {
            setDataSource(check.path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt().also { release() }
        }
        assertTrue("height $height", height <= 360)
    }
}
