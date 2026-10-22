package com.tinydictation.android.settings

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tinydictation.android.BuildConfig
import com.tinydictation.android.R
import com.tinydictation.android.TinyDictationApp
import com.tinydictation.android.update.UpdateRelease
import com.tinydictation.android.update.UpdateState
import kotlinx.coroutines.launch

class UpdateActivity : AppCompatActivity() {
    private val updates get() = (application as TinyDictationApp).updateManager
    private var release: UpdateRelease? = null
    private lateinit var latest: TextView
    private lateinit var notes: TextView
    private lateinit var result: TextView
    private lateinit var installResult: TextView
    private lateinit var progress: ProgressBar
    private lateinit var check: MaterialButton
    private lateinit var download: MaterialButton
    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = updates.state.value as? UpdateState.PermissionRequired
        if (pending != null) updates.resumeAfterPermission(pending.release)
        else updates.check(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLaunched = savedInstanceState?.getBoolean("permission_launched") ?: false
        setContentView(R.layout.activity_update)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.installedVersion).text =
            "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        latest = findViewById(R.id.latestVersion)
        notes = findViewById(R.id.releaseNotes)
        result = findViewById(R.id.updateResult)
        installResult = findViewById(R.id.installResult)
        progress = findViewById(R.id.updateProgress)
        check = findViewById(R.id.checkUpdate)
        download = findViewById(R.id.downloadUpdate)
        check.setOnClickListener { updates.check(force = true) }
        download.setOnClickListener {
            val pending = updates.state.value as? UpdateState.PermissionRequired
            if (pending != null) installPermission.launch(updates.unknownSourcesSettingsIntent())
            else release?.let { updates.install(it) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updates.state.collect { state ->
                    render(state)
                    if (state is UpdateState.PermissionRequired &&
                        !permissionLaunched
                    ) {
                        permissionLaunched = true
                        installPermission.launch(updates.unknownSourcesSettingsIntent())
                    }
                    if (state !is UpdateState.PermissionRequired) permissionLaunched = false
                }
            }
        }
        if (updates.state.value == UpdateState.Idle) updates.check(force = true)
    }

    private var permissionLaunched = false

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_launched", permissionLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun render(state: UpdateState) {
        val currentRelease = when (state) {
            is UpdateState.Current -> state.release
            is UpdateState.Available -> state.release
            is UpdateState.PermissionRequired -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        }
        if (currentRelease != null) release = currentRelease
        latest.text = release?.let { "Latest: ${it.versionName} (${it.versionCode})" }
            ?: "Latest: not checked yet"
        notes.text = release?.let { item ->
            buildString {
                append(item.notes)
                if (item.history.isNotEmpty()) append("\n\nRelease history")
                item.history.forEach {
                    append("\n\n${it.versionName} · ${it.date}\n${it.notes}")
                }
            }
        }.orEmpty()
        result.text = when (state) {
            UpdateState.Idle -> "Updates have not been checked yet."
            UpdateState.Checking -> "Checking for updates…"
            is UpdateState.Current -> "No newer version is available."
            is UpdateState.Available -> "An update is available."
            is UpdateState.PermissionRequired -> "Allow app installs in Android to continue."
            is UpdateState.Downloading -> "Downloading: ${(state.progress * 100).toInt()}%"
            UpdateState.Installing -> "APK verified. Waiting for Android to install the update…"
            is UpdateState.Installed -> state.message
            is UpdateState.Failed -> state.message
        }
        installResult.text = updates.lastInstallResult?.let { "Last install result: $it" }.orEmpty()
        progress.visibility = if (state is UpdateState.Downloading) View.VISIBLE else View.GONE
        progress.progress = if (state is UpdateState.Downloading) (state.progress * 100).toInt() else 0
        val busy = state == UpdateState.Checking || state is UpdateState.Downloading ||
            state == UpdateState.Installing
        check.isEnabled = !busy && state !is UpdateState.PermissionRequired
        download.isEnabled = !busy && (state is UpdateState.Available ||
            state is UpdateState.PermissionRequired || (state is UpdateState.Failed &&
            state.release != null))
    }
}
