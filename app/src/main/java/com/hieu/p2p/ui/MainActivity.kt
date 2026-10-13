package com.hieu.p2p.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.databinding.ActivityMainBinding
import com.hieu.p2p.databinding.DialogNameBinding
import com.hieu.p2p.service.MeshService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mesh get() = (application as P2pApp).mesh
    private val tabs by lazy { listOf(binding.tabMesh, binding.tabChats, binding.tabBoard) }
    private var currentTab = R.id.tab_mesh

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (MeshPermissions.canRunMesh(this)) {
                MeshService.start(this)
            } else {
                Snackbar.make(binding.root, R.string.permission_denied, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.tabBar)
                    .setAction(R.string.settings) {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        )
                    }
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Content stays out of the status and navigation bars; the tab bar floats just above the latter.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.fragmentContainer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            binding.tabBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + dp(10) }
            insets
        }

        binding.tabBar.capsuleShadow()
        tabs.forEach { tab -> tab.setOnClickListener { selectTab(tab.id) } }
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_mesh)

        if (mesh.displayName.isBlank()) promptName(firstRun = true)

        // Resume the mesh the user left on (e.g. after an app update killed the service).
        if (mesh.wantsRunning && !mesh.node.status.value.running && MeshPermissions.canRunMesh(this)) {
            MeshService.start(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    private fun selectTab(id: Int) {
        currentTab = id
        tabs.forEach { it.isSelected = it.id == id }
        // The chat list is a plain white page; the other tabs are grouped grey pages.
        binding.root.setBackgroundColor(ContextCompat.getColor(this, if (id == R.id.tab_chats) R.color.bg_plain else R.color.bg_grouped))
        showTab(id)
    }

    /** Anchor for snackbars so they sit above the floating tab bar. */
    val snackAnchor: View get() = binding.tabBar

    fun showLog() = LogBottomSheet().show(supportFragmentManager, "log")

    fun toggleMesh() {
        if (mesh.node.status.value.running) {
            MeshService.stop(this)
        } else if (MeshPermissions.missing(this).isEmpty()) {
            MeshService.start(this)
        } else {
            permissionLauncher.launch(MeshPermissions.missing(this))
        }
    }

    private fun showTab(itemId: Int) {
        val tag = "tab_$itemId"
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments.filter { it.tag?.startsWith("tab_") == true }.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.fragment_container, createTab(itemId), tag)
        tx.commit()
    }

    private fun createTab(itemId: Int): Fragment = when (itemId) {
        R.id.tab_chats -> ChatsFragment()
        R.id.tab_board -> BoardFragment()
        else -> MeshFragment()
    }

    fun promptName(firstRun: Boolean) {
        val dialogBinding = DialogNameBinding.inflate(LayoutInflater.from(this))
        dialogBinding.inputName.setText(mesh.displayName)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.name_title)
            .setMessage(R.string.name_message)
            .setView(dialogBinding.root)
            .setCancelable(!firstRun)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.inputName.text?.toString().orEmpty().trim()
                mesh.displayName = name.ifEmpty { getString(R.string.default_name, mesh.myId.short) }
            }
        if (!firstRun) builder.setNegativeButton(R.string.cancel, null)
        builder.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_TAB = "tab"
    }
}
