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
            v1.40.1:
            • Fix white bar below keyboard in light mode (force IME window to black)

            v1.40.0:
            • Fat finger correction: taps between keys now register on nearest key
            • Screen awake button: keep screen on while keyboard is open (sun icon in dictation bar)

            v1.39.5:
            • Fix white bar below keyboard in light/day mode

            v1.39.4:
            • Fix: hide prediction strip AND shrink container so key sizes stay identical
            • Keyboard is now 50dp shorter when predictions are off

            v1.39.3:
            • Swipe up on dictation bar to open keyboard panel
            • Swipe down on keyboard panel to close it

            v1.39.2:
            • Auto-pause transcription when phone plays audio (WhatsApp voice messages, YouTube, music)
            • Auto-resume when audio stops — no manual unmute needed
            • Prevents mic from transcribing phone speaker output

            v1.39.1:
            • Unified "Corrections" settings page — all correction features in one place
            • Three sections: During Dictation (Deepgram), After Dictation (AI), While Typing
            • Every correction layer is now independently toggleable
            • Moved correction toggles out of Appearance into dedicated Corrections page

            v1.39.0:
            • Toggle every feature on/off: Smart Format, Auto Punctuation, Numerals, Dictation (Deepgram Features)
            • Toggle AI features: Next-Word Prediction, Word Completion, Auto-Fix Sentences (Appearance)
            • Disabling features reduces latency — fewer API calls = faster keyboard
            • Smart Format and Auto Punctuation now respect user settings (were previously hardcoded on)

            v1.38.1:
            • Settings to disable word predictions and autocorrect (Appearance → toggles)
            • Auto-mute mic when opening QWERTY keyboard panel for typing (grey mic)
            • Manual unmute required to resume dictation after typing

            v1.38.0:
            • German dictionary support (50,000 words with umlauts)
            • Language switch button now also switches autocorrect/predictions dictionary
            • Disable autocorrect in URL bars and email fields (no more mangled URLs)

            v1.37.8:
            • FIX: Prediction middle tile no longer accumulates words in search bars
            • Clear autocorrect word state on input view restart and backward cursor movement
            • Fix dictation bar space button not clearing word state

            v1.37.7:
            • FIX: Word split no longer overrides prefix completions
            • "reall" now shows "really" (not "re all") since it's a valid prefix
            • Word split only shows when no prefix completions exist (e.g. "andstill")

            v1.37.6:
            • FIX: "iwant" → "i want" now works (added "i" and "a" to dictionary)
            • Word split now allows single-letter left parts (was requiring 2+ chars)

            v1.37.5:
            • Prediction strip now shows word splits (e.g. "andstill" → "and still")
            • Missing space detection appears as top suggestion while typing

            v1.37.4:
            • FIX: Predictions now preserve capitalization (e.g. "Testing" stays capitalized)
            • Was storing all letters as lowercase, so capital first letter was always lost

            v1.37.3:
            • FIX: Auto-fix no longer corrupts text when user types during API call
            • Was adding extra letters (e.g. "tTo") because deletion count didn't account for new typing
            • Now re-reads cursor position and preserves any text typed after the sentence

            v1.37.2:
            • FIX: Fast typing word merge bug - space now captures word on touch-down
            • Prevents "is pretty" becoming "ispretty" during fast typing
            • Fix "i" → "I" auto-capitalize (was broken because onSpace cleared word too early)

            v1.37.1:
            • Filter out single-letter predictions (no more standalone "i" in predictions)

            v1.37.0:
            • Removed sentence prediction feature entirely
            • Restored original keyboard layout (no extra UI elements)
            • Auto-fix only runs on typed text, not dictated text

            v1.36.6:
            • FIX: Keyboard button sizes restored - sentence bar now overlays prediction strip instead of adding height
            • Sentence prediction overlays word predictions when active, hides when empty

            v1.36.5:
            • Skip auto-fix for dictated text (Deepgram already handles corrections)
            • Auto-fix now only triggers for typed text (period key, punctuation popup)

            v1.36.4:
            • FIX: Sentence prediction bar no longer resizes keyboard (always reserves space)
            • Sentence bar now has gray box background matching prediction strip style

            v1.36.3:
            • FIX: AI word completion no longer shows same word you already typed
            • Reduced debounce from 400ms to 200ms for faster response

            v1.36.2:
            • AI word completion now fires from 1st letter (was 3rd)
            • Uses sentence context: "next time we a" → predicts "are"

            v1.36.1:
            • FIX: Disable ALL autocorrect/predictions/auto-fix in terminal apps
            • Word autocorrect, AI predictions, and sentence fix all skip Termius

            v1.36.0:
            • NEW: AI sentence completion bar above prediction strip
            • Shows ghosted text predicting how your sentence will end
            • Tap to accept and insert the completion
            • Regenerates as you type more letters
            • NEW: AI word completion in right prediction slot (Haiku)
            • Left + Middle = offline dictionary, Right = AI completion
            • FIX: Standalone "i" auto-capitalizes to "I" on space/prediction tap

            v1.34.6:
            • Auto-fix adds trailing space after improved sentence for seamless typing

            v1.34.5:
            • FIX: Auto-fix now capitalizes first letter of sentence

            v1.34.4:
            • FIX: Next-word prediction now triggers after tapping a word prediction
            • Previously only fired on space bar - now fires on both

            v1.34.3:
            • DEBUG: Verbose logging to diagnose next-word prediction not firing

            v1.34.2:
            • Disable AI next-word prediction in terminal apps (Termius, etc.)

            v1.34.1:
            • FIX: Next-word prediction not showing - auto-fix undo was blocking it
            • Shows "···" loading indicator while Haiku is thinking
            • Added debug logging to diagnose prediction issues

            v1.34.0:
            • NEW: AI next-word prediction powered by Claude Haiku
            • After pressing space, predicts 3 most likely next words
            • Tap a prediction to insert it and get the next prediction
            • Predictions auto-cancel when you start typing

            v1.33.1:
            • FIX: Improve button broken - wrong Haiku model ID (20251101 → 20251001)

            v1.33.0:
            • NEW: Auto AI sentence fix - triggers after period or dictation
            • Prediction strip turns purple while AI is working
            • Turns green with "Undo fix" button when done (10s timeout)
            • Tap "Undo fix" to revert to original text
            • Uses Haiku for fast response (~500ms)
            • Works for both typed sentences and dictated text

            v1.32.5:
            • NEW: Missing space detection - "thisis" → "this is", "ofthe" → "of the"
            • Uses frequency ranking to pick best split (won't break "together" into "to get her")
            • Switched Improve button from Opus to Haiku (60x cheaper, 3-5x faster)

            v1.32.4:
            • Word frequency ranking for predictions (Google 10K frequency data)
            • "th" → "the", "that", "this" instead of alphabetical
            • "experi" → "experience", "experienced", "experiences"
            • Spell corrections also ranked by frequency for ties

            v1.32.3:
            • Expanded dictionary from 3,198 to 63,730 words
            • Words like "completion", "experience", "keyboard" now available
            • Optimized spell correction with first-letter indexing for large dictionary
            • First-letter typo correction (checks QWERTY-adjacent first letters)

            v1.32.2:
            • Word completion: type "experi" → get "experience", "experiment", "experiences"
            • Prediction strip shows 3 words: middle=best, left/right=alternatives
            • Tap any prediction to complete the word
            • Completions sorted by word length (shorter=more common first)
            • Spell corrections shown as fallback when no prefix matches

            v1.32.1:
            • FIX: Reverted key margins to original 4dp (keys stay same size as before)
            • FIX: Prediction strip no longer resizes keys when suggestions appear/disappear
            • Strip always occupies its space, text clears when no suggestions

            v1.32.0:
            • NEW: Offline autocorrect with QWERTY-aware key proximity correction
            • Autocorrect uses bundled 3,198-word dictionary (no API calls, instant)
            • Adjacent-key typos weighted lower (e.g., "hrlp" → "help" because R is next to E)
            • Prediction strip shows suggestions as you type, tap to apply
            • Backspace after autocorrect undoes the correction
            • Fixed empty prediction strip boxes always showing (now hidden when inactive)
            • Reduced key margins from 4dp to 2dp for larger touch targets

            v1.31.5:
            • Fixed "scratch that" voice command not working in many apps
            • Use system-level Select All instead of unreliable text cursor approach
            • Removed dead getModel/setModel/buildDeepgramParams from PreferenceManager

            v1.31.4:
            • Removed Deepgram-handled voice commands from VoiceCommandProcessor (period, comma, question mark, exclamation mark, colon, new line, new paragraph)
            • Cleaned up Voice Commands screen to only show custom commands
            • Added note about Deepgram dictation handling basic punctuation automatically

            v1.31.3:
            • Deepgram dictation mode now ON by default (say "period", "comma", "new line" etc.)
            • Removed dead auto-punctuation toggle from Audio settings (was not connected to anything)
            • Removed duplicate Deepgram API key field from Transcription settings (use API Keys screen)

            v1.31.2:
            • Removed ~15 unused PreferenceManager methods (note bubble, hide bubbles, bar orientation/transparency, dictation mode, old model methods)
            • Removed deprecated constants (MODE_BUBBLE, MODE_COMPANION, ORIENTATION_*)
            • Removed unused buildDeepgramParams() (replaced by DeepgramClient.buildParams())
            • Inlined always-true currentProviderNeedsLanguageToggle()

            v1.31.1:
            • Removed dead activity_settings.xml layout (1,274 lines)
            • Fixed stale Groq references in comments (now Anthropic)
            • Removed orphaned prediction stub code
            • Extracted MIC button colors to companion object constants

            v1.31.0:
            • Major cleanup: removed unused engines (Groq, OpenAI, AssemblyAI, Gemini)
            • Removed correction, prediction, and dictionary systems
            • Removed GIF/Giphy integration
            • Simplified to Deepgram + Anthropic only (with Vosk/Whisper offline fallback)
            • Cleaned up settings screens and API key management
            • ~5,000 lines of dead code removed

            v1.30.0:
            • Deepgram Features settings: keywords, find & replace, numerals, dictation
            • Keywords: boost recognition of specific words (names, technical terms) — up to 100 per language
            • Find & Replace: fix recurring transcription errors — up to 200 rules per language
            • Numerals: convert spoken numbers to digits (enabled by default)
            • Dictation: say "period", "comma" to insert punctuation (English only)
            • All settings stored per-language (EN/DE have separate lists)

            v1.29.3:
            • Double-tap MIC to enter PTT mode (replaces long-press)
            • Double-tap MIC in PTT to exit back to normal mode (replaces quick-tap)
            • Single tap in PTT is now a no-op (prevents accidental exits)
            • Normal mode tap toggle-mute has ~300ms delay for double-tap detection

            v1.29.2:
            • FIX: PTT mode now sticks after long-press (ACTION_UP was routed to wrong handler)
            • Touch handler locked to the mode active at ACTION_DOWN for entire touch sequence

            v1.29.1:
            • FIX: Long-press MIC to enter PTT now works (scroll view was intercepting touch)
            • Added requestDisallowInterceptTouchEvent to prevent scroll stealing long-press

            v1.29.0:
            • Push-to-Talk mode: long-press MIC to enter, hold to record, release to transcribe
            • PTT bypasses VAD completely — perfect for noisy environments (trains, buses)
            • Visual states: blue (PTT idle), orange (buffering), red+pulse (streaming)
            • Quick tap in PTT mode exits back to normal dictation
            • Normal mode: tap MIC now toggles mute (unified mute replaces old toggle+lock)
            • PTT preference persists across app restarts

            v1.28.3:
            • FIX: Terminal detection now uses prefix matching (fixes Termius com.server.auditor.ssh.client)
            • FIX: Terminal mode now applied when TranscriptionManager is first created (was null before)
            • These two bugs caused v1.28.2 terminal raw mode to never actually activate

            v1.28.2:
            • Terminal raw mode: disables all Deepgram formatting (smart_format, punctuation, interim results)
            • Terminal apps now get pure raw transcription with no corrections or word replacements
            • Deduplication guard prevents repeated final results from Deepgram in terminals
            • Fixes persistent text doubling in Termius, Termux, and other terminal apps

            v1.28.1:
            • FIX: Dictation text no longer appears doubled in terminal apps (Termius, Termux, etc.)
            • Skip interim results in terminals where deleteSurroundingText doesn't work

            v1.28.0:
            • AI models switched: sentence correction → Claude Haiku, Improve button → Claude Opus 4.5
            • All AI prompts rewritten: grammar/spelling/punctuation only, no rephrasing or restructuring
            • Technical jargon protection: git, npm, docker, kubectl, and 100+ terms skip autocorrect
            • CLI flags (--verbose, -f) no longer autocorrected
            • Interim transcription: text appears in real-time as you speak (no more waiting for final result)
            • Removed bottom-left language indicator overlay (language shown on space bar + dictation bar)

            v1.27.7:
            • Space bar now shows current language name (Samsung-style: "English" / "Deutsch")
            • Language updates on space bar when toggling language

            v1.27.6:
            • FIX: Select All no longer crashes Edge browser (uses system performContextMenuAction)
            • Claude panel: Added /resume and /mcp to Quick Actions
            • Claude panel: Launch section simplified to 2 options (claude -d and claude -d -c)

            v1.27.4:
            • A key long-press: German mode → popup with ä and @
            • A key long-press: English mode → inserts @ directly
            • Hint on A key shows @ in English, ä in German
            • FIX: "Simon" no longer autocorrected to "soon" (stricter fuzzy matching for short words)
            • FIX: Snippet creation + button now shows dialog (was silently failing)

            v1.27.3:
            • FIX: Proper nouns like "Simon" no longer autocorrected to "iron"
            • First-character guard prevents corrections where first letter changes to non-adjacent key

            v1.27.2:
            • FIX: Last 2-3 words no longer dropped during Deepgram dictation
            • Sends Finalize message before closing WebSocket connection
            • NEW: Always-visible language indicator (EN/DE) at bottom-left
            • Tap the indicator to toggle language without scrolling
            • NEW: /coordinator command added to Agent Modes panel

            v1.27.5:
            • NEW: Claude Opus 4.5 for sentence-level AI correction
            • Claude Haiku also available (uses Anthropic API directly)
            • Requires Anthropic API key (Settings → API Keys)

            v1.27.1:
            • FIX: Shell panel buttons now work (@ | ~ ` $ # % ^ & * \ /)
            • FIX: @ key outputs @ in symbols mode (was outputting 'a')
            • FIX: O and U keys output correct symbols in symbols mode
            • Long-press umlauts (ä ö ü) disabled in numbers mode
            • Umlaut hints shown on A, O, U keys (small labels)

            v1.27.0:
            • URL protection: autocorrect no longer mangles URLs/links
            • Expanded TLD detection (.io, .dev, .ai, .app, +30 more)
            • AI corrections (Groq & OpenRouter) now preserve URLs
            • Rejection window: 5s → 15s (more time to undo autocorrect)
            • Fat finger tolerance: QWERTY-aware typo correction
            • Adjacent key typos (e.g., 'r' instead of 't') now prioritized

            v1.26.4:
            • NEW: API Keys settings screen
            • All API keys in one place (Settings → API Keys)
            • Transcription: Deepgram, Groq, OpenAI, AssemblyAI, Gemini
            • AI Services: OpenRouter, Anthropic (direct Claude access)
            • Keys stored securely on device with password toggle

            v1.26.3:
            • FIX: Snippet panel crash fixed
            • Added themed LayoutInflater for Material components
            • Added try-catch to prevent crashes on panel inflation
            • Clipboard panel also fixed for robustness

            v1.26.2:
            • FIX: "Simon" no longer corrected to "iron"
            • Proper nouns (capitalized mid-sentence) now skip correction
            • Stricter fuzzy matching for short words (5 chars or less)
            • Edit distance scaled by word length to prevent bad matches

            v1.26.1:
            • NEW: Smart AI rewrite on double-tap space!
            • Double-tap space triggers Claude Opus to rewrite your text
            • Fixes typos, garbled text, and unclear sentences
            • Requires OpenRouter API key (Settings → API Keys)
            • Falls back to Groq if OpenRouter not configured

            v1.26.0:
            • NEW: Autocorrect override learning!
            • If you backspace a correction and retype the original word, we remember it
            • "Won't correct 'vwo' again" - never re-corrects rejected words
            • Persisted across sessions - your preferences are saved
            • Works for typos, fuzzy matches, and word splits

            v1.25.3:
            • NEW: Manual snippet creation (+ button on snippets panel)
            • NEW: Text correction enabled by default
            • FIX: Clipboard panel now refreshes when switching tabs
            • FIX: Cursor dragging acceleration improved
            • FIX: Double-tap for auto-correction
            • FIX: Stuck word separation fixed
            • Removed duplicate AI settings from Appearance panel
            • Keyboard height shrinks when AI predictions disabled

            v1.25.2:
            • Renamed "Claude" tab to "Agent"
            • NEW: Agent Modes section with /worker, /planner, /qa, /hyper
            • NEW: /usage command in Quick Actions
            • Removed y/n buttons (never used)
            • Reorganized commands by usage frequency
            • /init and /clear now at top of Quick Actions

            v1.25.1:
            • FIX: Keyboard shrinks when AI predictions disabled
            • Previously keys grew taller when prediction bar hidden
            • Now container height reduces from 320dp to 270dp
            • Keys stay consistent size regardless of predictions

            v1.25.0:
            • NEW: Two-stage AI text correction ("Phat Fingers")!
            • Stage 1: Instant local typo fixes after each word
            • Stage 2: AI-powered sentence correction at punctuation/pause
            • Fixes typos, split stuck words (iwant → I want)
            • Context-aware corrections (by → buy when shopping)
            • Settings: Text Correction (new settings page)
            • Import contacts to skip list (won't correct names)
            • Learns from your corrections over time
            • FIX: NGram predictions now work instantly when offline
            • Skip Groq API when no network, use NGram directly

            v1.23.1:
            • NEW: Cursor trackpad mode on space bar!
            • Long-press space bar and drag to move cursor
            • Works like iOS/GBoard trackpad for text navigation
            • Haptic feedback when entering mode and on each cursor move

            v1.23.0:
            • NEW: German umlaut support on QWERTY keyboard
            • Long-press A for ä/Ä
            • Long-press O for ö/Ö
            • Long-press U for ü/Ü
            • Long-press Shift to toggle German mode (shows "DE")
            • Umlauts respect shift/caps lock state

            v1.22.5:
            • PERF: Reduced Trie candidate limit from 100 to 50
            • Predictions should be faster now
            • TreeMap ensures alphabetical order, so 50 is enough

            v1.22.4:
            • FIX: Trie now uses sorted (TreeMap) children
            • "the" now correctly appears before "things"
            • Alphabetical traversal guarantees short words first

            v1.22.3:
            • FIX: Common words now appear first in predictions
            • "the", "that", "this" etc. now show before longer words
            • Fixed Trie traversal order issue

            v1.22.2:
            • FIX: Word prediction tap now works reliably
            • Fixed race condition causing garbled text when tapping predictions
            • Note: Predictions don't work in terminal apps (Termius, etc.)

            v1.22.1:
            • FIX: Word predictions now English-only
            • No more random Italian/foreign words in suggestions

            v1.22.0:
            • NEW: Auto-correct for QWERTY keyboard
            • Fixes spelling and spacing errors at sentence end
            • Triggers after typing period/question/exclamation + space
            • Enable in: Settings → Appearance → Auto-Correct
            • Language button now always visible (all transcription models)

            v1.21.0:
            • NEW: Customizable dictation bar - toggle buttons on/off!
            • NEW: Customizable panels - show only the tabs you need
            • NEW: Compact mode - reduce dictation bar size by 40%
            • FIX: Emoji button now works after keyboard recreation
            • FIX: Keyboard button always opens ABC panel (no tab memory)
            • All settings in: Settings → Appearance

            v1.20.0:
            • FIX: Claude panel commands now work in Terminus!
            • NEW: Predictions auto-hide in terminal apps
            • NEW: Toggle to disable AI predictions in Settings
            • Supports: Terminus, Termius, ConnectBot, JuiceSSH, Termux

            v1.19.2:
            • Revert prediction engine to simple word mode only
            • Removed sentence completion (multi-word predictions)
            • Predictions now always return single words

            v1.19.1:
            • FIX: Improve button now works in Beeper!
            • Fallback to internal buffer for apps that don't expose text
            • Uses backspace method to replace text in restricted apps
            • Better debug logging for troubleshooting

            v1.19.0:
            • NEW: "Improve" button on dictation bar!
            • Tap to enhance text readability with AI
            • Makes text ~20-30% shorter and clearer
            • Adds structure (bullet points) where helpful
            • Uses professional, nonviolent language
            • Requires Groq API key (same as predictions)

            v1.18.49:
            • FIX: Prediction tap now replaces word in Termius!
            • Uses internal buffer to find partial word to delete
            • Type "did" → tap prediction → replaces correctly

            v1.18.48:
            • Clear internal buffer on Enter (after terminal commands)
            • Better buffer management for terminal apps

            v1.18.47:
            • FIX: Predictions now update in Termius!
            • Added internal text buffer for terminal apps
            • Tracks what you type when app doesn't share text
            • Type "pred" → get "predict", "prediction", etc.

            v1.18.46:
            • Added DISPLAY logging to confirm predictions show on screen
            • Logs will show "DISPLAY left='x' center='y' right='z'"

            v1.18.45:
            • Better debug logging for Termius issue
            • Logs textBefore content to diagnose prediction problems
            • Please test in Termius and share debug logs

            v1.18.44:
            • Debug logging for prediction updates in terminal apps
            • Investigating Termius prediction issue

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
