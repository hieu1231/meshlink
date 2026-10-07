package com.hieu.p2p.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/** One voice note playing at a time; reports position so the bubble's waveform can fill in. */
class VoicePlayer(private val onUpdate: (id: Long, positionMs: Int, playing: Boolean) -> Unit) {
    private var player: MediaPlayer? = null
    var currentId: Long = 0
        private set
    var positionMs: Int = 0
        private set
    val isPlaying: Boolean get() = player?.isPlaying == true

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            positionMs = p.currentPosition
            onUpdate(currentId, positionMs, p.isPlaying)
            if (p.isPlaying) handler.postDelayed(this, 50)
        }
    }

    fun toggle(id: Long, file: File) {
        val p = player
        if (p != null && id == currentId) {
            if (p.isPlaying) p.pause() else p.start()
            handler.post(ticker)
            return
        }
        release()
        if (!file.exists()) return
        currentId = id
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.path)
            setOnCompletionListener {
                positionMs = 0
                onUpdate(currentId, 0, false)
                handler.removeCallbacks(ticker)
            }
            prepare()
            start()
        }
        handler.post(ticker)
    }

    fun release() {
        handler.removeCallbacks(ticker)
        val old = currentId
        player?.release()
        player = null
        currentId = 0
        positionMs = 0
        if (old != 0L) onUpdate(old, 0, false)
    }
}
