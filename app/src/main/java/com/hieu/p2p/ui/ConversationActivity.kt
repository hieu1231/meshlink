package com.hieu.p2p.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.data.ImageCompressor
import com.hieu.p2p.data.PublicChat
import com.hieu.p2p.data.VideoCompressor
import com.hieu.p2p.data.VoiceRecorder
import com.hieu.p2p.databinding.ActivityConversationBinding
import com.hieu.p2p.databinding.BottomSheetAttachBinding
import com.hieu.p2p.databinding.DialogImagePreviewBinding
import com.hieu.p2p.databinding.ItemMsgDayBinding
import com.hieu.p2p.databinding.ItemMsgImageBinding
import com.hieu.p2p.databinding.ItemMsgTextBinding
import com.hieu.p2p.databinding.ItemMsgVoiceBinding
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.DmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Chat screen for the public channel (CRDT-replicated, text only, everyone reads it) and for a
 * direct chat (end-to-end encrypted text, photos, voice notes and video clips).
 */
class ConversationActivity : AppCompatActivity() {

    private data class UiMessage(
        val id: Long,
        val key: String,
        val text: String,
        val outgoing: Boolean,
        val sender: String?,
        val senderId: NodeId?,
        val time: Long,
        val status: DmStatus?,
        val kind: DmKind = DmKind.TEXT,
        val progress: Int = 0,
        val chunks: Int = 0,
        val bytes: Int = 0,
        val durationMs: Int = 0,
        val preview: ByteArray? = null,
    ) {
        /** The file is fully here (sent by us, or received completely). */
        val ready: Boolean get() = outgoing || status == DmStatus.RECEIVED
    }

    /** A row in the list: a day separator, or a message with its place in a same-sender group. */
    private sealed class Row(val key: String) {
        class Day(val label: String) : Row("day-$label")
        class Msg(val m: UiMessage, val first: Boolean, val last: Boolean) : Row(m.key)
    }

    private lateinit var binding: ActivityConversationBinding
    private lateinit var adapter: MessageAdapter
    private val mesh get() = (application as P2pApp).mesh
    private var peer: NodeId? = null
    private val voicePlayer = VoicePlayer { id, _, _ -> adapter.refreshVoice(id) }

    // ---- recording state
    private var recorder: VoiceRecorder? = null
    private var recordDownX = 0f
    private var cancelArmed = false
    private val ui = Handler(Looper.getMainLooper())
    private val recordTicker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            binding.recordingTime.text = Formats.duration(r.elapsedMs.toInt())
            if (r.elapsedMs >= VoiceRecorder.MAX_MS) finishRecording(keep = true) else ui.postDelayed(this, 200)
        }
    }

    // ---- pickers
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        mesh.node.log(if (uri == null) "Đã hủy chọn ảnh" else "Đã chọn ảnh, đang nén")
        if (uri != null) prepareImage(uri)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepareVideo(uri, deleteAfter = null)
    }
    private var captureFile: File? = null
    private val captureVideo = registerForActivityResult(CaptureVideo()) { ok ->
        val file = captureFile
        if (ok && file != null && file.length() > 0) prepareVideo(Uri.fromFile(file), deleteAfter = file) else file?.delete()
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        snack(if (granted) R.string.voice_hold_hint else R.string.voice_permission)
    }
    private var pendingCallVideo: Boolean? = null
    private val callPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val video = pendingCallVideo ?: return@registerForActivityResult
        pendingCallVideo = null
        val peer = peer ?: return@registerForActivityResult
        if (result[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)) {
            CallActivity.dial(this, peer, video && (result[Manifest.permission.CAMERA] == true || has(Manifest.permission.CAMERA)))
        } else {
            snack(R.string.call_permission)
        }
    }
    private val cameraForCapture = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture() else snack(R.string.call_permission)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun placeCall(peer: NodeId, video: Boolean) {
        val needed = buildList {
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            CallActivity.dial(this, peer, video)
        } else {
            pendingCallVideo = video
            callPermissions.launch(needed.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        peer = intent.getStringExtra(EXTRA_PEER)?.let(NodeId::fromHex)
        val peer = peer

        // Messages scroll under the floating header; the composer rides on the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            binding.headerBar.updatePadding(top = bars.top + dp(6))
            WindowInsetsCompat.CONSUMED
        }
        binding.headerBar.addOnLayoutChangeListener { header, _, _, _, _, _, _, _, _ ->
            val top = header.height - dp(6)
            if (binding.listMessages.paddingTop != top) binding.listMessages.post { binding.listMessages.updatePadding(top = top) }
        }

        binding.buttonBack.setOnClickListener { finish() }
        if (peer == null) {
            binding.headerTitle.text = getString(R.string.public_channel)
            binding.headerAvatarBox.isVisible = true
            binding.headerAvatar.bindChannelAvatar()
            binding.headerIcon.isVisible = true
        } else {
            binding.header.setOnClickListener { PeerSheet.newInstance(peer).show(supportFragmentManager, "peer") }
            binding.callGroup.isVisible = true
            binding.headerSubtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_lock_small, 0, 0, 0)
            binding.buttonVoiceCall.setOnClickListener { placeCall(peer, video = false) }
            binding.buttonVideoCall.setOnClickListener { placeCall(peer, video = true) }
        }

        adapter = MessageAdapter(
            scope = lifecycleScope,
            showAvatars = peer == null,
            fileFor = mesh.images::file,
            player = voicePlayer,
            onOpen = ::openMedia,
            onPlayVoice = { m -> voicePlayer.toggle(m.id, mesh.images.file(m.id)) },
        )
        binding.listMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.listMessages.adapter = adapter
        binding.listMessages.itemAnimator = null
        binding.emptyMessages.text = getString(if (peer == null) R.string.public_channel_empty else R.string.conversation_empty_dm)

        collectWhileStarted(rows(peer)) { list ->
            val atBottom = !binding.listMessages.canScrollVertically(1)
            adapter.submitList(list) {
                if (list.isNotEmpty() && atBottom) binding.listMessages.scrollToPosition(list.size - 1)
            }
            binding.emptyMessages.isVisible = list.isEmpty()
        }

        collectWhileStarted(mesh.node.status) { s ->
            if (peer == null) {
                binding.headerSubtitle.text = getString(R.string.conversation_public_subtitle)
                return@collectWhileStarted
            }
            val name = mesh.nameOf(peer)
            binding.headerTitle.text = name
            binding.headerAvatar.bindAvatar(name, peer)
            val route = s.routes.firstOrNull { it.dest == peer }
            binding.headerSubtitle.text = when {
                route == null -> getString(R.string.conversation_dm_no_route)
                route.isNeighbor || route.viaNode == null -> getString(R.string.conversation_dm_direct)
                else -> getString(R.string.conversation_dm_route, mesh.nameOf(route.viaNode), route.hops)
            }
        }

        setupComposer(peer)
    }

    override fun onStop() {
        super.onStop()
        voicePlayer.release()
        if (recorder != null) finishRecording(keep = false)
    }

    // ---- data → rows ------------------------------------------------------------------------------

    private fun rows(peer: NodeId?): Flow<List<Row>> {
        val runtime = mesh
        val node = runtime.node
        val me = runtime.myId
        val dayFormat = SimpleDateFormat("EEEE, d 'tháng' M", Locale("vi"))
        val today = getString(R.string.day_today)
        val yesterday = getString(R.string.day_yesterday)
        val source: Flow<List<UiMessage>> = if (peer == null) {
            node.dataVersion.map {
                PublicChat.list(node.data).map { m ->
                    UiMessage(0, m.key, m.text, m.author == me, m.authorName.ifBlank { m.author.short }, m.author, m.time, null)
                }
            }
        } else {
            node.messagesVersion.map {
                node.conversation(peer).map { m ->
                    UiMessage(
                        m.id, m.id.toString(), m.text, m.outgoing, null, null, m.timestamp, m.status,
                        m.kind, m.progress, m.chunks, m.imageBytes, m.durationMs, m.preview,
                    )
                }
            }
        }
        return source.map { messages ->
            val out = ArrayList<Row>(messages.size + 8)
            var lastDay = ""
            messages.forEachIndexed { i, m ->
                val label = when {
                    DateUtils.isToday(m.time) -> today
                    DateUtils.isToday(m.time + DateUtils.DAY_IN_MILLIS) -> yesterday
                    else -> dayFormat.format(Date(m.time)).replaceFirstChar { it.uppercase() }
                }
                if (label != lastDay) {
                    out += Row.Day(label)
                    lastDay = label
                }
                val prev = messages.getOrNull(i - 1)
                val next = messages.getOrNull(i + 1)
                out += Row.Msg(m, first = prev == null || !sameGroup(prev, m), last = next == null || !sameGroup(m, next))
            }
            out
        }.flowOn(Dispatchers.Default)
    }

    /** Consecutive messages from the same person within 3 minutes on the same day read as one group. */
    private fun sameGroup(a: UiMessage, b: UiMessage): Boolean =
        a.outgoing == b.outgoing && a.senderId == b.senderId && b.time - a.time < 3 * 60_000 && sameDay(a.time, b.time)

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // ---- composer ---------------------------------------------------------------------------------

    private fun hasText() = !binding.inputMessage.text.isNullOrBlank()

    private fun setupComposer(peer: NodeId?) {
        // Media only in direct chats: flooding a clip to everyone would choke the mesh.
        binding.buttonAttach.isVisible = peer != null
        binding.buttonAttach.setOnClickListener { showAttachSheet() }

        fun syncAction() {
            val send = hasText() || peer == null
            binding.buttonAction.setIconResource(if (send) R.drawable.ic_send else R.drawable.ic_mic)
            binding.buttonAction.contentDescription = getString(if (send) R.string.send else R.string.voice_hold)
            binding.buttonAction.isEnabled = !(peer == null && !hasText())
        }
        binding.inputMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = syncAction()
        })
        syncAction()

        binding.buttonAction.setOnClickListener {
            if (hasText()) send() else if (peer != null && recorder == null) snack(R.string.voice_hold_hint)
        }
        // Hold to record, slide left to cancel, release to send.
        binding.buttonAction.setOnTouchListener { v, e ->
            if (peer == null || hasText()) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    recordDownX = e.rawX
                    startRecording()
                }
                MotionEvent.ACTION_MOVE -> if (recorder != null) {
                    val armed = e.rawX - recordDownX < -dp(110)
                    if (armed != cancelArmed) {
                        cancelArmed = armed
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        binding.recordingHint.setText(if (armed) R.string.voice_release_cancel else R.string.voice_slide_cancel)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (recorder != null) finishRecording(keep = !cancelArmed) else v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> if (recorder != null) finishRecording(keep = false)
            }
            true
        }
    }

    private fun send() {
        val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val peer = peer
        if (peer == null) mesh.postPublic(text) else mesh.node.sendDirect(peer, text)
        binding.inputMessage.setText("")
    }

    private fun startRecording() {
        val r = VoiceRecorder(this)
        r.onLevel = { level -> binding.recordingDot.alpha = 0.35f + 0.65f * level }
        if (!r.start()) {
            snack(R.string.voice_failed)
            return
        }
        recorder = r
        cancelArmed = false
        voicePlayer.release()
        binding.buttonAction.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.composer.visibility = View.INVISIBLE
        binding.recordingBar.isVisible = true
        binding.recordingHint.setText(R.string.voice_slide_cancel)
        binding.recordingTime.text = Formats.duration(0)
        binding.buttonAction.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start()
        ui.post(recordTicker)
    }

    private fun finishRecording(keep: Boolean) {
        val r = recorder ?: return
        recorder = null
        ui.removeCallbacks(recordTicker)
        binding.recordingBar.isVisible = false
        binding.composer.visibility = View.VISIBLE
        binding.buttonAction.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        val result = r.stop(keep)
        val peer = peer
        if (result != null && peer != null) {
            binding.buttonAction.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            mesh.sendVoice(peer, result)
        }
    }

    // ---- attachments ------------------------------------------------------------------------------

    private fun showAttachSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = BottomSheetAttachBinding.inflate(layoutInflater)
        sheet.attachPhoto.setOnClickListener {
            dialog.dismiss()
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        sheet.attachVideo.setOnClickListener {
            dialog.dismiss()
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        sheet.attachRecord.setOnClickListener {
            dialog.dismiss()
            // We declare CAMERA (for calls), so Android requires it granted before the capture intent too.
            if (has(Manifest.permission.CAMERA)) launchCapture() else cameraForCapture.launch(Manifest.permission.CAMERA)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun launchCapture() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "clip-${System.currentTimeMillis()}.mp4")
        captureFile = file
        runCatching { captureVideo.launch(FileProvider.getUriForFile(this, "$packageName.files", file)) }
            .onFailure { snack(R.string.video_failed) }
    }

    private fun prepareImage(uri: Uri) {
        val peer = peer ?: return
        val busy = Snackbar.make(binding.root, R.string.image_preparing, Snackbar.LENGTH_INDEFINITE).also { it.show() }
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.IO) { mesh.prepareImage(uri) }
            busy.dismiss()
            if (jpeg == null) {
                snack(R.string.image_unreadable)
                return@launch
            }
            val preview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            confirm(
                title = getString(R.string.image_preview_title, mesh.nameOf(peer)),
                bitmap = preview,
                estimate = estimate(peer, jpeg.size, null),
                action = R.string.image_send,
            ) { caption -> mesh.sendImage(peer, jpeg, caption) }
        }
    }

    private fun prepareVideo(uri: Uri, deleteAfter: File?) {
        val peer = peer ?: return
        val busy = Snackbar.make(binding.root, R.string.video_preparing, Snackbar.LENGTH_INDEFINITE).also { it.show() }
        lifecycleScope.launch {
            val video = runCatching { VideoCompressor.compress(this@ConversationActivity, uri) }.getOrNull()
            deleteAfter?.delete()
            busy.dismiss()
            if (video == null || video.bytes.size > DmKind.VIDEO.maxBytes) {
                mesh.node.log("Nén video thất bại" + (video?.let { " (${it.bytes.size / 1024} KB, quá giới hạn)" } ?: ""))
                snack(R.string.video_failed)
                return@launch
            }
            mesh.node.log("Nén video xong: ${video.bytes.size / 1024} KB, ${video.durationMs / 1000} giây")
            val thumb = video.thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            confirm(
                title = getString(R.string.video_preview_title, mesh.nameOf(peer)),
                bitmap = thumb,
                estimate = estimate(peer, video.bytes.size, video.durationMs),
                action = R.string.video_send,
            ) { caption -> mesh.sendVideo(peer, video, caption) }
        }
    }

    private fun estimate(peer: NodeId, bytes: Int, durationMs: Int?): String {
        val kb = (bytes + 1023) / 1024
        val route = mesh.node.status.value.routes.firstOrNull { it.dest == peer }
        // Rough Bluetooth throughput per hop: ~20–60 KB/s.
        val slow = route?.let { (kb * it.hops / 20.0).toInt().coerceAtLeast(1) } ?: 0
        val fast = route?.let { (kb * it.hops / 60.0).toInt().coerceAtLeast(1) } ?: 0
        if (durationMs == null) {
            return if (route == null) getString(R.string.image_estimate_no_route, kb)
            else getString(R.string.image_estimate, kb, route.hops, fast, slow)
        }
        val size = Formats.bytes(bytes)
        val length = Formats.duration(durationMs)
        return if (route == null) getString(R.string.video_estimate_no_route, size, length)
        else getString(R.string.video_estimate, size, length, route.hops, fast, slow)
    }

    private fun confirm(title: String, bitmap: Bitmap?, estimate: String, action: Int, onSend: (String) -> Unit) {
        val form = DialogImagePreviewBinding.inflate(layoutInflater)
        form.image.setImageBitmap(bitmap)
        form.textEstimate.text = estimate
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(form.root)
            .setPositiveButton(action) { _, _ -> onSend(form.inputCaption.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(R.string.cancel) { _, _ -> mesh.node.log("Đã hủy gửi ở bước xem trước") }
            .show()
    }

    private fun openMedia(m: UiMessage) {
        val file = mesh.images.file(m.id)
        if (!m.ready || !file.exists()) return
        val who = if (m.outgoing) mesh.displayName else peer?.let(mesh::nameOf).orEmpty()
        when (m.kind) {
            DmKind.VIDEO -> VideoPlayerActivity.open(this, file, who, "${Formats.time(m.time)}, ${Formats.bytes(m.bytes)}")
            else -> ImageViewerActivity.open(this, file, who, m.time, m.bytes)
        }
    }

    private fun snack(res: Int) = Snackbar.make(binding.root, res, Snackbar.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** ACTION_VIDEO_CAPTURE with a 30 s cap; the camera app writes into our FileProvider URI. */
    private class CaptureVideo : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(context: Context, input: Uri) = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, (VideoCompressor.MAX_MS / 1000).toInt())
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
    }

    // ---- list -------------------------------------------------------------------------------------

    private class MessageAdapter(
        private val scope: CoroutineScope,
        /** Public channel: show who wrote each run of messages. */
        private val showAvatars: Boolean,
        private val fileFor: (Long) -> File,
        private val player: VoicePlayer,
        private val onOpen: (UiMessage) -> Unit,
        private val onPlayVoice: (UiMessage) -> Unit,
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

        class DayHolder(val b: ItemMsgDayBinding) : RecyclerView.ViewHolder(b.root)
        class TextHolder(val b: ItemMsgTextBinding) : RecyclerView.ViewHolder(b.root)
        class MediaHolder(val b: ItemMsgImageBinding) : RecyclerView.ViewHolder(b.root)
        class VoiceHolder(val b: ItemMsgVoiceBinding) : RecyclerView.ViewHolder(b.root)

        private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun refreshVoice(id: Long) {
            val index = currentList.indexOfFirst { it is Row.Msg && it.m.id == id }
            if (index >= 0) notifyItemChanged(index, PLAYBACK)
        }

        override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
            is Row.Day -> DAY
            is Row.Msg -> when (row.m.kind) {
                DmKind.TEXT -> TEXT
                DmKind.VOICE -> VOICE
                DmKind.IMAGE, DmKind.VIDEO -> MEDIA
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                DAY -> DayHolder(ItemMsgDayBinding.inflate(inf, parent, false))
                TEXT -> TextHolder(ItemMsgTextBinding.inflate(inf, parent, false))
                VOICE -> VoiceHolder(ItemMsgVoiceBinding.inflate(inf, parent, false))
                else -> MediaHolder(ItemMsgImageBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PLAYBACK) && holder is VoiceHolder) {
                bindPlayback(holder, (getItem(position) as Row.Msg).m)
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = getItem(position)
            if (row is Row.Day) {
                (holder as DayHolder).b.textDay.text = row.label
                return
            }
            val msg = row as Row.Msg
            when (holder) {
                is TextHolder -> bindText(holder.b, msg)
                is MediaHolder -> bindMedia(holder.b, msg)
                is VoiceHolder -> bindVoice(holder, msg)
            }
        }

        // -- shared styling

        private class Palette(val text: Int, val meta: Int)

        /** Aligns the row, picks the bubble shape for its place in the group, returns text colours. */
        private fun style(row: LinearLayout, bubble: View, msg: Row.Msg): Palette {
            val ctx = row.context
            val d = ctx.resources.displayMetrics.density
            val out = msg.m.outgoing
            row.gravity = if (out) android.view.Gravity.END else android.view.Gravity.START
            val side = (56 * d).toInt()
            val edge = ((if (showAvatars && !out) 8 else 12) * d).toInt()
            row.setPaddingRelative(if (out) side else edge, ((if (msg.first) 8 else 2) * d).toInt(), if (out) edge else side, 0)
            bubble.setBackgroundResource(
                when {
                    out && msg.last -> R.drawable.bg_bubble_out
                    out -> R.drawable.bg_bubble_out_mid
                    msg.last -> R.drawable.bg_bubble_in
                    else -> R.drawable.bg_bubble_in_mid
                }
            )
            val text = ContextCompat.getColor(ctx, if (out) R.color.on_bubble_out else R.color.on_bubble_in)
            return Palette(text, if (out) (text and 0x00FFFFFF) or (0xBF shl 24) else ContextCompat.getColor(ctx, R.color.label_secondary))
        }

        private fun bindMeta(time: TextView, icon: ImageView, m: UiMessage, p: Palette) {
            time.text = clock.format(Date(m.time))
            time.setTextColor(p.meta)
            val ctx = icon.context
            val (res, cd) = when (m.status?.takeIf { m.outgoing }) {
                DmStatus.QUEUED -> R.drawable.ic_schedule to R.string.status_cd_queued
                DmStatus.SENT -> R.drawable.ic_check to R.string.status_cd_sent
                DmStatus.DELIVERED -> R.drawable.ic_done_all to R.string.status_cd_delivered
                else -> 0 to 0
            }
            icon.isVisible = res != 0
            if (res != 0) {
                icon.setImageResource(res)
                icon.contentDescription = ctx.getString(cd)
                // Delivered ticks at full strength: the one state worth noticing at a glance.
                icon.imageTintList = ColorStateList.valueOf(if (m.status == DmStatus.DELIVERED) p.text else p.meta)
            }
        }

        // -- text

        private fun bindText(b: ItemMsgTextBinding, msg: Row.Msg) {
            val m = msg.m
            val p = style(b.row, b.bubble, msg)
            b.textBody.text = m.text
            b.textBody.setTextColor(p.text)
            val showSender = !m.outgoing && m.sender != null && msg.first
            b.textSender.isVisible = showSender
            if (showSender) {
                b.textSender.text = m.sender
                m.senderId?.let { b.textSender.setTextColor(avatarTextColor(b.root.context, it)) }
            }
            // Avatar at the foot of each run; earlier bubbles keep its space so the run lines up.
            val withAvatar = showAvatars && !m.outgoing
            b.avatar.visibility = when {
                !withAvatar -> View.GONE
                msg.last -> View.VISIBLE
                else -> View.INVISIBLE
            }
            if (withAvatar && msg.last) m.senderId?.let { b.avatar.bindAvatar(m.sender.orEmpty(), it) }
            bindMeta(b.textTime, b.iconStatus, m, p)
        }

        // -- photo / video

        private fun bindMedia(b: ItemMsgImageBinding, msg: Row.Msg) {
            val m = msg.m
            val p = style(b.row, b.bubble, msg)
            val ctx = b.root.context
            val isVideo = m.kind == DmKind.VIDEO
            b.textCaption.text = m.text
            b.textCaption.isVisible = m.text.isNotBlank()
            b.textCaption.setTextColor(p.text)
            bindMeta(b.textTime, b.iconStatus, m, p)

            b.iconPlay.isVisible = isVideo && m.ready
            // While downloading, the length goes into the progress line instead of a chip under it.
            b.textDuration.isVisible = isVideo && m.durationMs > 0 && m.ready
            b.textDuration.text = Formats.duration(m.durationMs)
            b.progressBox.isVisible = !m.ready
            if (!m.ready) {
                b.progress.max = m.chunks.coerceAtLeast(1)
                b.progress.setProgressCompat(m.progress, true)
                val state = if (m.progress == 0) ctx.getString(R.string.media_waiting)
                else ctx.getString(R.string.media_receiving, m.progress, m.chunks)
                b.textProgress.text = if (isVideo && m.durationMs > 0) ctx.getString(R.string.media_receiving_video, state, Formats.duration(m.durationMs)) else state
            }
            b.image.contentDescription = ctx.getString(if (isVideo) R.string.preview_video else R.string.image_cd)
            b.image.setOnClickListener { onOpen(m) }

            // Video: the thumbnail arrived in the header. Photo: the file itself, once complete.
            val preview = m.preview
            when {
                isVideo && preview != null -> loadBitmap(b.image, "t${m.id}") { BitmapFactory.decodeByteArray(preview, 0, preview.size) }
                !isVideo && m.ready -> {
                    val target = b.image.resources.getDimensionPixelSize(R.dimen.photo_width)
                    loadBitmap(b.image, "i${m.id}") { ImageCompressor.decodeForDisplay(fileFor(m.id), target) }
                }
                else -> {
                    b.image.tag = null
                    b.image.setImageDrawable(null)
                }
            }
        }

        private fun loadBitmap(view: ImageView, key: String, decode: () -> Bitmap?) {
            view.tag = key
            cache.get(key)?.let {
                view.setImageBitmap(it)
                return
            }
            view.setImageDrawable(null)
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching(decode).getOrNull() } ?: return@launch
                cache.put(key, bitmap)
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }

        // -- voice

        private fun bindVoice(h: VoiceHolder, msg: Row.Msg) {
            val b = h.b
            val m = msg.m
            val p = style(b.row, b.bubble, msg)
            val ctx = b.root.context
            bindMeta(b.textTime, b.iconStatus, m, p)
            b.textDuration.setTextColor(p.meta)
            b.waveform.setLevels(m.preview)
            // Outgoing: white disc on the blue bubble. Incoming: blue disc on the grey bubble.
            val accent = ContextCompat.getColor(ctx, R.color.accent)
            if (m.outgoing) {
                b.buttonPlay.backgroundTintList = ColorStateList.valueOf(p.text)
                b.buttonPlay.imageTintList = ColorStateList.valueOf(accent)
                b.waveform.playedColor = p.text
                b.waveform.idleColor = (p.text and 0x00FFFFFF) or (0x66 shl 24)
            } else {
                b.buttonPlay.backgroundTintList = ColorStateList.valueOf(accent)
                b.buttonPlay.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.on_accent))
                b.waveform.playedColor = accent
                b.waveform.idleColor = ContextCompat.getColor(ctx, R.color.label_tertiary)
            }
            b.buttonPlay.isEnabled = m.ready
            b.buttonPlay.alpha = if (m.ready) 1f else 0.45f
            b.buttonPlay.setOnClickListener { onPlayVoice(m) }
            bindPlayback(h, m)
        }

        private fun bindPlayback(h: VoiceHolder, m: UiMessage) {
            val b = h.b
            val ctx = b.root.context
            val mine = player.currentId == m.id
            val playing = mine && player.isPlaying
            b.buttonPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            b.buttonPlay.contentDescription = ctx.getString(if (playing) R.string.voice_pause else R.string.voice_play)
            b.waveform.progress = if (mine && m.durationMs > 0) player.positionMs.toFloat() / m.durationMs else 0f
            b.textDuration.text = when {
                !m.ready -> if (m.progress == 0) ctx.getString(R.string.media_waiting) else ctx.getString(R.string.media_receiving, m.progress, m.chunks)
                mine && player.positionMs > 0 -> Formats.duration(player.positionMs)
                else -> Formats.duration(m.durationMs)
            }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Day && b is Row.Day -> a.label == b.label
                a is Row.Msg && b is Row.Msg -> a.m == b.m && a.first == b.first && a.last == b.last
                else -> false
            }
        }

        companion object {
            const val DAY = 0
            const val TEXT = 1
            const val MEDIA = 2
            const val VOICE = 3
            private val PLAYBACK = Any()
        }
    }

    companion object {
        private const val EXTRA_PEER = "peer"

        fun openPublic(context: Context) {
            context.startActivity(Intent(context, ConversationActivity::class.java))
        }

        fun openDirect(context: Context, peer: NodeId) {
            context.startActivity(Intent(context, ConversationActivity::class.java).putExtra(EXTRA_PEER, peer.hex))
        }
    }
}
