package com.hieu.p2p.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audio and video for a mesh call, built on platform codecs only.
 *
 * Audio: mic (voice-communication source, with echo cancelling and noise suppression where the
 * phone has them) → AAC-LC 16 kHz mono 24 kbps → one ~200 B frame every 64 ms. Playback decodes
 * into an AudioTrack and drops the backlog when frames pile up, so latency never creeps up.
 *
 * Video: Camera2 → hardware H.264 encoder through its input Surface, ~320×240 at 15 fps.
 * Frames are cut into ≤12 KB fragments; a frame missing any fragment is dropped and the receiver
 * asks for a keyframe. The receiver reports loss every second; the sender lowers the bitrate under
 * loss (450 → 80 kbps), raises it when clean, and switches video off if even 80 kbps keeps failing.
 *
 * Frame wire format (inside the call's encrypted media packet):
 * ```
 * 1 AUDIO   seq:int  aac
 * 2 VIDEO   frame:int  index:u8  count:u8  flags:u8  rotation/90:u8  h264-fragment
 * 3 CONTROL sub:u8 ...    (1 keyframe please, 2 stats loss‰:short, 3 video on/off:u8)
 * ```
 */
class CallEngine(private val context: Context, private val send: (ByteArray) -> Unit) {

    enum class Quality { GOOD, FAIR, POOR }

    data class View(
        val running: Boolean = false,
        val muted: Boolean = false,
        val cameraOn: Boolean = false,
        val frontCamera: Boolean = true,
        val speaker: Boolean = false,
        val remoteVideo: Boolean = false,
        /** Decoded remote size after rotation, for aspect-correct display. */
        val remoteWidth: Int = 0,
        val remoteHeight: Int = 0,
        val quality: Quality = Quality.GOOD,
        /** Video was switched off automatically because the link could not carry it. */
        val videoDroppedForQuality: Boolean = false,
        val bitrateKbps: Int = 0,
    )

    /** Running counters for logs and device tests; reset on every start(). */
    class Stats {
        val audioSent = AtomicInteger()
        val audioPlayed = AtomicInteger()
        val videoFramesSent = AtomicInteger()
        val keyframesSent = AtomicInteger()
        val videoFramesDecoded = AtomicInteger()
        val keyframeRequests = AtomicInteger()

        override fun toString() = "audio ${audioSent}↑ ${audioPlayed}↓ · video ${videoFramesSent}↑ (key $keyframesSent) " +
            "${videoFramesDecoded}↓ · key requests $keyframeRequests"
    }

    @Volatile var stats = Stats()
        private set

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view.asStateFlow()
    private fun update(f: (View) -> View) { _view.value = f(_view.value) }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var running = false

    // ---------------------------------------------------------------------------------------------
    // lifecycle

    fun start(video: Boolean) {
        if (running) return
        running = true
        stats = Stats()
        lossWindow.reset()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        update { View(running = true, cameraOn = video, speaker = video, frontCamera = true) }
        applySpeaker(video)
        startAudio()
        startStatsLoop()
        if (video) startVideoEncoder()
    }

    fun stop() {
        if (!running) return
        running = false
        stopCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        stopVideoEncoder()
        synchronized(assemblies) { stopVideoDecoder() }
        stopAudio()
        statsHandler?.removeCallbacksAndMessages(null)
        statsThread?.quitSafely()
        statsThread = null
        statsHandler = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        _view.value = View()
    }

    fun setMuted(muted: Boolean) = update { it.copy(muted = muted) }

    fun setSpeaker(on: Boolean) {
        applySpeaker(on)
        update { it.copy(speaker = on) }
    }

    private fun applySpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    // ---------------------------------------------------------------------------------------------
    // inbound media (called on the mesh thread: parse and hand off, never block)

    fun onMedia(frame: ByteArray) {
        if (!running || frame.isEmpty()) return
        when (frame[0]) {
            AUDIO -> {
                val seq = ByteReader(frame.copyOfRange(1, 5)).int()
                lossWindow.record(seq)
                if (playQueue.size > MAX_AUDIO_BACKLOG) playQueue.pollFirst() // cut latency rather than lag
                playQueue.offerLast(frame.copyOfRange(5, frame.size))
            }
            VIDEO -> onVideoFragment(frame)
            CONTROL -> onControl(frame)
        }
    }

    private fun onControl(frame: ByteArray) {
        if (frame.size < 2) return
        when (frame[1]) {
            CTRL_KEYFRAME -> {
                stats.keyframeRequests.incrementAndGet()
                requestSyncFrame()
            }
            CTRL_STATS -> if (frame.size >= 4) adaptBitrate(((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
            CTRL_VIDEO_STATE -> if (frame.size >= 3) {
                // Off hides the remote picture at once; on waits for the first decoded frame.
                remoteVideoOn = frame[2] == 1.toByte()
                if (!remoteVideoOn) update { it.copy(remoteVideo = false) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // audio

    private var audioThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingDeque<ByteArray>()

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audioThread = Thread({ captureLoop() }, "call-audio-in").apply { start() }
        playThread = Thread({ playLoop() }, "call-audio-out").apply { start() }
    }

    private fun stopAudio() {
        audioThread?.interrupt()
        playThread?.interrupt()
        audioThread?.join(500)
        playThread?.join(500)
        audioThread = null
        playThread = null
        playQueue.clear()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, PCM_FRAME_BYTES * 4))
        } catch (e: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_FRAME_BYTES * 2)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        record.startRecording()
        val pcm = ByteArray(PCM_FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var seq = 0
        var pts = 0L
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                var read = 0
                while (read < pcm.size) {
                    val n = record.read(pcm, read, pcm.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (_view.value.muted) pcm.fill(0)
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)!!.apply { clear(); put(pcm, 0, read) }
                    encoder.queueInputBuffer(inIndex, 0, read, pts, 0)
                    pts += 1_000_000L * (read / 2) / SAMPLE_RATE
                }
                while (true) {
                    val out = encoder.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        encoder.getOutputBuffer(out)!!.apply { position(info.offset); get(data) }
                        stats.audioSent.incrementAndGet()
                        send(ByteWriter(data.size + 5).byte(AUDIO.toInt()).int(++seq).raw(data).toByteArray())
                    }
                    encoder.releaseOutputBuffer(out, false)
                }
            }
        } catch (e: Exception) {
            // interrupted or codec error: fall through to cleanup
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    private fun playLoop() {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_LC_16K_MONO_CONFIG))
        }
        decoder.configure(format, null, null, 0)
        decoder.start()
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, PCM_FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val aac = playQueue.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    decoder.getInputBuffer(inIndex)!!.apply { clear(); put(aac) }
                    decoder.queueInputBuffer(inIndex, 0, aac.size, pts, 0)
                    pts += 64_000
                }
                while (true) {
                    val out = decoder.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    if (info.size > 0) {
                        val pcm = ByteArray(info.size)
                        decoder.getOutputBuffer(out)!!.apply { position(info.offset); get(pcm) }
                        track.write(pcm, 0, pcm.size)
                        stats.audioPlayed.incrementAndGet()
                    }
                    decoder.releaseOutputBuffer(out, false)
                }
            }
        } catch (e: Exception) {
            // interrupted
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // loss statistics and adaptation

    /** Counts received vs. expected audio frames (audio is a steady 15.6 frames/s). */
    private class LossWindow {
        private var highest = 0
        private var received = 0
        private var expectedFrom = 0

        @Synchronized fun record(seq: Int) {
            if (expectedFrom == 0) expectedFrom = seq
            if (seq > highest) highest = seq
            received++
        }

        /** Loss in permille since the last call, then starts a new window. */
        @Synchronized fun takePermille(): Int? {
            if (expectedFrom == 0 || highest < expectedFrom) return null
            val expected = highest - expectedFrom + 1
            val loss = ((expected - received).coerceAtLeast(0) * 1000 / expected).coerceIn(0, 1000)
            expectedFrom = highest + 1
            received = 0
            return loss
        }

        @Synchronized fun reset() { highest = 0; received = 0; expectedFrom = 0 }
    }

    private val lossWindow = LossWindow()
    private var statsThread: HandlerThread? = null
    private var statsHandler: Handler? = null

    private fun startStatsLoop() {
        val t = HandlerThread("call-stats").apply { start() }
        statsThread = t
        val h = Handler(t.looper)
        statsHandler = h
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                val loss = lossWindow.takePermille()
                if (loss != null) {
                    send(byteArrayOf(CONTROL, CTRL_STATS, (loss shr 8).toByte(), loss.toByte()))
                    update { it.copy(quality = qualityOf(loss)) }
                } else {
                    // Heard nothing for a whole second.
                    update { it.copy(quality = Quality.POOR) }
                }
                h.postDelayed(this, 1_000)
            }
        }, 1_000)
    }

    private fun qualityOf(lossPermille: Int) = when {
        lossPermille < 50 -> Quality.GOOD
        lossPermille < 150 -> Quality.FAIR
        else -> Quality.POOR
    }

    private var bitrate = START_VIDEO_BITRATE
    private var cleanReports = 0
    private var badAtFloor = 0

    /** Sender side: the other end tells us how much of our media it lost last second. */
    private fun adaptBitrate(lossPermille: Int) {
        val encoder = videoEncoder ?: return
        when {
            lossPermille > 100 -> {
                cleanReports = 0
                if (bitrate <= MIN_VIDEO_BITRATE) {
                    if (lossPermille > 250 && ++badAtFloor >= 4) {
                        // Even the lowest bitrate drowns the link: keep the voice, drop the picture.
                        badAtFloor = 0
                        setCameraOn(false)
                        update { it.copy(videoDroppedForQuality = true) }
                        return
                    }
                } else {
                    bitrate = maxOf(MIN_VIDEO_BITRATE, (bitrate * 0.7).toInt())
                }
            }
            lossPermille < 20 -> {
                badAtFloor = 0
                if (++cleanReports >= 3 && bitrate < MAX_VIDEO_BITRATE) {
                    cleanReports = 0
                    bitrate = minOf(MAX_VIDEO_BITRATE, (bitrate * 1.15).toInt())
                } else return
            }
            else -> return
        }
        runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) }
        update { it.copy(bitrateKbps = bitrate / 1000) }
    }

    // ---------------------------------------------------------------------------------------------
    // video out

    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: HandlerThread? = null
    private var codecConfig: ByteArray? = null
    private var frameId = 0
    private var videoSize = Size(320, 240)
    private var sensorRotation = 0

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewTexture: SurfaceTexture? = null

    /** Local preview target; the camera only runs while one is attached (i.e. the call screen is visible). */
    fun attachPreview(texture: SurfaceTexture?) {
        previewTexture = texture
        if (!running || !_view.value.cameraOn) return
        // The capture session's outputs are fixed: rebuild it with (or without) the preview.
        stopCamera()
        if (videoEncoder == null) startVideoEncoder() else openCamera()
    }

    fun setCameraOn(on: Boolean) {
        update { it.copy(cameraOn = on, videoDroppedForQuality = if (on) false else it.videoDroppedForQuality) }
        send(byteArrayOf(CONTROL, CTRL_VIDEO_STATE, if (on) 1 else 0))
        if (on) {
            if (videoEncoder == null) startVideoEncoder() else openCamera()
            requestSyncFrame()
        } else {
            stopCamera()
        }
    }

    fun switchCamera() {
        update { it.copy(frontCamera = !it.frontCamera) }
        stopCamera()
        openCamera()
        requestSyncFrame()
    }

    private fun startVideoEncoder() {
        if (videoEncoder != null) {
            openCamera()
            return
        }
        val thread = HandlerThread("call-video-enc").apply { start() }
        encoderThread = thread
        videoSize = chooseSize()
        bitrate = START_VIDEO_BITRATE
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0) {
                        val data = ByteArray(info.size)
                        codec.getOutputBuffer(index)!!.apply { position(info.offset); get(data) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codecConfig = data
                        } else if (_view.value.cameraOn) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            // Keyframes always carry SPS/PPS so a receiver can (re)join at any keyframe.
                            sendVideoFrame(if (key) (codecConfig ?: ByteArray(0)) + data else data, key)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    // codec released while a buffer was in flight
                }
            }
        }
        // Async mode is fixed at configure(): the callback has to be in place before it.
        val handler = Handler(thread.looper)
        // Encoders reject options they do not support (the emulator's has no CBR, for one):
        // ask for the most, then fall back to plain settings. SPS/PPS are prepended by hand
        // in the callback, so no KEY_PREPEND_HEADER_TO_SYNC_FRAMES, which many encoders refuse.
        val extras = listOf(
            MediaFormat.KEY_BITRATE_MODE to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            MediaFormat.KEY_PROFILE to MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaFormat.KEY_LEVEL to MediaCodecInfo.CodecProfileLevel.AVCLevel31,
        )
        var configured = false
        for (keep in extras.size downTo 0) {
            extras.forEachIndexed { i, (key, value) -> if (i < keep) format.setInteger(key, value) else format.removeKey(key) }
            try {
                encoder.setCallback(callback, handler)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                break
            } catch (e: Exception) {
                encoder.reset()
            }
        }
        if (!configured) {
            // No usable video encoder: carry on as a voice call.
            encoder.release()
            thread.quitSafely()
            encoderThread = null
            update { it.copy(cameraOn = false) }
            return
        }
        encoderSurface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder
        update { it.copy(bitrateKbps = bitrate / 1000) }
        openCamera()
    }

    private fun stopVideoEncoder() {
        val encoder = videoEncoder ?: return
        videoEncoder = null
        runCatching { encoder.stop() }
        encoder.release()
        encoderSurface?.release()
        encoderSurface = null
        encoderThread?.quitSafely()
        encoderThread = null
        codecConfig = null
    }

    private fun sendVideoFrame(data: ByteArray, key: Boolean) {
        val id = ++frameId
        val count = (data.size + FRAGMENT - 1) / FRAGMENT
        if (count > 255) return
        stats.videoFramesSent.incrementAndGet()
        if (key) stats.keyframesSent.incrementAndGet()
        val rotation = (sensorRotation / 90).toByte()
        for (i in 0 until count) {
            val from = i * FRAGMENT
            val part = data.copyOfRange(from, minOf(data.size, from + FRAGMENT))
            send(
                ByteWriter(part.size + 10).byte(VIDEO.toInt()).int(id).byte(i).byte(count)
                    .byte(if (key) 1 else 0).byte(rotation.toInt()).raw(part).toByteArray()
            )
        }
    }

    private fun requestSyncFrame() {
        runCatching { videoEncoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private val cameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun cameraId(front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    /** Smallest 4:3 size the camera can feed an encoder that is at least QVGA. */
    private fun chooseSize(): Size {
        val id = cameraId(true) ?: return Size(320, 240)
        val map = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(320, 240)
        val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        return sizes.filter { it.width * 3 == it.height * 4 && it.width >= 320 }.minByOrNull { it.width * it.height }
            ?: sizes.filter { it.width >= 320 }.minByOrNull { it.width * it.height }
            ?: Size(320, 240)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!running || camera != null || encoderSurface == null || !_view.value.cameraOn) return
        val id = cameraId(_view.value.frontCamera) ?: return
        val chars = cameraManager.getCameraCharacteristics(id)
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        if (cameraThread == null) {
            cameraThread = HandlerThread("call-camera").apply { start() }.also { cameraHandler = Handler(it.looper) }
        }
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { FPS in it.lower..it.upper && it.upper <= 30 }
            ?.minByOrNull { it.upper - it.lower } ?: Range(FPS, FPS)
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running || !_view.value.cameraOn) {
                        device.close()
                        return
                    }
                    camera = device
                    val targets = mutableListOf(encoderSurface!!)
                    previewTexture?.let {
                        it.setDefaultBufferSize(videoSize.width, videoSize.height)
                        targets += Surface(it)
                    }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                targets.forEach(::addTarget)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                            }.build()
                            runCatching { s.setRepeatingRequest(request, null, cameraHandler) }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                    }, cameraHandler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                }
            }, cameraHandler)
        } catch (e: Exception) {
            // camera busy or permission revoked: carry on with voice
        }
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    // ---------------------------------------------------------------------------------------------
    // video in

    private class Assembly(val count: Int, val key: Boolean, val rotation: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var have = 0
    }

    private val assemblies = HashMap<Int, Assembly>()
    private var lastDecoded = 0
    private var waitingForKey = true
    private var lastKeyRequest = 0L
    @Volatile private var remoteVideoOn = true

    private var remoteSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private var decoderRotation = -1
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    /** Where remote video is drawn; null while the call screen is hidden. */
    fun attachRemote(surface: Surface?) {
        synchronized(assemblies) {
            remoteSurface = surface
            stopVideoDecoder()
            waitingForKey = true
        }
        if (surface != null) askForKeyframe(force = true)
    }

    private fun onVideoFragment(frame: ByteArray) {
        if (frame.size < 10) return
        val r = ByteReader(frame)
        r.byte()
        val id = r.int()
        val index = r.byte()
        val count = r.byte()
        val key = r.byte() == 1
        val rotation = r.byte() * 90
        val part = r.raw(r.remaining)
        if (count == 0 || index >= count) return
        remoteVideoOn = true
        val complete: Assembly
        synchronized(assemblies) {
            if (id <= lastDecoded) return
            val a = assemblies.getOrPut(id) { Assembly(count, key, rotation) }
            if (a.parts[index] == null) {
                a.parts[index] = part
                a.have++
            }
            if (a.have < a.count) {
                // Too many frames in flight: older ones will never complete.
                if (assemblies.size > 6) assemblies.keys.filter { it < id - 6 }.forEach { assemblies.remove(it) }
                return
            }
            assemblies.remove(id)
            assemblies.keys.filter { it < id }.forEach { assemblies.remove(it) }
            // A P-frame after a gap cannot be decoded cleanly: wait for the next keyframe.
            if (!a.key && (waitingForKey || id != lastDecoded + 1)) {
                lastDecoded = id
                waitingForKey = true
                askForKeyframe(force = false)
                return
            }
            waitingForKey = false
            lastDecoded = id
            complete = a
        }
        val data = ByteArray(complete.parts.sumOf { it!!.size })
        var pos = 0
        complete.parts.forEach { p -> System.arraycopy(p!!, 0, data, pos, p.size); pos += p.size }
        decodeHandler?.post { decode(data, complete.rotation) } ?: startVideoDecoder(complete.rotation, data)
    }

    private fun askForKeyframe(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyRequest < 700) return
        lastKeyRequest = now
        send(byteArrayOf(CONTROL, CTRL_KEYFRAME))
    }

    private fun startVideoDecoder(rotation: Int, first: ByteArray) {
        if (remoteSurface == null) return
        if (decodeThread == null) {
            decodeThread = HandlerThread("call-video-dec").apply { start() }.also { decodeHandler = Handler(it.looper) }
        }
        decodeHandler!!.post { decode(first, rotation) }
    }

    private var decodePts = 0L

    /** Runs on the decode thread. */
    private fun decode(data: ByteArray, rotation: Int) {
        val surface = remoteSurface ?: return
        if (decoder == null || rotation != decoderRotation) {
            decoder?.let { runCatching { it.stop() }; it.release() }
            decoder = null
            val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height).apply {
                setInteger(MediaFormat.KEY_ROTATION, rotation)
            }
            try {
                d.configure(format, surface, null, 0)
                d.start()
            } catch (e: Exception) {
                d.release()
                return
            }
            decoder = d
            decoderRotation = rotation
        }
        val d = decoder ?: return
        try {
            val inIndex = d.dequeueInputBuffer(20_000)
            if (inIndex < 0) return
            d.getInputBuffer(inIndex)!!.apply { clear(); put(data) }
            decodePts += 1_000_000L / FPS
            d.queueInputBuffer(inIndex, 0, data.size, decodePts, 0)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = d.dequeueOutputBuffer(info, 5_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = d.outputFormat
                        val w = f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        val swap = rotation % 180 != 0
                        update { it.copy(remoteVideo = true, remoteWidth = if (swap) h else w, remoteHeight = if (swap) w else h) }
                    }
                    out >= 0 -> {
                        d.releaseOutputBuffer(out, true)
                        stats.videoFramesDecoded.incrementAndGet()
                        if (!_view.value.remoteVideo && remoteVideoOn) update { it.copy(remoteVideo = true) }
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            // corrupted input or codec reset: rebuild on the next keyframe
            decoder?.let { runCatching { it.stop() }; it.release() }
            decoder = null
            synchronized(assemblies) { waitingForKey = true }
            askForKeyframe(force = true)
        }
    }

    private fun stopVideoDecoder() {
        val h = decodeHandler
        val t = decodeThread
        decodeHandler = null
        decodeThread = null
        h?.post {
            decoder?.let { runCatching { it.stop() }; it.release() }
            decoder = null
            decoderRotation = -1
        }
        t?.quitSafely()
        assemblies.clear()
        lastDecoded = 0
    }

    companion object {
        const val AUDIO: Byte = 1
        const val VIDEO: Byte = 2
        const val CONTROL: Byte = 3
        private const val CTRL_KEYFRAME: Byte = 1
        private const val CTRL_STATS: Byte = 2
        private const val CTRL_VIDEO_STATE: Byte = 3

        private const val SAMPLE_RATE = 16_000
        private const val AUDIO_BITRATE = 24_000
        /** One AAC frame: 1024 samples of 16-bit mono. */
        private const val PCM_FRAME_BYTES = 2048
        /** AudioSpecificConfig for AAC-LC (2), 16 kHz (index 8), mono (1). */
        private val AAC_LC_16K_MONO_CONFIG = byteArrayOf(0x14, 0x08)
        /** ~400 ms of queued audio: beyond that, drop rather than fall behind. */
        private const val MAX_AUDIO_BACKLOG = 6

        private const val FPS = 15
        private const val FRAGMENT = 12 * 1024
        private const val START_VIDEO_BITRATE = 250_000
        private const val MIN_VIDEO_BITRATE = 80_000
        private const val MAX_VIDEO_BITRATE = 450_000
    }
}
