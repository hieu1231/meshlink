package com.hieu.p2p.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (MeshPermissions.canRunMesh(this)) {
                MeshService.start(this)
            } else {
                Snackbar.make(binding.root, R.string.permission_denied, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomNav)
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
        setSupportActionBar(binding.toolbar)

        // The bottom navigation pads itself for the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        binding.bottomNav.setOnItemSelectedListener {
            showTab(it.itemId)
            updateSubtitle(it.itemId)
            true
        }
        if (savedInstanceState == null) showTab(R.id.nav_mesh)

        if (mesh.displayName.isBlank()) promptName(firstRun = true)

        // Resume the mesh the user left on (e.g. after an app update killed the service).
        if (mesh.wantsRunning && !mesh.node.status.value.running && MeshPermissions.canRunMesh(this)) {
            MeshService.start(this)
        }

        collectWhileStarted(mesh.node.status) { updateSubtitle() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_logs -> {
            LogBottomSheet().show(supportFragmentManager, "log")
            true
        }
        R.id.action_rename -> {
            promptName(firstRun = false)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** The mesh tab states connection status in its own headline; other tabs get it here. */
    private fun updateSubtitle(tab: Int = binding.bottomNav.selectedItemId) {
        val s = mesh.node.status.value
        binding.toolbar.subtitle = when {
            tab == R.id.nav_mesh -> null
            !s.running -> getString(R.string.toolbar_offline)
            s.links == 0 -> getString(R.string.toolbar_searching)
            else -> getString(R.string.toolbar_online, s.links, s.routes.size)
        }
    }

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
        R.id.nav_chats -> ChatsFragment()
        R.id.nav_board -> BoardFragment()
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
}
