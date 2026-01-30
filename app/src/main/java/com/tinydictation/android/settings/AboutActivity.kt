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
            v1.18.43:
            • NEW: Cloud-first prediction with offline fallback
            • Groq AI tried first for high-quality predictions
            • NGram (offline) kicks in if Groq fails/times out
            • Best of both worlds: AI quality + instant fallback

            v1.18.42:
            • Fixed "need" not predicting from "nee"
            • Added 100+ common words to prediction dictionary
            • "nee" → need, "nev" → never, "eve" → every, etc.
            • Better prefix completion for everyday words

            v1.18.41:
            • Multilingual predictions! Auto-detects language
            • Type German → get German predictions
            • Type English → get English predictions
            • Works for any language Groq supports

            v1.18.40:
            • NEW: AI sentence completion with Groq!
            • Type 3+ words → get phrase suggestions
            • "I want to" → "go to the store", "help you", etc.
            • Requires Groq API key in Settings > Word Predictions

            v1.18.39:
            • Predictions now work in terminal apps (Termius, etc.)
            • Predictions update on ANY text change, not just typing
            • Works with dictation, paste, and all input methods

            v1.18.38:
            • More debug logging for prediction troubleshooting
            • Added more common words (prediction, sentence, etc.)
            • Check debug logs to see what's happening

            v1.18.37:
            • NEW: Typo correction for predictions!
            • "tseting" → suggests "testing"
            • Uses edit distance when prefix matching fails

            v1.18.36:
            • Debug logging for prediction engine initialization
            • Better fallback handling when Groq fails
            • Helps diagnose why predictions might not appear

            v1.18.35:
            • Prediction suggestions styled as keyboard keys
            • Rounded corners, same background as letter keys
            • Removed separator lines between suggestions

            v1.18.34:
            • NEW: Separate "Word Predictions" settings section
            • Choose prediction engine: N-gram (offline) or Groq (cloud AI)
            • Enter Groq API key for predictions independently of transcription provider

            v1.18.33:
            • Fix: Apply number row setting when panel opens

            v1.18.32:
            • Debug logging for number row toggle issue

            v1.18.31:
            • Fixed number row toggle (now refreshes on keyboard open)
            • Number hints only shown when number row is OFF

            v1.18.30:
            • Long-press history button → opens settings
            • Long-press Q-P keys → types numbers 1-0
            • Number hints shown on top row keys

            v1.18.29:
            • Special keys now use accent color #1A1A1A
            • Fixed Kotlin code overriding XML styles

            v1.18.28:
            • Panel height back to 320dp
            • Row spacing reduced to 7dp

            v1.18.27:
            • Dictation bar buttons: #1A1A1A (darker)
            • Special keys (shift, backspace, enter, !#1): #1A1A1A

            v1.18.26:
            • Fix dictation bar icons (white instead of black)
            • Number row height 42dp → 43dp
            • Panel height 320dp → 324dp (+1dp per character row)

            v1.18.25:
            • Uniform row spacing (8dp between all rows)
            • Space bar row same height as character rows

            v1.18.24:
            • Fix keyboard panel height (280dp → 320dp)

            v1.18.23:
            • Samsung-style QWERTY layout adjustments
            • Reduced keyboard panel height (330dp → 280dp)
            • Increased row spacing (10dp between rows)
            • Number row height reduced (48dp → 42dp)

            v1.18.22:
            • Dictation bar background: AMOLED black (#000000)
            • Dictation bar buttons unchanged (keep XML styling)
            • White icons on dictation bar for visibility

            v1.18.21:
            • Darker dictation bar background (#1A1A1A)
            • All dictation bar icons set to white (except mic)
            • Fixed programmatic color override issue

            v1.18.20:
            • Dark dictation bar background (#2D2D2D)
            • White icons for better visibility on dark background
            • QWERTY keyboard styling unchanged

            v1.18.19:
            • Exact Samsung color (#2D2D2F) for keyboard keys
            • Dictation bar keeps original colors (not themed)
            • Corner radius increased to 6dp
            • Number row height 48dp, space bar row shorter
            • Vector icons for backspace and enter keys
            • Bigger shift symbol (22sp)

            v1.18.15:
            • Set corner radius to 4dp (Samsung-style subtle rounding)

            v1.18.14:
            • FIX: Keyboard keys now respect corner radius settings
            • Root cause: Kotlin code was overriding XML drawable corners

            v1.18.11:
            • FIX: Updated Kotlin code to use TextView instead of Button
            • Fixes crash on keyboard load

            v1.18.10:
            • Changed all keyboard keys from Button to TextView
            • Removes Material3 styling that forced rounded corners

            v1.18.9:
            • Override Material3 button shape to square corners
            • Added shapeAppearance with cornerSize=0dp to all button styles

            v1.18.8:
            • Completely square button corners (0dp radius)

            v1.18.7:
            • Clean rebuild to ensure corner radius changes apply

            v1.18.6:
            • Removed swipe gesture between tabs (use tab buttons instead)
            • Sharper button corners (6dp → 3dp radius)

            v1.18.5:
            • Added version number at bottom of main Settings screen

            v1.18.4:
            • Reduced button corner radius (12dp → 6dp) for Samsung-like appearance
            • Affects all keys, dictation bar buttons, and mic glow

            v1.18.3:
            • FIX: Reduced swipe sensitivity to prevent accidental tab switches
            • Swipe must be faster, longer, and more horizontal to trigger

            v1.18.2:
            • Taller keyboard keys matching Samsung keyboard sizing
            • Increased panel height from 250dp to 290dp

            v1.18.1:
            • FIX: Snippets now appear after saving from clipboard

            v1.18.0:
            • NEW: Snippet panel - save clipboard items as reusable snippets
            • Long-press clipboard items to save as snippet
            • Snippets sorted alphabetically for easy finding
            • Tap snippet to insert, long-press to delete

            v1.17.11:
            • FIX: Claude panel /exit command now has correct forward slash

            v1.17.10:
            • FIX: Mic button no longer freezes after losing network connection
            • Auto-returns to standby mode on connection error (will auto-reconnect)
            • Fixes issue where mic appeared frozen after going offline (e.g., on train)

            v1.17.9:
            • NEW: Long-press mic to lock mute state (grey icon)
            • Long-press again to unlock and resume normal operation
            • Prevents auto-start when mute is locked

            v1.17.8:
            • FIX: Clipboard panel real-time updates now work consistently
            • Panel state properly reset when keyboard view is recreated
            • Listener re-registered on each view inflation

            v1.17.7:
            • NEW: Quick Actions section at top of Claude panel
            • exit, Esc, y/n buttons immediately accessible
            • Added /send, /cost, /compact, /clear to quick section

            v1.17.6:
            • NEW: Clipboard panel updates in real-time
            • New items appear instantly when copied to clipboard
            • No need to close/reopen the panel to see new entries

            v1.17.5:
            • FIX: QWERTY keyboard layout no longer has extra blank space
            • Fixed row height calculation to fill available space
            • Number row toggle now works correctly

            v1.17.4:
            • NEW: Optional number row on QWERTY keyboard
            • Toggle in Settings > Appearance > Number Row
            • Shows 1-0 above letter keys for quick number input

            v1.17.3:
            • NEW: Long-press 'a' key to insert @ (like iOS)
            • Quick access for @mentions in ticketing/chat apps

            v1.17.2:
            • FIX: Claude tab buttons now tappable and scrollable
            • Added Claude shell launch commands (--dangerously-skip-permissions, --continue)
            • Added /model opus, /model sonnet, /model haiku shortcuts
            • Added more commands: /terminal, /vim, /doctor, /pr, /bug, /init, /login
            • FIX: Normalized background colors across all keyboard tabs
            • Clipboard and History items now match dark keyboard theme

            v1.17.1:
            • FIX: Dictation bar button order corrected
            • New order: Language | Backspace | MIC (left to right from center)
            • QWERTY Enter key symbol made larger (28sp)

            v1.17.0:
            • NEW: Claude commands tab on keyboard
            • Quick access to Claude Code commands (/continue, /skip-permissions, etc.)
            • NEW: Long-press period key for ! and ? punctuation popup
            • NEW: Default predictions shown when nothing is typed
            • Personalized predictions based on your word usage
            • Tab wraparound - swipe from ABC to reach History and vice versa
            • Consistent button styling across all keyboard panels

            v1.16.2:
            • Reordered dictation bar buttons
            • New order: ... | Language | MIC | Enter | Keyboard | History
            • Keyboard button moved next to History for easier access

            v1.16.1:
            • FIX: QWERTY keyboard buttons now match dictation bar colors
            • FIX: Best prediction now centered, secondary on sides
            • Single prediction shows in center only

            v1.16.0:
            • NEW: Groq AI prediction engine (ultra-fast cloud predictions)
            • Set Groq API key in Settings to enable AI predictions
            • Auto-detects API key and switches engine automatically
            • Increased keyboard height for prediction strip
            • Added more common words to offline predictions

            v1.15.2:
            • Added copy button to keyboard error screen
            • Makes it easier to share error details for debugging

            v1.15.1:
            • FIX: Keyboard keys now have visible borders
            • Fixed MaterialButton inflation in IME context
            • Applied stroke styling to all keyboard panels

            v1.15.0:
            • NEW: Word prediction for QWERTY keyboard
            • Predictions appear above keyboard while typing
            • Tap a suggestion to insert it
            • Swappable engine architecture for future upgrades

            v1.14.10:
            • FIX: Keyboard keys now have visible borders
            • Added 1dp stroke around each key (#4D4D4D)
            • Keys are now clearly distinguishable on black background

            v1.14.9:
            • NEW: Clipboard pin feature
            • Tap the pin icon to pin/unpin clipboard items
            • Pinned items appear at the top and never auto-delete
            • Long-press for pin/delete options menu

            v1.14.8:
            • NEW: Keyboard keys now have visible boundaries
            • Dark gray key backgrounds (#2D2D2D) on black background
            • Improved visual clarity for touch targets

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
