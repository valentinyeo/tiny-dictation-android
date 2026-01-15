package com.tinydictation.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tinydictation.android.service.DictationAccessibilityService
import com.tinydictation.android.service.DictationKeyboardService
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.transcription.models.ModelManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TinyDictation"

        // Changelog - update this with each version
        private val CHANGELOG = """
            v1.11.70:
            • FIX: Correct button order - Space to the left of Backspace
            • Button order: Chevron | Emoji | Keyboard | Clipboard | Space | Backspace | MIC | Enter | Language | SelectAll

            v1.11.69:
            • Reordered buttons: MIC | Backspace | Space sequence for thumb access
            • Button order: Chevron | Emoji | Keyboard | Clipboard | Enter | MIC | Backspace | Space | Language | SelectAll

            v1.11.68:
            • NEW: Auto-start dictation when keyboard opens (mic red by default)
            • Swapped Enter/Backspace positions for easier access
            • Button order: Chevron | Emoji | Keyboard | Clipboard | Backspace | Space | MIC | Enter | Language | SelectAll

            v1.11.67:
            • NEW: Space button added for quick space insertion during dictation
            • Swapped Emoji/Keyboard order (Emoji first)
            • Button order: Chevron | Emoji | Keyboard | Clipboard | Enter | Space | MIC | Backspace | Language | SelectAll

            v1.11.66:
            • NEW: Restored button order with MIC centered for thumb access
            • NEW: Clipboard/History button restored - opens transcription history

            v1.11.65:
            • FIX: Replace Material TabLayout with simple tab bar (TabLayout fails in IME context)

            v1.11.64:
            • FIX: Remove duplicate visibility/ID attributes from layout

            v1.11.63:
            • FIX: Keyboard panel not showing (include tag ID issue)

            v1.11.62:
            • NEW: Tabbed keyboard - swipe or tap tabs to switch ABC/123↑
            • Tab 1: QWERTY keyboard (letters, numbers, symbols)
            • Tab 2: Cursor keys + numpad (arrows, navigation, calculator)
            • Removed separate numpad button from bar

            v1.11.61:
            • NEW: Cursor/Numpad keyboard panel - tap grid icon to toggle
            • Left side: Calculator-style numpad (0-9, dot, equals)
            • Right side: Arrow keys, Home/End, PgUp/PgDn, Tab, Del, Esc

            v1.11.60:
            • FIX: Restored Paste button to dictation bar
            • FIX: Reordered buttons - SelectAll/Paste now on left (visible by default)
            • Button order: SelectAll | Paste | Backspace | Enter | Mic | Language | Emoji | Keyboard | Chevron

            v1.11.59:
            • NEW: Numbers/symbols keyboard - tap 123 to switch
            • Two symbol pages: tap #+= for more symbols (€£¥ etc.)
            • Tap ABC to return to letters

            v1.11.58:
            • FIX: Dictation bar now scrolls left to show SelectAll button

            v1.11.57:
            • UI: Keyboard and Emoji buttons show active state when panels are expanded
            • Active buttons show purple tint with up-arrow indicator
            • Clear visual feedback for which panel is currently open

            v1.11.56:
            • NEW: Word Dictionary - fix common misspellings automatically
            • Add words that get replaced in all transcriptions
            • Case-insensitive matching with case preservation
            • Quick restart button in Settings

            v1.11.55:
            • UI: Keyboard bar now scrolls to show SelectAll button by default

            v1.11.54:
            • UI: Mic colors - active: imperial red, standby: dark imperial red, off: white

            v1.11.42:
            • UI: Button order: SelectAll | Backspace | Enter | Mic | Language | Emoji | Keyboard | Chevron
            • NEW: GIF support via Giphy API (requires API key in Settings)
            • UI: Tabbed Emoji/GIF panel - tap tabs to switch
            • GIF search with trending GIFs on open

            v1.11.41:
            • UI: Scrollable dictation bar
            • FIX: Removed Giphy SDK - faster app startup

            v1.11.40:
            • UI: Emoji category headers improved (headline case, white color, regular spacing)
            • Fixed: Emoji picker references updated to match new panel layout

            v1.11.39:
            • UI: Smaller emojis in picker (12 columns instead of default 9)
            • UI: Taller emoji panel (280dp) to show more rows

            v1.11.38:
            • NEW: QWERTY keyboard - tap keyboard button to expand typing panel
            • Shift key (tap for shift, double-tap for caps lock)
            • Keys update to show uppercase when shift/caps active

            v1.11.37:
            • NEW: Emoji picker - tap emoji button to expand, tap emoji to insert
            • Keyboard expands upward to show emoji grid (200dp)
            • Replaced History button with Emoji button

            v1.11.36:
            • FIX: Language now properly refreshed from preferences on each mic start

            v1.11.35:
            • FIX: Language switch now auto-restarts mic when recording

            v1.11.34:
            • UI: Brightest pure red (#FF0000) for recording mic icon
            • UI: Removed glow effect

            v1.11.32:
            • UI: Recording mic icon color (#FF4040)

            v1.11.30:
            • UI: Version number - same size, wider letter spacing, more visible

            v1.11.28:
            • FIX: Language selection now persists after device sleep/wake
            • Previously, language would reset to English after device sleep

            v1.11.27:
            • NEW: Auto-stop after silence - recording stops automatically when you stop speaking
            • Configurable silence duration: 2s, 3s, or 5s (default: 3s)
            • Toggle on/off in Settings > Voice Settings
            • Behaves like traditional dictation apps

            v1.11.26:
            • FIX: Imperial Red (#ED2939) mic icon when recording

            v1.11.25:
            • FIX: First word of dictation no longer missed
            • Audio is now buffered while WebSocket connects to Deepgram
            • Previously audio recorded during connection was silently dropped

            v1.11.24:
            • FIX: Language toggle now works with Deepgram
            • Switching EN/DE actually changes transcription language
            • Previously only saved preference but engine kept using English

            v1.11.23:
            • NEW: AMOLED Black theme option for keyboard
            • Pure black background (#000000) saves battery on OLED screens
            • Select in Settings > Keyboard Theme

            v1.11.22:
            • FIX: Even brighter red mic icon (#FF1744) for dark theme

            v1.11.21:
            • FIX: Brighter red mic icon when recording (more visible in dark theme)

            v1.11.20:
            • FIX: Battery drain - transcription now stops when keyboard is hidden
            • Audio recording stops automatically when you leave the keyboard

            v1.11.19:
            • ADD: History button on keyboard for quick access to transcription history

            v1.11.18:
            • FIX: Build workflow quote escaping issue

            v1.11.17:
            • SIMPLIFY: Removed Type instead of paste toggle
            • Keyboard always types directly - setting no longer needed

            v1.11.16:
            • SIMPLIFY: Removed paste button from keyboard
            • Text is typed directly - no clipboard needed
            • History still accessible from Settings

            v1.11.15:
            • SIMPLIFY: Removed floating bubble and companion bar modes
            • App now focuses exclusively on keyboard-based dictation
            • Cleaner settings UI with unnecessary options removed
            • Archived floating bar code preserved for future reference

            v1.11.14:
            • FIX: Transcription history now saves properly!
            • Transcriptions are saved after each final segment
            • Fixed back button in history view - no longer goes to Settings
            • History opens as overlay and closes back to previous app

            v1.11.13:
            • FIX: Bluetooth headset microphone now works!
            • Added Bluetooth SCO support for wireless headsets
            • OnePlus Buds, AirPods, and other Bluetooth earbuds can now record audio

            v1.11.12:
            • NEW: Filter Whisper hallucinations - removes (clicking), [door closing], etc.
            • Filters out sound descriptions, repeated words, and spam phrases
            • No more transcription garbage when there's silence or background noise

            v1.11.11:
            • NEW: Fallback model dropdown - choose which offline model to use
            • Whisper offline now correctly identified as auto-detecting language
            • Language toggle hidden for Whisper (it auto-detects)
            • Fallback options only shown when using cloud provider

            v1.11.10:
            • FIX: Headphone mic now actually works!
            • Uses setPreferredDevice() to explicitly route audio to headset mic
            • Supports wired, USB, and Bluetooth headsets
            • Added mic source indicator in Voice Settings
            • Shows which mic will be used and warns if headset not connected

            v1.11.8:
            • FIX: Reverted keyboard to pre-emoji-picker state
            • Removed emoji picker feature temporarily to restore keyboard functionality
            • Emoji picker will be re-added properly in a future update

            v1.11.7:
            • FIX: Keyboard bar visibility - ensure bar shows on startup
            • Added explicit visibility control and error protection for emoji picker
            • Added debug logging for keyboard initialization

            v1.11.6:
            • NEW: Unified provider dropdown - cloud and offline providers in one place
            • Model selection adapts to provider (shows download status for offline)
            • Fallback toggle only appears when using cloud providers
            • Cleaner, more intuitive settings UI

            v1.11.5:
            • FIX: Keyboard bar no longer appears blank after emoji picker fix
            • Fixed theme not applying to keyboard bar due to incorrect view cast

            v1.11.4:
            • FIX: Emoji picker no longer crashes the keyboard
            • Fixed view inflation issue by integrating picker into keyboard layout

            v1.11.3:
            • NEW: Emoji picker button added to keyboard bar
            • Tap the smiley icon to open emoji picker
            • 8 emoji categories: Smileys, Gestures, Hearts, Animals, Food, Activities, Objects, Symbols
            • Tap any emoji to insert it, tap chevron to close picker

            v1.11.2:
            • FIX: Added echo cancellation to prevent transcribing speaker audio
            • Mic no longer picks up WhatsApp voice messages, YouTube, etc.
            • Also enabled noise suppression for cleaner voice input

            v1.11.1:
            • NEW: Added AssemblyAI and Gemini (Google) as cloud transcription providers
            • Both new providers auto-detect language by default

            v1.11.0:
            • NEW: Multiple cloud providers - choose Deepgram, Groq, or OpenAI
            • Model selection for each provider (nova-3, whisper-large-v3, gpt-4o-transcribe, etc.)
            • Auto language detection toggle for supported providers/models
            • Improved paste UX: tap history item to paste, long-press for multi-select
            • Reduced clipboard toasts - only copies when pasting from history
            • Language button hides when auto-detect is enabled

            v1.10.28:
            • Fixed keyboard overlaying regular apps (WhatsApp, chat apps)
            • Reverted problematic PWA inset fix that broke normal apps
            • Fixed chevron button not switching to other keyboard
            • Added fallback to switchToNextInputMethod if last method fails

            v1.10.27:
            • Fixed clipboard not updating after transcription
            • Each transcription now copies the full session text to clipboard
            • Long-press copy in History no longer blocks future clipboard updates
            • Added debug logging for clipboard operations

            v1.10.26:
            • Fixed backspace key in terminal/SSH apps (Connectbot, Termux)
            • Uses sendDownUpKeyEvents() for more reliable key dispatch
            • Works better with apps that need actual key events vs text manipulation
            • Added debug logging to track backspace hold behavior

            v1.10.25:
            • Redesigned transcription history for thumb-zone accessibility
            • Grid/tiles layout with smaller font - see more transcriptions at once
            • Reversed order: oldest at top, newest at bottom (right under your thumb)
            • Single tap any tile to copy and close - quick workflow
            • Back button moved to floating FAB in bottom-right corner
            • Long-press for multi-select, copy FAB appears above back button
            • Removed toolbar for cleaner, more spacious layout

            v1.10.24:
            • Added tiny version number display on keyboard bar
            • Shows current version in bottom-right corner for dev/testing
            • Super subtle: 7sp font, 50% opacity, doesn't affect button placement

            v1.10.23:
            • Removed redundant Start/Stop Dictation button from Settings
            • Users now control dictation from the floating bar or keyboard
            • Floating bar has drag-to-close, companion bar will have close button
            • Cleaner Settings screen focused on configuration

            v1.10.22:
            • Fixed keyboard overlaying PWA content instead of pushing it up
            • Properly reports keyboard insets so apps can resize content
            • PWAs now correctly adjust their layout when keyboard opens
            • Added debug logging for keyboard insets troubleshooting

            v1.10.21:
            • NEW: Bar transparency slider in Floating Bar Settings
            • Adjust floating bar transparency from 0% (solid) to 50% (semi-transparent)
            • Renamed "bubble" references to "floating bar" throughout the UI
            • Cleaner, more consistent terminology

            v1.10.20:
            • Fixed floating bar appearing when Horizontal Bar or Keyboard mode selected
            • Floating bar UI only shows when "Vertical Floating Bar" mode is active
            • BubbleService still runs for transcription backend in other modes (without UI)

            v1.10.19:
            • Fixed keyboard theme not applying immediately when changed in Settings
            • Theme changes now take effect instantly without switching keyboards
            • No longer need to switch away and back for theme to apply

            v1.10.18:
            • Seamless switching between Vertical and Horizontal floating bar modes
            • Vertical bar: tap horizontal bar icon to switch to horizontal mode
            • Horizontal bar: tap vertical bar icon to switch to vertical mode
            • Instant mode switching without restarting dictation
            • New icons for horizontal/vertical bar switch buttons

            v1.10.17:
            • NEW: Pulsing mic icon animation while processing transcription
            • After stopping dictation, mic icon pulses red while awaiting final transcript
            • Visual feedback shows when audio is being transcribed (not just recording)
            • Pulse animation appears in floating bar, keyboard, and companion bar

            v1.10.16:
            • Fixed keyboard switch button not working on floating bar and companion bar
            • Added toast feedback when button is pressed ("Switching keyboard...")
            • Added fallback to open keyboard settings if picker fails
            • Better debug logging for keyboard switch troubleshooting

            v1.10.15:
            • Fixed companion bar NOT hiding when Tiny Dictation keyboard opens
            • Now stops KeyboardCompanionService entirely (not just hiding the bar)
            • Previous fix in v1.10.11 only hid the bar temporarily
            • Service listener would show it again - now service is fully stopped
            • Also updates dictation mode preference to keyboard mode automatically

            v1.10.14:
            • Fixed backspace button stopping after 1-2 seconds
            • Backspace now keeps deleting as long as button is held
            • Accelerates from slow to fast over 1.5 seconds
            • After 2 seconds switches to faster word-by-word deletion
            • Uses robust flag-based control to prevent premature stopping

            v1.10.13:
            • Long-press paste button now opens transcription history
            • Quick access to past dictations from keyboard bar

            v1.10.12:
            • Added proper Select All icon to keyboard bar
            • Icon shows nested rectangles with dashed selection border
            • Matches the visual style of other keyboard icons

            v1.10.11:
            • Fixed mutual exclusivity between dictation modes
            • Companion bar now hides when Tiny Dictation keyboard opens
            • Added floating bar button to companion bar for quick mode switching
            • Tap the new sidebar icon to switch from companion mode to floating bar

            v1.10.10:
            • Fixed Keyboard Companion bar overlaying keyboard suggestions
            • Raised bar position to sit above SwiftKey/Gboard suggestion row
            • Right-aligned buttons for easier right-thumb access
            • Added debug logging to keyboard switch button

            v1.10.9:
            • Reordered keyboard buttons for better ergonomics
            • New order: Chevron | Language, Mic, Enter, Backspace, Paste, All

            v1.10.8:
            • Enhanced backspace button hold behavior
            • Hold to delete: starts slow, accelerates over 1.5 seconds
            • After 2 seconds of holding, switches to word-by-word deletion
            • Faster and more intuitive text deletion

            v1.10.7:
            • Fixed Keyboard Companion bar not appearing when keyboard opens
            • Now uses AccessibilityService to detect keyboard visibility
            • More reliable detection works with Gboard, SwiftKey, Samsung keyboard, etc.
            • Previous overlay-based detection didn't work (overlays don't get window insets)

            v1.10.6:
            • Fixed button state bleed bug in keyboard bar
            • Pressing Enter no longer causes Chevron button to show activated state
            • Each button now has fully independent visual state

            v1.10.5:
            • Re-enabled Keyboard Companion mode
            • Companion bar sits above your keyboard (between text and keyboard)
            • Appears automatically when any keyboard opens
            • Contains Mic, Language, and Keyboard switch buttons

            v1.10.4:
            • Fixed keyboard icon colors staying constant on press
            • Icons no longer change color when buttons are pressed/active

            v1.10.3:
            • NEW: Bar orientation setting - choose Vertical or Horizontal
            • Horizontal bar is perfect for use alongside keyboards
            • Replaced unreliable Keyboard Companion mode with simpler horizontal bar
            • Users who had Companion mode will be auto-migrated to horizontal bar

            v1.10.2:
            • NEW: Pause media during dictation - automatically pauses YouTube, music, etc.
            • When enabled, requests audio focus which pauses other audio apps
            • Toggle on/off in Voice Settings
            • Media playback resumes when dictation stops

            v1.10.1:
            • Fixed: Dictation modes are now mutually exclusive
            • Only ONE UI appears based on selected mode (Floating Bar, Companion, or Keyboard)
            • No more floating bars appearing in Companion or Keyboard modes

            v1.10.0:
            • NEW: Keyboard Companion Mode - horizontal bar above your keyboard
            • Works with Gboard, SwiftKey, or any keyboard
            • Automatically appears when keyboard is visible
            • Quick access to Mic, Language, and Expand buttons
            • Choose mode in Settings: Floating Bar, Companion, or Full Keyboard

            v1.9.26:
            • Fixed chevron icon color to match other keyboard icons consistently

            v1.9.25:
            • Redesigned floating bar as vertical bar matching keyboard styling
            • Unified look with keyboard bar (same colors, buttons, icons)
            • Simplified to 3 core buttons: Mic, Language, Keyboard switch
            • Removed paste/note buttons for cleaner interface

            v1.9.24:
            • Backspace now supports hold-to-delete like SwiftKey
            • Hold down backspace to continuously delete characters

            v1.9.23:
            • Moved switch-back button to left side with small chevron icon
            • New button order: Chevron, Mic, Lang | Backspace, Enter, Paste, All

            v1.9.22:
            • Added keyboard theme setting: System (Auto), Light, or Dark
            • Keyboard appearance now matches your preference
            • Light theme: white background, gray buttons, black icons
            • Mic button still turns red when recording regardless of theme

            v1.9.21:
            • Fixed keyboard insets so apps resize content properly
            • PWA and web apps should no longer be hidden behind keyboard

            v1.9.20:
            • All keyboard icons now white, only mic turns red when recording

            v1.9.19:
            • Reordered keyboard buttons: Backspace, Enter, Paste, All, Switch

            v1.9.18:
            • Keyboard button now switches directly to Tiny Dictation keyboard
            • Grant WRITE_SECURE_SETTINGS via ADB for direct switching
            • Falls back to picker if permission not granted

            v1.9.17:
            • Added switch back button to return to previous keyboard instantly
            • No more keyboard picker - one tap to switch back to Gboard/other

            v1.9.16:
            • Added language switch button to keyboard bar
            • Added backspace button to keyboard bar
            • Long-press mic button opens settings
            • Floating bar auto-hides when keyboard is visible
            • Fixed dark mode icons (now visible on dark backgrounds)
            • Removed redundant keyboard switch button

            v1.9.15:
            • Added keyboard action buttons: Enter, Select All, Paste, Switch Keyboard
            • Larger keyboard bar with 56dp buttons for easier tapping
            • Mic button on keyboard toggles dictation like the floating bar
            • Recording state syncs between floating bar and keyboard

            v1.9.14:
            • Added error handling to keyboard service to prevent crashes
            • Better logging for keyboard initialization issues

            v1.9.13:
            • Added keyboard input method for direct text entry
            • Fixed all mipmap icons to use new adaptive icon design
            • Keyboard works in apps where accessibility paste fails

            v1.9.12:
            • Fixed first part of sentence sometimes going missing
            • Audio buffer no longer cleared during session startup race condition
            • Added explicit audio buffer clearing when starting new dictation session

            v1.9.11:
            • Redesigned app icons with modern Lucide-inspired stroke style
            • New professional launcher icon with microphone and audio waves
            • Unified in-app icons: mic, check, warning, close, delete, history, copy, note
            • Added distinct icons for overlay and accessibility permissions

            v1.9.10:
            • Fixed CI build issue

            v1.9.9:
            • Fixed Share Debug Log - now uses dpaste.com with paste.rs fallback
            • More reliable log sharing across different networks

            v1.9.8:
            • Save button now closes settings and returns to previous app
            • New Type-instead-of-paste mode: inserts text at cursor position
            • Useful for editing in the middle of existing text

            v1.9.7:
            • Consolidated app into single screen
            • Permissions, Start/Stop, and Settings all in one place
            • Removed separate main screen for simpler navigation

            v1.9.6:
            • Fixed language switching crash with Whisper engines

            v1.9.5:
            • Fixed Start/Stop button showing inverted state

            v1.9.4:
            • Improved silence detection sensitivity
            • Better error logging for model issues

            v1.9.3:
            • Fixed duplicate text bug in Whisper transcription

            v1.9.2:
            • Fixed Whisper Base and Small models not working
            • All Whisper model sizes now supported

            v1.9.1:
            • Fixed Whisper hallucinations (filters Music, Bell, etc.)
            • Better silence detection - processes on speech pauses
            • Increased buffer for more accurate transcription

            v1.9.0:
            • Whisper transcription now fully working with Sherpa-ONNX
            • High-quality offline speech recognition with Whisper models
            • Supports Whisper Tiny, Base, and Small models

            v1.8.1:
            • Reverted clipboard recording mode - back to simpler copy/paste
            • Long-press clipboard opens history again

            v1.7.8:
            • Fixed note app selection: Now shows all compatible apps
            • Added manual package name entry for note apps

            v1.7.6:
            • Improved drag-to-X close: larger target, vibration feedback, easier to hit
            • Fixed fragmented history: now saves full dictation session on clipboard tap
            • Auto-cleanup: history entries older than 2 days are removed

            v1.7.5:
            • Note Bubble: Send transcriptions directly to note apps (Keep, OneNote, etc.)
            • Multi-select in History: Long-press to select, copy multiple transcriptions
            • Headphone mic toggle: Use headphone mic instead of phone mic
            • Lock screen fix: Dictation no longer stops after unlocking
            • Long-press bubble now opens Settings

            v1.7.4:
            • Fixed drag-to-close functionality
            • Now uses actual close target position for hit detection
            • Increased hit radius for easier targeting
            • Added debug logging for drag-to-close

            v1.7.3:
            • Multiple Whisper models: Tiny (fast), Base (balanced), Small (accurate)
            • Fixed Whisper model downloads (tar.bz2 support)
            • Choose speed vs accuracy for offline transcription

            v1.7.2:
            • Reduced APK size from ~70MB to ~20MB per architecture
            • Separate APKs for ARM64 and ARM32 devices
            • Offline models still downloaded on-demand via Settings

            v1.7.1:
            • Engine selection in Settings! Choose Deepgram, Vosk, or Whisper
            • Model download manager - download offline models directly
            • Vosk English & German models available
            • Auto-fallback toggle for seamless offline mode

            v1.7.0:
            • Offline mode foundation - Vosk integration
            • Multi-engine architecture (Deepgram, Vosk, Sherpa-ONNX)
            • Auto-fallback to offline when no internet
            • Settings UI for engine selection coming soon

            v1.6.4:
            • Fixed drag-to-close - larger hit area, better positioning

            v1.6.3:
            • Auto-start dictation when app launches
            • All transcriptions saved to history (not just copied)

            v1.6.2:
            • Green checkmark when clipboard is ready
            • Vibration feedback on copy
            • Clearer ready-to-paste indicator

            v1.6.1:
            • Bubble toggles apply instantly (no Save needed)
            • Cleaner settings flow

            v1.6.0:
            • Transcription History! View all past transcriptions
            • Long-press clipboard button to open history
            • History button added to main screen
            • Tap any history item to copy it

            v1.5.12:
            • Drag to close! Drag bubble to X target at bottom to close
            • Visual feedback when hovering over close target

            v1.5.11:
            • Sticky Save button (always visible at bottom)
            • Collapsible changelog (tap to expand)

            v1.5.10:
            • Fixed bubble toggle settings (now uses preference listener)
            • Bubbles hide/show automatically when settings change

            v1.5.9:
            • Clear clipboard buffer when starting new dictation session
            • Each tap-to-start gives you a fresh buffer

            v1.5.8:
            • Long-press bubble now opens main app (was compact mode)

            v1.5.7:
            • Reordered bubbles: Language -> Clipboard -> Mic (bottom)

            v1.5.6:
            • Fixed bubble toggle settings (was async race condition)
            • Debug logging for bubble visibility

            v1.5.5:
            • Fixed bubble toggle settings not taking effect immediately
            • Language and paste buttons now hide/show when settings change

            v1.5.4:
            • Smart clipboard button - tap to copy, tap again to paste
            • Icon changes from clipboard to paste after copying
            • Works seamlessly with accessibility service

            v1.5.3:
            • Less intrusive bubbles - now 65% opacity by default
            • Outline-style microphone icon (cleaner look)
            • Compact mode now even more translucent (40%)

            v1.5.2:
            • Long-press for compact/translucent mode (less intrusive)
            • Double-tap to close bubble (reminder: already worked!)

            v1.5.1:
            • Paste button for clipboard mode (works with Termux!)
            • Vertical bubble layout, all same size
            • Toggle bubbles on/off in Settings

            v1.5.0:
            • Language toggle! Tap EN/DE button to switch languages
            • Works mid-conversation - just tap to switch

            v1.4.2:
            • Ultra-low latency optimizations
            • Reduced endpointing from 300ms to 150ms
            • Start recording immediately (no 200ms delay)

            v1.4.1:
            • Smaller bubble (40dp instead of 56dp)

            v1.4.0:
            • Continuous streaming mode - much lower latency!
            • Connects to Deepgram immediately, streams all audio
            • No more waiting for speech detection before transcribing

            v1.3.9:
            • Wait for final transcript before closing connection
            • Reduced log spam, increased log buffer to 500 entries

            v1.3.8:
            • Fixed Deepgram audio encoding params (critical fix for transcription)

            v1.3.7:
            • Fixed Share Logs - now uses paste.rs for reliable uploads

            v1.3.6:
            • Added Share Logs button for easy log sharing

            v1.3.5:
            • Cleaner bubble indicator - mic icon turns red when recording
            • Removed text label below bubble for minimal UI

            v1.3.4:
            • Fixed button contrast in dark mode
            • Improved readability for primary buttons

            v1.3.1:
            • Fixed bubble display (no more double bubble)
            • Bubble now appears on right side of screen

            v1.3.0:
            • Version displayed on main screen
            • Changelog in Settings (What's New)

            v1.2.0:
            • Auto-start listening on Start
            • Uses phone mic (not headphones)
            • Improved speech detection
            • Double-tap bubble to close
        """.trimIndent()
    }

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var modelManager: ModelManager

    // Permission cards
    private lateinit var microphoneCard: MaterialCardView
    private lateinit var overlayCard: MaterialCardView
    private lateinit var accessibilityCard: MaterialCardView

    // Status icons
    private lateinit var microphoneIcon: ImageView
    private lateinit var overlayIcon: ImageView
    private lateinit var accessibilityIcon: ImageView

    // Permission buttons
    private lateinit var microphoneButton: MaterialButton
    private lateinit var overlayButton: MaterialButton
    private lateinit var accessibilityButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var dictionaryButton: MaterialButton
    private lateinit var restartAppButton: MaterialButton

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var autoPunctuationSwitch: MaterialSwitch
    private lateinit var headphoneMicSwitch: MaterialSwitch
    private lateinit var tvMicSourceStatus: TextView
    private lateinit var pauseMediaSwitch: MaterialSwitch
    private lateinit var silenceAutoStopSwitch: MaterialSwitch
    private lateinit var tilSilenceDuration: TextInputLayout
    private lateinit var silenceDurationDropdown: AutoCompleteTextView

    // Audio device detection
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            mainHandler.post { updateMicSourceStatus() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            mainHandler.post { updateMicSourceStatus() }
        }
    }
    private lateinit var btnSetupKeyboard: MaterialButton
    private lateinit var keyboardThemeDropdown: AutoCompleteTextView
    private lateinit var copyDebugLogButton: MaterialButton
    private lateinit var shareDebugLogButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var versionInfo: TextView
    private lateinit var changelog: TextView
    private lateinit var changelogHeader: LinearLayout
    private lateinit var changelogExpandIcon: ImageView
    private var isChangelogExpanded = false

    // Unified provider/model selection
    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var tilModel: TextInputLayout
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var tilGroqApiKey: TextInputLayout
    private lateinit var groqApiKeyInput: TextInputEditText
    private lateinit var tilOpenAIApiKey: TextInputLayout
    private lateinit var openAIApiKeyInput: TextInputEditText
    private lateinit var tilAssemblyAIApiKey: TextInputLayout
    private lateinit var assemblyAIApiKeyInput: TextInputEditText
    private lateinit var tilGeminiApiKey: TextInputLayout
    private lateinit var geminiApiKeyInput: TextInputEditText
    private lateinit var tilGiphyApiKey: TextInputLayout
    private lateinit var giphyApiKeyInput: TextInputEditText
    private lateinit var layoutAutoDetect: LinearLayout
    private lateinit var autoDetectSwitch: MaterialSwitch
    private lateinit var layoutAutoFallback: LinearLayout
    private lateinit var autoFallbackSwitch: MaterialSwitch
    private lateinit var layoutFallbackModel: LinearLayout
    private lateinit var spinnerFallbackModel: Spinner
    private lateinit var tvFallbackModelNote: TextView


    // Model downloads - Vosk
    private lateinit var tvVoskEnStatus: TextView
    private lateinit var tvVoskDeStatus: TextView
    private lateinit var btnDownloadVoskEn: MaterialButton
    private lateinit var btnDownloadVoskDe: MaterialButton

    // Model downloads - Whisper
    private lateinit var tvWhisperTinyStatus: TextView
    private lateinit var tvWhisperBaseStatus: TextView
    private lateinit var tvWhisperSmallStatus: TextView
    private lateinit var btnDownloadWhisperTiny: MaterialButton
    private lateinit var btnDownloadWhisperBase: MaterialButton
    private lateinit var btnDownloadWhisperSmall: MaterialButton

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, "Microphone permission result: $isGranted")
        updatePermissionStatus()
        if (!isGranted) {
            Log.e(TAG, "Microphone permission denied")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SettingsActivity onCreate")
        setContentView(R.layout.activity_settings)

        preferenceManager = PreferenceManager(this)
        modelManager = ModelManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        loadSettings()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "SettingsActivity onResume")
        updatePermissionStatus()

        // Register audio device callback to detect headset connect/disconnect
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        updateMicSourceStatus()
    }

    override fun onPause() {
        super.onPause()
        // Unregister audio device callback
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    private fun updatePermissionStatus() {
        // Microphone permission
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission status - Microphone: $hasMicrophone")

        updatePermissionCard(microphoneIcon, microphoneButton, hasMicrophone)

        // Overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        Log.d(TAG, "Permission status - Overlay: $hasOverlay")
        updatePermissionCard(overlayIcon, overlayButton, hasOverlay)

        // Accessibility service
        val hasAccessibility = isAccessibilityServiceEnabled()
        Log.d(TAG, "Permission status - Accessibility: $hasAccessibility")
        updatePermissionCard(accessibilityIcon, accessibilityButton, hasAccessibility)
    }

    private fun updatePermissionCard(icon: ImageView, button: MaterialButton, isEnabled: Boolean) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.ic_check)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_listening))
            button.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_recording))
            button.visibility = View.VISIBLE
        }
    }

    /**
     * Updates the microphone source status display.
     * Shows which microphone will be used based on:
     * 1. The "Use headphone microphone" setting
     * 2. Whether a headset microphone is actually connected
     */
    private fun updateMicSourceStatus() {
        val useHeadphoneMic = preferenceManager.getUseHeadphoneMic()
        val connectedHeadset = getConnectedHeadsetMic()

        val statusText = when {
            useHeadphoneMic && connectedHeadset != null -> {
                "Will use: $connectedHeadset"
            }
            useHeadphoneMic && connectedHeadset == null -> {
                "No headset connected - will use built-in mic"
            }
            !useHeadphoneMic -> {
                "Will use: Built-in microphone"
            }
            else -> {
                "Will use: Built-in microphone"
            }
        }

        tvMicSourceStatus.text = statusText

        // Change color based on whether intended source is available
        val color = if (useHeadphoneMic && connectedHeadset == null) {
            ContextCompat.getColor(this, R.color.bubble_recording) // Warning color
        } else {
            ContextCompat.getColor(this, R.color.bubble_listening) // Success color
        }
        tvMicSourceStatus.setTextColor(color)

        DebugLog.d("Mic source status: $statusText (headset connected: ${connectedHeadset != null})")
    }

    /**
     * Detects connected headset microphones.
     * Returns the name of the connected headset mic, or null if none connected.
     */
    private fun getConnectedHeadsetMic(): String? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        for (device in devices) {
            val deviceType = device.type
            val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }

            when (deviceType) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    return productName ?: "Wired headset mic"
                }
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return productName ?: "USB headset mic"
                }
                AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                    return productName ?: "Bluetooth LE headset mic"
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    return productName ?: "Bluetooth headset mic"
                }
                // TYPE_BLUETOOTH_A2DP is output only, not input
            }
        }

        return null
    }

    private fun allPermissionsGranted(): Boolean {
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        return hasMicrophone && hasOverlay && hasAccessibility
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == DictationAccessibilityService::class.java.name
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)

        // Permission cards
        microphoneCard = findViewById(R.id.cardMicrophone)
        overlayCard = findViewById(R.id.cardOverlay)
        accessibilityCard = findViewById(R.id.cardAccessibility)

        // Permission icons
        microphoneIcon = findViewById(R.id.iconMicrophone)
        overlayIcon = findViewById(R.id.iconOverlay)
        accessibilityIcon = findViewById(R.id.iconAccessibility)

        // Permission buttons
        microphoneButton = findViewById(R.id.btnEnableMicrophone)
        overlayButton = findViewById(R.id.btnEnableOverlay)
        accessibilityButton = findViewById(R.id.btnEnableAccessibility)

        // History button
        historyButton = findViewById(R.id.btnHistory)

        // Dictionary and Restart buttons
        dictionaryButton = findViewById(R.id.btnDictionary)
        restartAppButton = findViewById(R.id.btnRestartApp)

        // Settings views
        apiKeyInput = findViewById(R.id.etApiKey)
        autoPunctuationSwitch = findViewById(R.id.switchAutoPunctuation)
        headphoneMicSwitch = findViewById(R.id.switchHeadphoneMic)
        tvMicSourceStatus = findViewById(R.id.tvMicSourceStatus)
        pauseMediaSwitch = findViewById(R.id.switchPauseMedia)
        silenceAutoStopSwitch = findViewById(R.id.switchSilenceAutoStop)
        tilSilenceDuration = findViewById(R.id.tilSilenceDuration)
        silenceDurationDropdown = findViewById(R.id.actvSilenceDuration)
        btnSetupKeyboard = findViewById(R.id.btnSetupKeyboard)
        keyboardThemeDropdown = findViewById(R.id.actvKeyboardTheme)
        copyDebugLogButton = findViewById(R.id.btnCopyDebugLog)
        shareDebugLogButton = findViewById(R.id.btnShareDebugLog)
        saveButton = findViewById(R.id.btnSave)
        versionInfo = findViewById(R.id.tvVersionInfo)
        changelog = findViewById(R.id.tvChangelog)
        changelogHeader = findViewById(R.id.changelogHeader)
        changelogExpandIcon = findViewById(R.id.ivChangelogExpand)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)

        // Display version info and changelog
        displayVersionInfo()
        changelog.text = CHANGELOG

        // Setup expandable changelog
        changelogHeader.setOnClickListener {
            toggleChangelog()
        }

        // Unified provider/model views
        providerDropdown = findViewById(R.id.actvProvider)
        modelDropdown = findViewById(R.id.actvModel)
        tilModel = findViewById(R.id.tilModel)
        tilApiKey = findViewById(R.id.tilApiKey)
        tilGroqApiKey = findViewById(R.id.tilGroqApiKey)
        groqApiKeyInput = findViewById(R.id.etGroqApiKey)
        tilOpenAIApiKey = findViewById(R.id.tilOpenAIApiKey)
        openAIApiKeyInput = findViewById(R.id.etOpenAIApiKey)
        tilAssemblyAIApiKey = findViewById(R.id.tilAssemblyAIApiKey)
        assemblyAIApiKeyInput = findViewById(R.id.etAssemblyAIApiKey)
        tilGeminiApiKey = findViewById(R.id.tilGeminiApiKey)
        geminiApiKeyInput = findViewById(R.id.etGeminiApiKey)
        tilGiphyApiKey = findViewById(R.id.tilGiphyApiKey)
        giphyApiKeyInput = findViewById(R.id.etGiphyApiKey)
        layoutAutoDetect = findViewById(R.id.layoutAutoDetect)
        autoDetectSwitch = findViewById(R.id.switchAutoDetect)
        layoutAutoFallback = findViewById(R.id.layoutAutoFallback)
        autoFallbackSwitch = findViewById(R.id.switchAutoFallback)
        layoutFallbackModel = findViewById(R.id.layoutFallbackModel)
        spinnerFallbackModel = findViewById(R.id.spinnerFallbackModel)
        tvFallbackModelNote = findViewById(R.id.tvFallbackModelNote)

        // Model download views
        tvVoskEnStatus = findViewById(R.id.tvVoskEnStatus)
        tvVoskDeStatus = findViewById(R.id.tvVoskDeStatus)
        btnDownloadVoskEn = findViewById(R.id.btnDownloadVoskEn)
        btnDownloadVoskDe = findViewById(R.id.btnDownloadVoskDe)

        // Whisper model views
        tvWhisperTinyStatus = findViewById(R.id.tvWhisperTinyStatus)
        tvWhisperBaseStatus = findViewById(R.id.tvWhisperBaseStatus)
        tvWhisperSmallStatus = findViewById(R.id.tvWhisperSmallStatus)
        btnDownloadWhisperTiny = findViewById(R.id.btnDownloadWhisperTiny)
        btnDownloadWhisperBase = findViewById(R.id.btnDownloadWhisperBase)
        btnDownloadWhisperSmall = findViewById(R.id.btnDownloadWhisperSmall)

        // Setup unified provider dropdown (combines cloud and offline providers)
        setupUnifiedProviderDropdown()

        // Setup keyboard theme dropdown
        setupKeyboardThemeDropdown()

        // Setup silence auto-stop dropdown
        setupSilenceAutoStopDropdown()
    }

    private fun toggleChangelog() {
        isChangelogExpanded = !isChangelogExpanded
        if (isChangelogExpanded) {
            changelog.maxLines = Int.MAX_VALUE
            changelogExpandIcon.rotation = 180f
        } else {
            changelog.maxLines = 4
            changelogExpandIcon.rotation = 0f
        }
    }

    /**
     * Sets up the unified provider dropdown that combines cloud and offline providers.
     * Cloud providers: Deepgram, Groq, OpenAI, AssemblyAI, Gemini
     * Offline providers: Vosk, Whisper (Sherpa-ONNX)
     */
    private fun setupUnifiedProviderDropdown() {
        refreshProviderDropdown()

        // Setup auto-detect switch
        autoDetectSwitch.isChecked = preferenceManager.getAutoDetectLanguage()
        autoDetectSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoDetectLanguage(isChecked)
            DebugLog.i("Auto-detect language: $isChecked")
        }

        // Setup auto-fallback switch
        autoFallbackSwitch.isChecked = preferenceManager.getAutoFallback()
        autoFallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoFallback(isChecked)
            updateFallbackModelText()
            DebugLog.i("Auto-fallback: $isChecked")
        }

        // Load saved API keys
        preferenceManager.getGroqApiKey()?.let { groqApiKeyInput.setText(it) }
        preferenceManager.getOpenAIApiKey()?.let { openAIApiKeyInput.setText(it) }
        preferenceManager.getAssemblyAIApiKey()?.let { assemblyAIApiKeyInput.setText(it) }
        preferenceManager.getGeminiApiKey()?.let { geminiApiKeyInput.setText(it) }
    }

    private fun refreshProviderDropdown() {
        // Build provider list with sections
        val providers = mutableListOf<Pair<String, String>>()

        // Cloud providers (always available)
        providers.add("--- Cloud ---" to "header:cloud")
        providers.add("Deepgram" to PreferenceManager.UNIFIED_DEEPGRAM)
        providers.add("Groq (Whisper)" to PreferenceManager.UNIFIED_GROQ)
        providers.add("OpenAI" to PreferenceManager.UNIFIED_OPENAI)
        providers.add("AssemblyAI" to PreferenceManager.UNIFIED_ASSEMBLYAI)
        providers.add("Gemini (Google)" to PreferenceManager.UNIFIED_GEMINI)

        // Offline providers (show based on downloaded models)
        val hasVoskModel = modelManager.isModelAvailable("vosk", "en") || modelManager.isModelAvailable("vosk", "de")
        val hasWhisperModel = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny") ||
                modelManager.isModelAvailable("sherpa-onnx", "whisper-base") ||
                modelManager.isModelAvailable("sherpa-onnx", "whisper-small")

        if (hasVoskModel || hasWhisperModel) {
            providers.add("--- Offline ---" to "header:offline")
        }
        if (hasVoskModel) {
            providers.add("Vosk (Fast Offline)" to PreferenceManager.UNIFIED_VOSK)
        }
        if (hasWhisperModel) {
            providers.add("Whisper (High Quality)" to PreferenceManager.UNIFIED_WHISPER)
        }

        // Filter out headers for the adapter, but keep them for display logic
        val selectableProviders = providers.filter { !it.second.startsWith("header:") }
        val displayNames = providers.map { it.first }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        providerDropdown.setAdapter(adapter)

        // Set current selection
        val currentProvider = preferenceManager.getUnifiedProvider()
        val currentIndex = providers.indexOfFirst { it.second == currentProvider }
        if (currentIndex >= 0) {
            providerDropdown.setText(providers[currentIndex].first, false)
        } else {
            // Default to Deepgram
            providerDropdown.setText("Deepgram", false)
        }

        // Update UI based on current provider
        updateProviderUI(currentProvider)

        // Handle selection changes
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = providers[position]
            // Skip headers
            if (selected.second.startsWith("header:")) {
                // Re-select the current value
                providerDropdown.setText(providers.find { it.second == preferenceManager.getUnifiedProvider() }?.first ?: "Deepgram", false)
                return@setOnItemClickListener
            }

            preferenceManager.setUnifiedProvider(selected.second)
            updateProviderUI(selected.second)
            DebugLog.i("Provider changed to: ${selected.second}")
        }
    }

    /**
     * Updates all UI elements based on the selected provider.
     */
    private fun updateProviderUI(provider: String) {
        val isCloud = provider.startsWith("cloud:")

        // Update model dropdown
        updateModelDropdown(provider)

        // Update API key visibility (only for cloud providers)
        updateApiKeyVisibility(provider)

        // Update auto-detect visibility
        updateAutoDetectVisibility()

        // Update fallback toggle visibility (only for cloud providers)
        layoutAutoFallback.visibility = if (isCloud) View.VISIBLE else View.GONE
        if (isCloud) {
            updateFallbackModelText()
        }
    }

    /**
     * Updates the model dropdown based on the selected provider.
     * For cloud providers, shows their available models.
     * For offline providers, shows downloaded models with download status.
     */
    private fun updateModelDropdown(provider: String) {
        val models = when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> listOf(
                "nova-3 (Best)" to PreferenceManager.DEEPGRAM_NOVA_3,
                "nova-2 (Default)" to PreferenceManager.DEEPGRAM_NOVA_2,
                "enhanced" to PreferenceManager.DEEPGRAM_ENHANCED,
                "base (Cheapest)" to PreferenceManager.DEEPGRAM_BASE
            )
            PreferenceManager.UNIFIED_GROQ -> listOf(
                "whisper-large-v3 (Best)" to PreferenceManager.GROQ_WHISPER_LARGE_V3,
                "whisper-large-v3-turbo (Faster)" to PreferenceManager.GROQ_WHISPER_LARGE_V3_TURBO,
                "distil-whisper (English only)" to PreferenceManager.GROQ_DISTIL_WHISPER
            )
            PreferenceManager.UNIFIED_OPENAI -> listOf(
                "gpt-4o-transcribe (Best)" to PreferenceManager.OPENAI_GPT4O_TRANSCRIBE,
                "gpt-4o-mini-transcribe (Cheaper)" to PreferenceManager.OPENAI_GPT4O_MINI,
                "whisper-1 (Original)" to PreferenceManager.OPENAI_WHISPER_1
            )
            PreferenceManager.UNIFIED_ASSEMBLYAI -> emptyList()
            PreferenceManager.UNIFIED_GEMINI -> emptyList()
            PreferenceManager.UNIFIED_VOSK -> buildVoskModelList()
            PreferenceManager.UNIFIED_WHISPER -> buildWhisperModelList()
            else -> emptyList()
        }

        // Hide model dropdown if no models to show
        if (models.isEmpty()) {
            tilModel.visibility = View.GONE
            return
        }
        tilModel.visibility = View.VISIBLE

        val displayNames = models.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        modelDropdown.setAdapter(adapter)

        // Set current selection
        val currentModel = getCurrentModelForProvider(provider)
        val currentIndex = models.indexOfFirst { it.second == currentModel }
        if (currentIndex >= 0) {
            modelDropdown.setText(displayNames[currentIndex], false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position].second

            // Check if it's a download action (starts with "download:")
            if (selectedModel.startsWith("download:")) {
                val modelId = selectedModel.removePrefix("download:")
                triggerModelDownload(provider, modelId)
                return@setOnItemClickListener
            }

            saveModelForProvider(provider, selectedModel)
            updateAutoDetectVisibility()
            DebugLog.i("Model changed to: $selectedModel")
        }
    }

    /**
     * Builds Vosk model list with download status indicators.
     */
    private fun buildVoskModelList(): List<Pair<String, String>> {
        val models = mutableListOf<Pair<String, String>>()
        val voskEnAvailable = modelManager.isModelAvailable("vosk", "en")
        val voskDeAvailable = modelManager.isModelAvailable("vosk", "de")

        if (voskEnAvailable) {
            models.add("English (41MB) \u2713" to "en")
        } else {
            models.add("English (41MB) \u2193" to "download:en")
        }

        if (voskDeAvailable) {
            models.add("German (45MB) \u2713" to "de")
        } else {
            models.add("German (45MB) \u2193" to "download:de")
        }

        return models
    }

    /**
     * Builds Whisper model list with download status indicators.
     */
    private fun buildWhisperModelList(): List<Pair<String, String>> {
        val models = mutableListOf<Pair<String, String>>()
        val tinyAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")
        val baseAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-base")
        val smallAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-small")

        if (tinyAvailable) {
            models.add("Tiny - Fast (75MB) \u2713" to PreferenceManager.WHISPER_TINY)
        } else {
            models.add("Tiny - Fast (75MB) \u2193" to "download:whisper-tiny")
        }

        if (baseAvailable) {
            models.add("Base - Balanced (150MB) \u2713" to PreferenceManager.WHISPER_BASE)
        } else {
            models.add("Base - Balanced (150MB) \u2193" to "download:whisper-base")
        }

        if (smallAvailable) {
            models.add("Small - Accurate (500MB) \u2713" to PreferenceManager.WHISPER_SMALL)
        } else {
            models.add("Small - Accurate (500MB) \u2193" to "download:whisper-small")
        }

        return models
    }

    /**
     * Gets the current model selection for the given provider.
     */
    private fun getCurrentModelForProvider(provider: String): String {
        return when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> preferenceManager.getDeepgramModel()
            PreferenceManager.UNIFIED_GROQ -> preferenceManager.getGroqModel()
            PreferenceManager.UNIFIED_OPENAI -> preferenceManager.getOpenAIModel()
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.getLanguage()
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.getWhisperModel()
            else -> ""
        }
    }

    /**
     * Saves the model selection for the given provider.
     */
    private fun saveModelForProvider(provider: String, model: String) {
        when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> preferenceManager.setDeepgramModel(model)
            PreferenceManager.UNIFIED_GROQ -> preferenceManager.setGroqModel(model)
            PreferenceManager.UNIFIED_OPENAI -> preferenceManager.setOpenAIModel(model)
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.setLanguage(model)
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.setWhisperModel(model)
        }
    }

    /**
     * Triggers a model download when user taps on a not-yet-downloaded model.
     */
    private fun triggerModelDownload(provider: String, modelId: String) {
        val engineId = when (provider) {
            PreferenceManager.UNIFIED_VOSK -> "vosk"
            PreferenceManager.UNIFIED_WHISPER -> "sherpa-onnx"
            else -> return
        }

        // Find the appropriate button and status view
        val (button, statusView) = when {
            provider == PreferenceManager.UNIFIED_VOSK && modelId == "en" -> btnDownloadVoskEn to tvVoskEnStatus
            provider == PreferenceManager.UNIFIED_VOSK && modelId == "de" -> btnDownloadVoskDe to tvVoskDeStatus
            provider == PreferenceManager.UNIFIED_WHISPER && modelId == "whisper-tiny" -> btnDownloadWhisperTiny to tvWhisperTinyStatus
            provider == PreferenceManager.UNIFIED_WHISPER && modelId == "whisper-base" -> btnDownloadWhisperBase to tvWhisperBaseStatus
            provider == PreferenceManager.UNIFIED_WHISPER && modelId == "whisper-small" -> btnDownloadWhisperSmall to tvWhisperSmallStatus
            else -> return
        }

        // Trigger the download
        handleModelButton(engineId, modelId, button, statusView)

        // Refresh dropdown after download completes (handled in handleModelButton)
    }

    private fun updateApiKeyVisibility(provider: String) {
        tilApiKey.visibility = if (provider == PreferenceManager.UNIFIED_DEEPGRAM) View.VISIBLE else View.GONE
        tilGroqApiKey.visibility = if (provider == PreferenceManager.UNIFIED_GROQ) View.VISIBLE else View.GONE
        tilOpenAIApiKey.visibility = if (provider == PreferenceManager.UNIFIED_OPENAI) View.VISIBLE else View.GONE
        tilAssemblyAIApiKey.visibility = if (provider == PreferenceManager.UNIFIED_ASSEMBLYAI) View.VISIBLE else View.GONE
        tilGeminiApiKey.visibility = if (provider == PreferenceManager.UNIFIED_GEMINI) View.VISIBLE else View.GONE
    }

    private fun updateAutoDetectVisibility() {
        // Show auto-detect option only for providers/models that support it
        val supportsAutoDetect = preferenceManager.currentProviderSupportsAutoDetect()
        layoutAutoDetect.visibility = if (supportsAutoDetect) View.VISIBLE else View.GONE
    }

    /**
     * Data class for fallback model dropdown items.
     */
    private data class FallbackModelItem(
        val id: String,
        val displayName: String,
        val isWhisper: Boolean
    )

    /**
     * Updates the fallback model dropdown with available offline models.
     */
    private fun updateFallbackModelText() {
        if (!autoFallbackSwitch.isChecked) {
            layoutFallbackModel.visibility = View.GONE
            return
        }

        // Build list of available offline models
        val availableModels = mutableListOf<FallbackModelItem>()

        // Check Vosk models
        if (modelManager.isModelAvailable("vosk", "en")) {
            availableModels.add(FallbackModelItem(
                PreferenceManager.FALLBACK_VOSK_EN,
                "Vosk English",
                isWhisper = false
            ))
        }
        if (modelManager.isModelAvailable("vosk", "de")) {
            availableModels.add(FallbackModelItem(
                PreferenceManager.FALLBACK_VOSK_DE,
                "Vosk German",
                isWhisper = false
            ))
        }

        // Check Whisper models
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")) {
            availableModels.add(FallbackModelItem(
                PreferenceManager.FALLBACK_WHISPER_TINY,
                "Whisper Tiny (multilingual)",
                isWhisper = true
            ))
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-base")) {
            availableModels.add(FallbackModelItem(
                PreferenceManager.FALLBACK_WHISPER_BASE,
                "Whisper Base (multilingual)",
                isWhisper = true
            ))
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-small")) {
            availableModels.add(FallbackModelItem(
                PreferenceManager.FALLBACK_WHISPER_SMALL,
                "Whisper Small (multilingual)",
                isWhisper = true
            ))
        }

        if (availableModels.isEmpty()) {
            // No models available - hide dropdown, show message
            layoutFallbackModel.visibility = View.VISIBLE
            spinnerFallbackModel.visibility = View.GONE
            tvFallbackModelNote.text = "No offline models downloaded"
            tvFallbackModelNote.visibility = View.VISIBLE
            return
        }

        layoutFallbackModel.visibility = View.VISIBLE
        spinnerFallbackModel.visibility = View.VISIBLE

        // Set up spinner adapter
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableModels.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFallbackModel.adapter = adapter

        // Select current fallback model or first available
        val currentFallback = preferenceManager.getFallbackModel()
        val currentIndex = availableModels.indexOfFirst { it.id == currentFallback }
        if (currentIndex >= 0) {
            spinnerFallbackModel.setSelection(currentIndex)
        } else {
            // Default to first available and save it
            spinnerFallbackModel.setSelection(0)
            preferenceManager.setFallbackModel(availableModels[0].id)
        }

        // Update note visibility based on current selection
        val selectedModel = if (currentIndex >= 0) availableModels[currentIndex] else availableModels[0]
        tvFallbackModelNote.visibility = if (selectedModel.isWhisper) View.VISIBLE else View.GONE

        // Handle selection changes
        spinnerFallbackModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = availableModels[position]
                preferenceManager.setFallbackModel(selected.id)
                tvFallbackModelNote.visibility = if (selected.isWhisper) View.VISIBLE else View.GONE
                DebugLog.i("Fallback model changed to: ${selected.displayName}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupKeyboardThemeDropdown() {
        // Theme options: display name to preference value
        val themes = listOf(
            getString(R.string.keyboard_theme_system) to PreferenceManager.THEME_SYSTEM,
            getString(R.string.keyboard_theme_light) to PreferenceManager.THEME_LIGHT,
            getString(R.string.keyboard_theme_dark) to PreferenceManager.THEME_DARK,
            getString(R.string.keyboard_theme_amoled) to PreferenceManager.THEME_AMOLED
        )

        val displayNames = themes.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        keyboardThemeDropdown.setAdapter(adapter)

        // Set current selection
        val currentTheme = preferenceManager.getKeyboardTheme()
        val currentIndex = themes.indexOfFirst { it.second == currentTheme }
        if (currentIndex >= 0) {
            keyboardThemeDropdown.setText(displayNames[currentIndex], false)
        } else {
            // Default to System
            keyboardThemeDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes - apply immediately
        keyboardThemeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = themes[position].second
            preferenceManager.setKeyboardTheme(selectedValue)
            DebugLog.i("Keyboard theme changed to: $selectedValue")

            // Notify the running keyboard service to refresh its theme immediately
            DictationKeyboardService.getInstance()?.refreshTheme()
        }
    }

    /**
     * Sets up the silence auto-stop toggle and duration dropdown.
     */
    private fun setupSilenceAutoStopDropdown() {
        // Duration options: display name to milliseconds
        val durations = listOf(
            "2 seconds" to 2000,
            "3 seconds" to 3000,
            "5 seconds" to 5000
        )

        val displayNames = durations.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        silenceDurationDropdown.setAdapter(adapter)

        // Load current setting
        val currentMs = preferenceManager.getSilenceAutoStopMs()
        val isEnabled = currentMs > 0

        // Set switch state
        silenceAutoStopSwitch.isChecked = isEnabled

        // Set duration dropdown state
        tilSilenceDuration.visibility = if (isEnabled) View.VISIBLE else View.GONE
        if (isEnabled) {
            val currentIndex = durations.indexOfFirst { it.second == currentMs }
            if (currentIndex >= 0) {
                silenceDurationDropdown.setText(displayNames[currentIndex], false)
            } else {
                // Default to 3 seconds if stored value doesn't match options
                silenceDurationDropdown.setText(displayNames[1], false)
            }
        }

        // Handle switch changes
        silenceAutoStopSwitch.setOnCheckedChangeListener { _, isChecked ->
            tilSilenceDuration.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                // Enable with default or previously selected duration
                val selectedText = silenceDurationDropdown.text.toString()
                val selectedMs = durations.find { it.first == selectedText }?.second
                    ?: PreferenceManager.DEFAULT_SILENCE_AUTO_STOP_MS
                preferenceManager.setSilenceAutoStopMs(selectedMs)
                DebugLog.i("Silence auto-stop enabled: ${selectedMs}ms")
            } else {
                // Disable (0 = disabled)
                preferenceManager.setSilenceAutoStopMs(0)
                DebugLog.i("Silence auto-stop disabled")
            }
        }

        // Handle duration selection
        silenceDurationDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedMs = durations[position].second
            preferenceManager.setSilenceAutoStopMs(selectedMs)
            DebugLog.i("Silence auto-stop duration changed to: ${selectedMs}ms")
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

    private fun loadSettings() {
        // Load API key
        preferenceManager.getApiKey()?.let { apiKey ->
            apiKeyInput.setText(apiKey)
        }

        // Load auto-punctuation
        autoPunctuationSwitch.isChecked = preferenceManager.getAutoPunctuation()

        // Load headphone mic setting
        headphoneMicSwitch.isChecked = preferenceManager.getUseHeadphoneMic()

        // Load pause media during dictation setting
        pauseMediaSwitch.isChecked = preferenceManager.getPauseMediaDuringDictation()

        // Load Giphy API key
        preferenceManager.getGiphyApiKey()?.let { key ->
            giphyApiKeyInput.setText(key)
        }

        // Update model status
        updateModelStatus()
    }

    private fun updateModelStatus() {
        // Vosk English
        val voskEnAvailable = modelManager.isModelAvailable("vosk", "en")
        if (voskEnAvailable) {
            tvVoskEnStatus.text = "Downloaded"
            btnDownloadVoskEn.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["en"]
            tvVoskEnStatus.text = "Not downloaded (${info?.sizeMB ?: 41} MB)"
            btnDownloadVoskEn.text = "Download"
        }

        // Vosk German
        val voskDeAvailable = modelManager.isModelAvailable("vosk", "de")
        if (voskDeAvailable) {
            tvVoskDeStatus.text = "Downloaded"
            btnDownloadVoskDe.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["de"]
            tvVoskDeStatus.text = "Not downloaded (${info?.sizeMB ?: 45} MB)"
            btnDownloadVoskDe.text = "Download"
        }

        // Whisper Tiny (Fast)
        val whisperTinyAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")
        if (whisperTinyAvailable) {
            tvWhisperTinyStatus.text = "Downloaded"
            btnDownloadWhisperTiny.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-tiny"]
            tvWhisperTinyStatus.text = "Not downloaded (${info?.sizeMB ?: 75} MB)"
            btnDownloadWhisperTiny.text = "Download"
        }

        // Whisper Base (Balanced)
        val whisperBaseAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-base")
        if (whisperBaseAvailable) {
            tvWhisperBaseStatus.text = "Downloaded"
            btnDownloadWhisperBase.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-base"]
            tvWhisperBaseStatus.text = "Not downloaded (${info?.sizeMB ?: 150} MB)"
            btnDownloadWhisperBase.text = "Download"
        }

        // Whisper Small (Accurate)
        val whisperSmallAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-small")
        if (whisperSmallAvailable) {
            tvWhisperSmallStatus.text = "Downloaded"
            btnDownloadWhisperSmall.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-small"]
            tvWhisperSmallStatus.text = "Not downloaded (${info?.sizeMB ?: 500} MB)"
            btnDownloadWhisperSmall.text = "Download"
        }
    }

    private fun setupClickListeners() {
        // Permission buttons
        microphoneButton.setOnClickListener {
            Log.d(TAG, "Microphone button clicked - requesting permission")
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        overlayButton.setOnClickListener {
            Log.d(TAG, "Overlay button clicked - opening settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accessibilityButton.setOnClickListener {
            Log.d(TAG, "Accessibility button clicked - opening settings")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Find 'Tiny Dictation' and enable it",
                Toast.LENGTH_LONG
            ).show()
        }

        // History button
        historyButton.setOnClickListener {
            Log.d(TAG, "History button clicked")
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Dictionary button
        dictionaryButton.setOnClickListener {
            Log.d(TAG, "Dictionary button clicked")
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        // Restart app button
        restartAppButton.setOnClickListener {
            Log.d(TAG, "Restart app button clicked")
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        // Setup keyboard button - opens system keyboard settings
        btnSetupKeyboard.setOnClickListener {
            openKeyboardSettings()
        }

        // Headphone mic toggle applies immediately
        headphoneMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setUseHeadphoneMic(isChecked)
            updateMicSourceStatus()
            DebugLog.i("Headphone mic setting changed to: $isChecked")
        }

        // Pause media toggle applies immediately
        pauseMediaSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPauseMediaDuringDictation(isChecked)
            DebugLog.i("Pause media during dictation setting changed to: $isChecked")
        }

        copyDebugLogButton.setOnClickListener {
            copyDebugLogToClipboard()
        }

        shareDebugLogButton.setOnClickListener {
            shareDebugLog()
        }

        saveButton.setOnClickListener {
            saveSettings()
        }

        // Model download/delete buttons
        btnDownloadVoskEn.setOnClickListener {
            handleModelButton("vosk", "en", btnDownloadVoskEn, tvVoskEnStatus)
        }

        btnDownloadVoskDe.setOnClickListener {
            handleModelButton("vosk", "de", btnDownloadVoskDe, tvVoskDeStatus)
        }

        // Whisper model download buttons
        btnDownloadWhisperTiny.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-tiny", btnDownloadWhisperTiny, tvWhisperTinyStatus)
        }

        btnDownloadWhisperBase.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-base", btnDownloadWhisperBase, tvWhisperBaseStatus)
        }

        btnDownloadWhisperSmall.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-small", btnDownloadWhisperSmall, tvWhisperSmallStatus)
        }
    }

    private fun handleModelButton(engineId: String, language: String, button: MaterialButton, statusView: TextView) {
        val isAvailable = modelManager.isModelAvailable(engineId, language)

        if (isAvailable) {
            // Delete model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Deleting..."

                val result = modelManager.deleteModel(engineId, language)
                if (result.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "Model deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Failed to delete model", Toast.LENGTH_SHORT).show()
                }

                updateModelStatus()
                refreshProviderDropdown()
                button.isEnabled = true
            }
        } else {
            // Download model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Downloading..."
                statusView.text = "Downloading..."

                val result = modelManager.downloadModel(engineId, language)
                if (result.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "Model downloaded!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }

                updateModelStatus()
                refreshProviderDropdown()
                button.isEnabled = true
            }
        }
    }

    private fun saveSettings() {
        val apiKey = apiKeyInput.text?.toString()?.trim()
        val groqApiKey = groqApiKeyInput.text?.toString()?.trim()
        val openAIApiKey = openAIApiKeyInput.text?.toString()?.trim()
        val assemblyAIApiKey = assemblyAIApiKeyInput.text?.toString()?.trim()
        val geminiApiKey = geminiApiKeyInput.text?.toString()?.trim()

        // API key validation based on selected provider
        val unifiedProvider = preferenceManager.getUnifiedProvider()

        // Only validate API key for cloud providers
        if (unifiedProvider.startsWith("cloud:")) {
            when (unifiedProvider) {
                PreferenceManager.UNIFIED_DEEPGRAM -> {
                    if (apiKey.isNullOrEmpty()) {
                        apiKeyInput.error = "API key is required for Deepgram"
                        return
                    }
                }
                PreferenceManager.UNIFIED_GROQ -> {
                    if (groqApiKey.isNullOrEmpty()) {
                        groqApiKeyInput.error = "API key is required for Groq"
                        return
                    }
                }
                PreferenceManager.UNIFIED_OPENAI -> {
                    if (openAIApiKey.isNullOrEmpty()) {
                        openAIApiKeyInput.error = "API key is required for OpenAI"
                        return
                    }
                }
                PreferenceManager.UNIFIED_ASSEMBLYAI -> {
                    if (assemblyAIApiKey.isNullOrEmpty()) {
                        assemblyAIApiKeyInput.error = "API key is required for AssemblyAI"
                        return
                    }
                }
                PreferenceManager.UNIFIED_GEMINI -> {
                    if (geminiApiKey.isNullOrEmpty()) {
                        geminiApiKeyInput.error = "API key is required for Gemini"
                        return
                    }
                }
            }
        }

        // Save Deepgram API key if provided
        if (!apiKey.isNullOrEmpty()) {
            preferenceManager.setApiKey(apiKey)
        }

        // Save Groq API key if provided
        if (!groqApiKey.isNullOrEmpty()) {
            preferenceManager.setGroqApiKey(groqApiKey)
        }

        // Save OpenAI API key if provided
        if (!openAIApiKey.isNullOrEmpty()) {
            preferenceManager.setOpenAIApiKey(openAIApiKey)
        }

        // Save AssemblyAI API key if provided
        if (!assemblyAIApiKey.isNullOrEmpty()) {
            preferenceManager.setAssemblyAIApiKey(assemblyAIApiKey)
        }

        // Save Gemini API key if provided
        if (!geminiApiKey.isNullOrEmpty()) {
            preferenceManager.setGeminiApiKey(geminiApiKey)
        }

        // Save Giphy API key if provided
        val giphyApiKey = giphyApiKeyInput.text?.toString()?.trim()
        if (!giphyApiKey.isNullOrEmpty()) {
            preferenceManager.setGiphyApiKey(giphyApiKey)
        }

        // Save auto-punctuation
        preferenceManager.setAutoPunctuation(autoPunctuationSwitch.isChecked)

        // Note: Bubble settings are saved immediately via switch listeners

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
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

        // Disable button and show uploading state
        shareDebugLogButton.isEnabled = false
        shareDebugLogButton.text = getString(R.string.uploading_logs)

        lifecycleScope.launch {
            try {
                val url = uploadToPaste(logText)
                if (url != null) {
                    showShareDialog(url)
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DebugLog.e("Failed to upload logs: ${e.message}")
                Toast.makeText(this@SettingsActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
            } finally {
                // Restore button state
                shareDebugLogButton.isEnabled = true
                shareDebugLogButton.text = getString(R.string.share_debug_log)
            }
        }
    }

    private suspend fun uploadToPaste(content: String): String? = withContext(Dispatchers.IO) {
        // Try dpaste.com first (more reliable), then paste.rs as fallback
        uploadToDpaste(content) ?: uploadToPasteRs(content)
    }

    private fun uploadToDpaste(content: String): String? {
        return try {
            // dpaste.com uses form-urlencoded POST
            val formBody = okhttp3.FormBody.Builder()
                .add("content", content)
                .build()

            val request = Request.Builder()
                .url("https://dpaste.com/api/")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // dpaste.com returns the URL as plain text
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
            // paste.rs - simple API, just POST raw content
            val requestBody = content.toRequestBody("text/plain".toMediaType())

            val request = Request.Builder()
                .url("https://paste.rs/")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // paste.rs returns the URL as plain text
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Opens the system keyboard settings so user can enable Tiny Dictation keyboard.
     */
    private fun openKeyboardSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'Tiny Dictation' in the keyboard list", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening keyboard settings", e)
            Toast.makeText(this, "Could not open keyboard settings", Toast.LENGTH_SHORT).show()
        }
    }

}
