package com.hieu.p2p.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.hieu.p2p.databinding.ActivityVideoPlayerBinding
import java.io.File

/** Full-screen playback of a received or sent clip (Media3 ExoPlayer). */
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityVideoPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val header = binding.toolbar
        ViewCompat.setOnApplyWindowInsetsListener(header.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left + dp(10), top = bars.top + dp(6), right = bars.right + dp(10))
            insets
        }
        header.buttonBack.setOnClickListener { finish() }
        header.textTitle.text = intent.getStringExtra(EXTRA_TITLE)
        header.textSubtitle.text = intent.getStringExtra(EXTRA_SUBTITLE)
        binding.player.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility -> header.root.visibility = visibility }
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
