package com.tinydictation.android.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tinydictation.android.R
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Activity displaying app information, changelog, debug tools, and app actions.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        // Changelog
        private val CHANGELOG = """
            v1.14.7:
            • NEW: Cursor keys now support hold-to-repeat
            • Same acceleration behavior as backspace
            • Hold arrow keys to move cursor continuously

            v1.14.6:
            • FIX: Language switching no longer causes app freeze
            • Fixed state sync when restarting mic for language change

            v1.14.0:
            • NEW: Redesigned Settings screen with clean category list
            • NEW: Voice Commands screen showing all available commands
            • Each settings category now opens its own dedicated screen
            • Big headline, icon + title + subtitle for each category
            • Easier navigation and cleaner organization

            v1.13.1:
            • FIX: Voice commands now work with trailing punctuation
            • "Scratch that." now properly detected (period stripped)

            v1.13.0:
            • NEW: Voice commands for hands-free control
            • Say "scratch that" or "delete that" to clear all text
            • Say "undo that" to delete the last word
            • Say "new line" or "new paragraph" for line breaks
            • Punctuation: "period", "comma", "question mark", etc.
            • Quotes/brackets: "open quote", "close paren", etc.

            v1.12.6:
            • FIX: App no longer freezes when toggling pause mode
            • Fixed race condition in Deepgram connection state management

            v1.12.5:
            • NEW: Clipboard tab now shows clipboard history
            • Copies are automatically captured and saved
            • Tap any clipboard item to paste directly
        """.trimIndent()
    }

    private lateinit var versionInfo: TextView
    private lateinit var changelog: TextView
    private lateinit var changelogHeader: LinearLayout
    private lateinit var changelogExpandIcon: ImageView
    private lateinit var copyDebugLogButton: MaterialButton
    private lateinit var shareDebugLogButton: MaterialButton
    private lateinit var restartAppButton: MaterialButton

    private var isChangelogExpanded = false

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        versionInfo = findViewById(R.id.tvVersionInfo)
        changelog = findViewById(R.id.tvChangelog)
        changelogHeader = findViewById(R.id.changelogHeader)
        changelogExpandIcon = findViewById(R.id.ivChangelogExpand)
        copyDebugLogButton = findViewById(R.id.btnCopyDebugLog)
        shareDebugLogButton = findViewById(R.id.btnShareDebugLog)
        restartAppButton = findViewById(R.id.btnRestartApp)

        displayVersionInfo()
        changelog.text = CHANGELOG
    }

    private fun setupClickListeners() {
        changelogHeader.setOnClickListener {
            toggleChangelog()
        }

        copyDebugLogButton.setOnClickListener {
            copyDebugLogToClipboard()
        }

        shareDebugLogButton.setOnClickListener {
            shareDebugLog()
        }

        restartAppButton.setOnClickListener {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun displayVersionInfo() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            versionInfo.text = "Version $versionName ($versionCode)"
        } catch (e: PackageManager.NameNotFoundException) {
            versionInfo.text = "Version unknown"
        }
    }

    private fun toggleChangelog() {
        isChangelogExpanded = !isChangelogExpanded
        if (isChangelogExpanded) {
            changelog.maxLines = Int.MAX_VALUE
            changelogExpandIcon.rotation = 180f
        } else {
            changelog.maxLines = 6
            changelogExpandIcon.rotation = 0f
        }
    }

    private fun copyDebugLogToClipboard() {
        val logText = DebugLog.exportLog()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Tiny Dictation Debug Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareDebugLog() {
        val logText = DebugLog.exportLog()

        if (logText.isBlank()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        shareDebugLogButton.isEnabled = false
        shareDebugLogButton.text = getString(R.string.uploading_logs)

        lifecycleScope.launch {
            try {
                val url = uploadToPaste(logText)
                if (url != null) {
                    showShareDialog(url)
                } else {
                    Toast.makeText(this@AboutActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DebugLog.e("Failed to upload logs: ${e.message}")
                Toast.makeText(this@AboutActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
            } finally {
                shareDebugLogButton.isEnabled = true
                shareDebugLogButton.text = getString(R.string.share_debug_log)
            }
        }
    }

    private suspend fun uploadToPaste(content: String): String? = withContext(Dispatchers.IO) {
        uploadToDpaste(content) ?: uploadToPasteRs(content)
    }

    private fun uploadToDpaste(content: String): String? {
        return try {
            val formBody = okhttp3.FormBody.Builder()
                .add("content", content)
                .build()

            val request = Request.Builder()
                .url("https://dpaste.com/api/")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val url = response.body?.string()?.trim()
                DebugLog.i("dpaste.com upload successful: $url")
                url
            } else {
                DebugLog.e("dpaste.com upload failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            DebugLog.e("dpaste.com upload exception: ${e.message}")
            null
        }
    }

    private fun uploadToPasteRs(content: String): String? {
        return try {
            val requestBody = content.toRequestBody("text/plain".toMediaType())
            val request = Request.Builder()
                .url("https://paste.rs/")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val url = response.body?.string()?.trim()
                DebugLog.i("paste.rs upload successful: $url")
                url
            } else {
                DebugLog.e("paste.rs upload failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            DebugLog.e("paste.rs upload exception: ${e.message}")
            null
        }
    }

    private fun showShareDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Logs Uploaded")
            .setMessage("Your logs have been uploaded to:\n\n$url")
            .setPositiveButton("Share") { _, _ ->
                shareUrl(url)
            }
            .setNeutralButton("Copy URL") { _, _ ->
                copyUrlToClipboard(url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyUrlToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Log URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.log_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Tiny Dictation Debug Logs")
            putExtra(Intent.EXTRA_TEXT, "Debug logs: $url")
        }
        startActivity(Intent.createChooser(shareIntent, "Share log URL"))
    }
}
