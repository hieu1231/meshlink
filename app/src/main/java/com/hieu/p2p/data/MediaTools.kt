package com.hieu.p2p.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Voice notes: AAC mono at 16 kHz / 24 kbps (~3 KB per second, so a minute is ~180 KB and crosses
 * several hops). While recording it samples the level 20 times a second to draw a waveform,
 * which is sent ahead of the audio so the bubble looks right before the file arrives.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = mutableListOf<Int>()
    private val handler = Handler(Looper.getMainLooper())

    /** Live level 0..1 for the recording indicator. */
    var onLevel: (Float) -> Unit = {}

    val elapsedMs: Long get() = if (recorder == null) 0 else System.currentTimeMillis() - startedAt

    private val sampler = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            levels += amp
            onLevel((amp / 20_000f).coerceIn(0f, 1f))
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    fun start(): Boolean {
        val out = File(context.cacheDir, "voice-${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            levels.clear()
            handler.postDelayed(sampler, SAMPLE_MS)
            true
        } catch (e: Exception) {
            r.release()
            out.delete()
            false
        }
    }

    class Result(val bytes: ByteArray, val durationMs: Int, val waveform: ByteArray)

    /** Stops; returns null if cancelled, too short or unreadable. */
    fun stop(keep: Boolean): Result? {
        handler.removeCallbacks(sampler)
        val r = recorder ?: return null
        val duration = elapsedMs.toInt()
        recorder = null
        runCatching { r.stop() } // throws if nothing was recorded yet
        r.release()
        val f = file ?: return null
        file = null
        if (!keep || duration < MIN_MS) {
            f.delete()
            return null
        }
        val bytes = f.readBytes().also { f.delete() }
        return Result(bytes, duration, waveform(levels))
    }

    companion object {
        const val MAX_MS = 120_000
        const val MIN_MS = 600
        private const val SAMPLE_MS = 50L
        const val WAVEFORM_BARS = 48

        /**
         * Down-samples levels to [WAVEFORM_BARS] bytes 0..255: peak per bucket, stretched between the
         * quietest and loudest bucket (so steady talkers still get a readable shape), then sqrt for
         * perceived loudness.
         */
        fun waveform(levels: List<Int>): ByteArray {
            if (levels.isEmpty()) return ByteArray(WAVEFORM_BARS)
            val peaks = List(WAVEFORM_BARS) { i ->
                val from = (i * levels.size / WAVEFORM_BARS).coerceAtMost(levels.size - 1)
                val to = max(from + 1, (i + 1) * levels.size / WAVEFORM_BARS).coerceAtMost(levels.size)
                levels.subList(from, to).max()
            }
            val lo = peaks.min()
            val span = (peaks.max() - lo).coerceAtLeast(1)
            return ByteArray(WAVEFORM_BARS) { i ->
                val v = (peaks[i] - lo).toDouble() / span
                (Math.sqrt(v) * 255).roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }
}

/**
 * Video clips: re-encoded with Media3 Transformer to H.264 at most 360 px tall, ~450 kbps, AAC
 * audio, trimmed to [MAX_MS]. Phone camera video (10–20 Mbps) would never cross a Bluetooth mesh.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {
    const val MAX_MS = 30_000L
    private const val HEIGHT = 360
    private const val BITRATE = 450_000

    class Result(val bytes: ByteArray, val durationMs: Int, val thumbnail: ByteArray?)

    suspend fun compress(context: Context, uri: Uri): Result? {
        val out = File(context.cacheDir, "video-${System.nanoTime()}.mp4")
        val ok = withContext(Dispatchers.Main) { export(context, uri, out) }
        if (!ok || !out.exists()) return null
        return withContext(Dispatchers.IO) {
            try {
                val duration = durationMs(out.path)
                Result(out.readBytes(), duration, thumbnail(out.path))
            } finally {
                out.delete()
            }
        }
    }

    private suspend fun export(context: Context, uri: Uri, out: File): Boolean = suspendCancellableCoroutine { cont ->
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(MAX_MS).build())
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(HEIGHT))))
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            .build()
        transformer.start(edited, out.path)
        cont.invokeOnCancellation { transformer.cancel() }
    }

    fun durationMs(path: String): Int = MediaMetadataRetriever().run {
        try {
            setDataSource(path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            release()
        }
    }

    /** Small JPEG (fits in the message header) of a frame one second in. */
    fun thumbnail(path: String): ByteArray? = MediaMetadataRetriever().run {
        try {
            setDataSource(path)
            val frame = getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@run null
            val scale = 240f / max(frame.width, frame.height)
            val small = Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
            var quality = 70
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                quality -= 15
            } while (bytes.size > 11 * 1024 && quality > 10)
            bytes
        } catch (e: Exception) {
            null
        } finally {
            release()
        }
    }
}
