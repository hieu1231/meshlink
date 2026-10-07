package com.hieu.p2p.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.hieu.p2p.databinding.ActivityVideoPlayerBinding
import java.io.File

/** Full-screen playback of a received or sent clip (Media3 ExoPlayer). */
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityVideoPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE)
        binding.toolbar.subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        binding.player.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility -> binding.toolbar.visibility = visibility }
        )
    }

    override fun onStart() {
        super.onStart()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        player = ExoPlayer.Builder(this).build().also {
            binding.player.player = it
            it.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"

        fun open(context: Context, file: File, title: String, subtitle: String) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_PATH, file.path).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SUBTITLE, subtitle)
            )
        }
    }
}
