package com.hieu.p2p.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.hieu.p2p.R
import com.hieu.p2p.data.ImageCompressor
import com.hieu.p2p.databinding.ActivityImageViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen photo from a direct message. */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = intent.getStringExtra(EXTRA_WHO)
        binding.toolbar.subtitle = getString(
            R.string.image_viewer_subtitle,
            Formats.time(intent.getLongExtra(EXTRA_TIME, 0)),
            (intent.getIntExtra(EXTRA_BYTES, 0) + 1023) / 1024,
        )
        binding.image.setOnClickListener {
            binding.toolbar.animate().alpha(if (binding.toolbar.alpha > 0f) 0f else 1f).setDuration(150).start()
        }

        val file = File(intent.getStringExtra(EXTRA_PATH) ?: return finish())
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageCompressor.decodeForDisplay(file, target) }
            if (bitmap == null) finish() else binding.image.setImageBitmap(bitmap)
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_WHO = "who"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_BYTES = "bytes"

        fun open(context: Context, file: File, who: String, time: Long, bytes: Int) {
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_PATH, file.path)
                    .putExtra(EXTRA_WHO, who)
                    .putExtra(EXTRA_TIME, time)
                    .putExtra(EXTRA_BYTES, bytes)
            )
        }
    }
}
