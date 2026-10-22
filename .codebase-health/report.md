# Codebase Health Report — Tiny Dictation Android

**Date:** 2026-04-05
**Overall Score:** 45/100 (D)

---

## Scorecard

| Dimension | Static | AI Modifier | Final | Weight |
|---|---|---|---|---|
| Token Efficiency | 27 | +5 | **32** | 25% |
| AI Agent Friendliness | 47 | +10 | **57** | 25% |
| TDD Readiness | 25 | 0 | **25** | 20% |
| Human Readability | 73 | +5 | **78** | 15% |
| Development Speed | 15 | +15 | **30** | 10% |
| Stability/Robustness | 50 | +10 | **60** | 5% |

**Weighted Overall:** 32×0.25 + 57×0.25 + 25×0.20 + 78×0.15 + 30×0.10 + 60×0.05 = **45/100**

---

## Per-Dimension Breakdown

### Token Efficiency (32/100)

**Key metrics:**
- 8 files exceed 500 lines
- 24 functions exceed 50 lines
- 21% duplication ratio

**Critical file:** `DictationKeyboardService.kt` at **5109 lines** — this single file is the main drag. It handles keyboard creation, all button handlers, QWERTY setup, tab management, clipboard, history, snippets, shell panel, Claude panel, autocorrect, predictions, PTT mode, and more. An AI agent working on any keyboard feature must load this entire file.

**Other large files:** AboutActivity.kt (1080), PreferenceManager.kt (1051), TranscriptionManager.kt (818), SherpaOnnxEngine.kt (593), AudioRecorder.kt (516)

**AI modifier (+5):** Non-service files are reasonably focused and well-scoped. The problem is concentrated in one god-class.

### AI Agent Friendliness (57/100)

**Key metrics:**
- No discoverable build commands in package.json/Makefile (false positive — documented in CLAUDE.md)
- 25 generic error messages
- No `.claude/settings.json`

**AI modifier (+10):** CLAUDE.md is comprehensive — build commands, file locations, architecture, terminology all well-documented. Error messages in practice include DebugLog context. The static analyzer misses that this is a well-documented Android project.

### TDD Readiness (25/100)

**Key metrics:**
- 0 test files for 38 source files (0.00 ratio)
- No test framework detected

**AI modifier (0):** No tests exist. While Android UI testing is harder, unit tests for VoiceCommandProcessor, ClipboardHistoryManager, TranscriptionFilter, AutocorrectManager would be straightforward and high-value.

### Human Readability (78/100)

**Key metrics:**
- No formatter config

**AI modifier (+5):** Code is genuinely well-written. Good naming conventions (TranscriptionState enum, CommandResult sealed class), useful doc comments, clear patterns. Kotlin's type system helps readability naturally.

### Development Speed (30/100)

**Key metrics:**
- No hot reload / watch mode
- "No type system" (false positive — Kotlin is strongly typed)

**AI modifier (+15):** Kotlin IS typed. Android doesn't have hot reload for IME services. Gradle build is fast (~30s incremental). The static score is unfairly low due to false positives.

### Stability/Robustness (60/100)

**Key metrics:**
- No dependency lockfile
- "Hardcoded secrets" detected (likely API key field names, not actual secrets)

**AI modifier (+10):** Error handling in audio/transcription code is thoughtful — proper cleanup in onDestroy, try-catch with specific logging, graceful fallback between engines.

---

## AI Sampling Observations

**Files sampled:** TranscriptionManager.kt, AudioRecorder.kt, VoiceCommandProcessor.kt, ClipboardHistoryManager.kt, ModelManager.kt

1. **Naming quality:** Excellent. Constants like `SPEECH_RMS_THRESHOLD`, `AUDIO_BUFFER_MAX_CHUNKS` are self-documenting. Enum values like `TranscriptionState.STANDBY` are clear.

2. **Abstraction level:** Appropriate. VoiceCommandProcessor uses a clean Command data class pattern. ClipboardHistoryManager has proper singleton with lifecycle methods.

3. **Self-containment:** Good. Each file can be understood independently. Clear interfaces between components (callbacks, listeners).

4. **Documentation:** Useful where present. Class-level docs explain purpose and constraints. Not over-documented.

5. **Error messages:** Specific. DebugLog messages include component name and context (e.g., "AudioRecorder: Failed to enable AEC - ${e.message}").

---

## Prioritized Fix List

| Priority | Issue | Impact | Effort |
|---|---|---|---|
| 1 | Split DictationKeyboardService.kt | High (token efficiency, AI friendliness) | High |
| 2 | Add unit tests for pure-logic classes | High (TDD readiness) | Medium |
| 3 | Add .editorconfig | Low (readability) | Trivial |
| 4 | Add .claude/settings.json | Low (AI friendliness) | Trivial |
| 5 | Extract long functions (50+ lines) | Medium (token efficiency) | Medium |
| 6 | Reduce duplication (21%) | Medium (token efficiency) | Medium |
| 7 | Add gradle dependency locking | Low (stability) | Trivial |

### Recommended splits for DictationKeyboardService.kt:
- `QwertyKeyboardHandler.kt` — QWERTY setup, letter keys, shift, numbers mode
- `TabbedPanelManager.kt` — Tab switching, panel inflation, swipe gestures
- `DictationBarHandler.kt` — Button handlers, compact mode, visibility
- `PttHandler.kt` — Push-to-talk state machine
- `AutocorrectHandler.kt` — Prediction strip, autocorrect, AI completion
- `ClaudeShellPanelHandler.kt` — Claude and shell panel setup
