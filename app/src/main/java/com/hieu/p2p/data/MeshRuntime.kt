package com.hieu.p2p.data

import android.content.Context
import android.util.Base64
import com.hieu.p2p.mesh.MeshNode
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.NodeStatus
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.transport.NearbyTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * App-wide owner of the mesh node: identity persistence, the single protocol thread and the tick loop.
 */
class MeshRuntime(context: Context) {
    private val prefs = context.getSharedPreferences("mesh", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "mesh-core") }
    private var ticker: ScheduledFuture<*>? = null
    private var statusTicker: ScheduledFuture<*>? = null

    private val appContext = context.applicationContext
    private val database = MeshDatabase(context)
    val images = FileAttachmentStore(context)
    private val knownNames = HashMap<NodeId, String>()

    val identity: Identity = loadOrCreateIdentity()

    val node = MeshNode(
        identity = identity,
        displayName = prefs.getString(KEY_NAME, "").orEmpty(),
        transport = NearbyTransport(context, identity.nodeId.hex),
        opLog = database.opLog,
        messageStore = database.messages,
        executor = executor,
        attachments = images,
    )

    val myId: NodeId get() = identity.nodeId

    val location = LocationTracker(context)

    private val _sharing = MutableStateFlow(prefs.getBoolean(KEY_SHARE_LOCATION, true))
    /** Whether this phone's position goes out in its announces. */
    val sharingLocation: StateFlow<Boolean> = _sharing.asStateFlow()

    /** Audio/video for calls; frames go out through the mesh's encrypted call channel. */
    val callEngine = CallEngine(appContext) { frame -> node.sendCallMedia(frame) }
    private val calls = CallNotifier(appContext, this)

    init {
        node.logSink = { android.util.Log.i("MeshLink", it) }
        location.onUpdate = ::pushStatus
        node.onCallMedia = callEngine::onMedia
        calls.watch()
    }

    fun setSharingLocation(share: Boolean) {
        prefs.edit().putBoolean(KEY_SHARE_LOCATION, share).apply()
        _sharing.value = share
        pushStatus()
    }

    private fun pushStatus() {
        val pos = if (_sharing.value) location.position.value else null
        node.setLocalStatus(NodeStatus(pos, location.batteryPercent))
    }

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, "").orEmpty())
    val name: StateFlow<String> = _name.asStateFlow()

    var displayName: String
        get() = _name.value
        set(value) {
            val name = value.trim().take(32)
            prefs.edit().putString(KEY_NAME, name).apply()
            _name.value = name
            node.setDisplayName(name)
        }

    /** The user's on/off choice, kept so the mesh comes back after an app update or restart. */
    val wantsRunning: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    @Synchronized
    fun start() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        node.start()
        location.start()
        pushStatus()
        if (ticker == null) {
            ticker = executor.scheduleWithFixedDelay({ node.tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
            // Battery changes slowly; refresh it with each minute's worth of ticks.
            statusTicker = executor.scheduleWithFixedDelay({ pushStatus() }, 60, 60, TimeUnit.SECONDS)
        }
    }

    /** User switched the mesh off on purpose. */
    fun turnOff() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        stop()
    }

    @Synchronized
    fun stop() {
        ticker?.cancel(false)
        ticker = null
        statusTicker?.cancel(false)
        statusTicker = null
        location.stop()
        node.stop()
    }

    /** Display name of a node; names learned from announces are remembered across restarts. */
    @Synchronized
    fun nameOf(id: NodeId): String {
        if (id == myId) return displayName.ifBlank { id.short }
        val live = node.peerName(id)
        if (!live.isNullOrBlank()) {
            if (knownNames.put(id, live) != live) prefs.edit().putString(KEY_PEER_PREFIX + id.hex, live).apply()
            return live
        }
        return knownNames[id] ?: prefs.getString(KEY_PEER_PREFIX + id.hex, null)?.also { knownNames[id] = it } ?: id.short
    }

    fun postPublic(text: String) {
        val key = "${System.currentTimeMillis()}-${myId.hex}-${Crypto.randomLong().toULong().toString(36)}"
        node.put(PublicChat.COLLECTION, key, PublicChat.encode(displayName, text))
    }

    fun postBoard(key: String?, category: BoardCategory, title: String, detail: String) {
        val itemKey = key ?: "${myId.hex}-${Crypto.randomLong().toULong().toString(36)}"
        val here = if (_sharing.value) location.position.value else null
        node.put(Board.COLLECTION, itemKey, Board.encode(category, title, detail, myId, displayName, here))
    }

    /** Compresses the picked photo; call off the main thread. Null if it could not be read. */
    fun prepareImage(uri: android.net.Uri): ByteArray? {
        val started = System.currentTimeMillis()
        return runCatching { ImageCompressor.compress(appContext, uri) }
            .onFailure { node.log("Nén ảnh lỗi: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
            .getOrNull()
            .also {
                if (it == null) node.log("Không đọc được ảnh đã chọn")
                else node.log("Nén ảnh xong: ${it.size / 1024} KB trong ${System.currentTimeMillis() - started} ms")
            }
    }

    fun sendImage(peer: NodeId, jpeg: ByteArray, caption: String) = node.sendImage(peer, jpeg, caption)

    fun sendVoice(peer: NodeId, voice: VoiceRecorder.Result) =
        node.sendAttachment(peer, DmKind.VOICE, voice.bytes, "", voice.durationMs, voice.waveform)

    fun sendVideo(peer: NodeId, video: VideoCompressor.Result, caption: String) =
        node.sendAttachment(peer, DmKind.VIDEO, video.bytes, caption, video.durationMs, video.thumbnail)

    fun deleteBoard(key: String) = node.put(Board.COLLECTION, key, null)

    /** One status item per person: re-posting replaces it everywhere (LWW register keyed by node). */
    fun postSafe(title: String, detail: String) =
        postBoard("status-${myId.hex}", BoardCategory.SAFE, title, detail)

    val isConnected: Boolean get() = node.status.value.links > 0

    private fun loadOrCreateIdentity(): Identity {
        val stored = prefs.getString(KEY_IDENTITY, null)
        if (stored != null) {
            runCatching {
                return Identity.restore(stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) })
            }
        }
        val identity = Identity.generate()
        val encoded = identity.export().joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
        prefs.edit().putString(KEY_IDENTITY, encoded).apply()
        return identity
    }

    companion object {
        private const val KEY_IDENTITY = "identity"
        private const val KEY_NAME = "display_name"
        private const val KEY_ENABLED = "mesh_enabled"
        private const val KEY_SHARE_LOCATION = "share_location"
        private const val KEY_PEER_PREFIX = "peer_name_"
        private const val TICK_MS = 2_000L
    }
}
