package com.hieu.p2p

import android.Manifest
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hieu.p2p.data.CallEngine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * Two call engines on one phone, wired back to back: what A sends, B receives and vice versa,
 * each direction on its own thread like the mesh thread. Checks the real mic/camera/codec path.
 */
@RunWith(AndroidJUnit4::class)
class CallEngineLoopbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val toB = Executors.newSingleThreadExecutor()
    private val toA = Executors.newSingleThreadExecutor()
    /** While true, every video fragment from A to B is lost. */
    @Volatile private var dropVideo = false

    private lateinit var a: CallEngine
    private lateinit var b: CallEngine

    private val readerThread = HandlerThread("remote-surface").apply { start() }
    private val reader = ImageReader.newInstance(320, 240, ImageFormat.PRIVATE, 4).apply {
        setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(readerThread.looper))
    }

    @Before
    fun setUp() {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        a = CallEngine(context) { frame ->
            if (dropVideo && frame[0] == CallEngine.VIDEO) return@CallEngine
            toB.execute { b.onMedia(frame) }
        }
        b = CallEngine(context) { frame -> toA.execute { a.onMedia(frame) } }
    }

    @After
    fun tearDown() {
        a.stop()
        b.stop()
        toA.shutdownNow()
        toB.shutdownNow()
        reader.close()
        readerThread.quitSafely()
    }

    @Test
    fun voiceFlowsBothWays() {
        a.start(video = false)
        b.start(video = false)
        Thread.sleep(3_000)
        log("voice")
        // 16 kHz / 1024 samples ≈ 15.6 frames a second.
        assertTrue("A sent ${a.stats.audioSent}", a.stats.audioSent.get() > 30)
        assertTrue("B sent ${b.stats.audioSent}", b.stats.audioSent.get() > 30)
        assertTrue("B played ${b.stats.audioPlayed}", b.stats.audioPlayed.get() > 25)
        assertTrue("A played ${a.stats.audioPlayed}", a.stats.audioPlayed.get() > 25)
    }

    @Test
    fun videoDecodesOnTheOtherSide() {
        b.start(video = false)
        b.attachRemote(reader.surface)
        a.start(video = true)
        waitFor(10_000) { b.stats.videoFramesDecoded.get() >= 30 }
        log("video")
        assertTrue("A sent ${a.stats.videoFramesSent} frames", a.stats.videoFramesSent.get() >= 30)
        assertTrue("B decoded ${b.stats.videoFramesDecoded}", b.stats.videoFramesDecoded.get() >= 30)
        val view = b.view.value
        assertTrue("remote video not shown: $view", view.remoteVideo && view.remoteWidth > 0 && view.remoteHeight > 0)
    }

    @Test
    fun keyframeAfterLossRestoresPicture() {
        b.start(video = false)
        b.attachRemote(reader.surface)
        a.start(video = true)
        waitFor(10_000) { b.stats.videoFramesDecoded.get() >= 15 }
        val keysBefore = a.stats.keyframesSent.get()

        dropVideo = true
        Thread.sleep(700)
        dropVideo = false
        val decodedAfterLoss = b.stats.videoFramesDecoded.get()

        // The keyframe interval is 2 s; recovery must come from the request, well before that.
        waitFor(1_500) { b.stats.videoFramesDecoded.get() >= decodedAfterLoss + 10 }
        log("loss")
        assertTrue("B never asked A for a keyframe", a.stats.keyframeRequests.get() > 0)
        assertTrue("no new keyframe from A", a.stats.keyframesSent.get() > keysBefore)
        assertTrue(
            "picture did not recover: ${b.stats.videoFramesDecoded} after $decodedAfterLoss",
            b.stats.videoFramesDecoded.get() >= decodedAfterLoss + 10,
        )
    }

    @Test
    fun cameraOffStopsVideoAndHidesRemote() {
        b.start(video = false)
        b.attachRemote(reader.surface)
        a.start(video = true)
        waitFor(10_000) { b.view.value.remoteVideo }
        a.setCameraOn(false)
        waitFor(2_000) { !b.view.value.remoteVideo }
        val sent = a.stats.videoFramesSent.get()
        Thread.sleep(1_000)
        log("camera off")
        assertTrue("remote still shown", !b.view.value.remoteVideo)
        assertTrue("A kept sending video", a.stats.videoFramesSent.get() - sent <= 1)
    }

    private fun waitFor(ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    private fun log(what: String) =
        android.util.Log.i("CallEngineTest", "$what · A: ${a.stats} ${a.view.value} · B: ${b.stats} ${b.view.value}")
}
