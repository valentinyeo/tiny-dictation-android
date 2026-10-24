# IGSH-484: Deepgram streaming audit

## Verdict

Verified client mismatches fixed; Valentin's phone failure is not yet reproduced.
No phone logs, installed version, saved settings, API key, or account status were available.
Nova-2 is still supported: changing the default to Nova-3 is not a justified fix.

The public repository has no `main` branch. Its remote HEAD is `master`.
This isolated worktree starts at `origin/master`, `aaefee1385f54c75d195a515ee581d47e2f1e9ac`.
The main checkout was read only; a separate bare repository holds this worktree.
`CLAUDE.md`, `AGENTS.md`, and `todo.md` were read before source analysis.
The ticket's no-build/no-test/no-push restrictions override the repository workflow.

## Client trace

References below name patched files unless explicitly marked baseline.

| Item | Source and exact value |
| --- | --- |
| Endpoint | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:19`: `wss://api.deepgram.com/v1/listen` |
| Model | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:25` and `:93`: default `nova-2`; actual saved model comes from `app/src/main/java/com/tinydictation/android/util/PreferenceManager.kt:374`, applied by `app/src/main/java/com/tinydictation/android/transcription/TranscriptionManager.kt:586` |
| Audio parameters | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:20`: `encoding=linear16&sample_rate=16000&channels=1` |
| Other query parameters | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:35-68`: model, smart_format, punctuate, interim_results, endpointing=150, language, optional numerals/dictation, repeated keyterm or keywords, repeated replace, tag=tiny-dictation. Terminal mode disables smart_format, punctuate, and interim_results. |
| Token input | `app/src/main/java/com/tinydictation/android/settings/ApiKeysSettingsActivity.kt:52-56`: trimmed Settings > API Keys input; no token minting or backend |
| Token storage | `app/src/main/java/com/tinydictation/android/util/PreferenceManager.kt:137-152`: EncryptedSharedPreferences; getApiKey/setApiKey |
| Token wiring | `app/src/main/java/com/tinydictation/android/service/DictationKeyboardService.kt:455-466` reads saved key and constructs manager; `app/src/main/java/com/tinydictation/android/transcription/TranscriptionManager.kt:71` constructs DeepgramEngine; `app/src/main/java/com/tinydictation/android/transcription/engines/DeepgramEngine.kt:103-104` supplies key to client |
| Authorization | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:123`: `Authorization: Token $apiKey` |
| Close handling | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:175-190`: onClosing/onClosed clear connected state, account usage, map close errors; onClosing acknowledges close |
| Failure handling | `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:194-204`: onFailure logs, maps HTTP status and dg-error header; synchronous connection exceptions are forwarded at `:129-131` |
| Error mapping | `app/src/main/java/com/tinydictation/android/transcription/DeepgramErrors.kt:12-33`: actionable 401/403/402 messages, HTTP errors, abnormal close reasons, network fallback |
| Keyboard delivery | `app/src/main/java/com/tinydictation/android/transcription/TranscriptionManager.kt:297-320` and `:488-503`: normal/PTT error callbacks. `app/src/main/java/com/tinydictation/android/service/DictationKeyboardService.kt:525-538`: logs, existing toast, new persistent keyboard message |
| Audio bytes | `app/src/main/java/com/tinydictation/android/audio/AudioRecorder.kt:29-33` captures 16 kHz, mono PCM16; `:373-381` writes little-endian bytes |

## Current API comparison

Official pages were retrieved directly during this audit.

| Area | Finding | Documentation |
| --- | --- | --- |
| Endpoint | Matches v1 streaming Listen; no endpoint migration needed. | https://developers.deepgram.com/reference/speech-to-text/listen-streaming |
| Nova-2 versus Nova-3 | Both supported. Nova-3 is recommended for general/noisy/multilingual audio, but Nova-2 is not retired. Keep the saved/default model unchanged. | https://developers.deepgram.com/docs/models-languages-overview |
| Authorization | Correct for the saved API key: `Authorization: Token`. `Bearer` is for a temporary JWT, which this app does not request. | https://developers.deepgram.com/reference/speech-to-text/listen-streaming and https://developers.deepgram.com/docs/authenticating |
| Encoding / sample rate / channels | Correct: raw little-endian PCM16, 16000 Hz, mono agrees with the query. | https://developers.deepgram.com/reference/speech-to-text/listen-streaming |
| Endpointing | `150` ms is valid, not a mismatch. Docs recommend `100` ms for codeswitching, but that is not a requirement. Leave 150 unchanged. | https://developers.deepgram.com/reference/speech-to-text/listen-streaming and https://developers.deepgram.com/docs/multilingual-code-switching |
| **Mismatch: Nova-3 prompting** | Baseline `DeepgramClient.kt:58-60` always emits `keywords`, which is unsupported on Nova-3. Patched `:57-60` emits `keyterm` for Nova-3 and preserves `keywords` for Nova-2. Repeated URL-encoded terms preserved. | https://developers.deepgram.com/docs/keywords and https://developers.deepgram.com/docs/keyterm |
| **Mismatch: automatic language** | Baseline `DeepgramClient.kt:48-50` omits language for app value `auto`; API omission defaults to English, not automatic recognition. Patched `:48-49` explicitly sends `language=multi`. | https://developers.deepgram.com/docs/language and https://developers.deepgram.com/docs/multilingual-code-switching |
| **Mismatch: error event schema** | Baseline `DeepgramClient.kt:218-223` recognizes only an `error` property. Current `type=Error` events contain `description`, not `error`, and were dropped as messages without a channel. Patched `:218-221` forwards description to existing error handling. | https://developers.deepgram.com/reference/speech-to-text/listen-streaming |
| **Mismatch: no-status closure** | Baseline `DeepgramErrors.kt:28` suppresses both 1000 and 1005. Only 1000 denotes normal closure; 1005 means no status received. Patched mapping reports 1005 without changing client-initiated-close suppression. | https://www.rfc-editor.org/rfc/rfc6455#section-7.4.1; Deepgram close-error guidance: https://developers.deepgram.com/docs/stt-troubleshooting-websocket-data-and-net-errors |

Other existing options (`replace`, `numerals`, `dictation`, formatting, interim_results, tag, Finalize, KeepAlive) are documented streaming options, not evidence of endpoint incompatibility. Dictation is an English-only feature; the app already labels it English-only. No model/language defaults or formatting preferences were changed.

## Exactly where baseline failures are silent

All references in this section are at baseline `aaefee1385f54c75d195a515ee581d47e2f1e9ac`.

- `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:218-223`: `type=Error` without an `error` property falls through and returns for missing channel. No error callback or log. Fixed.
- `app/src/main/java/com/tinydictation/android/transcription/DeepgramErrors.kt:28`, reached from `DeepgramClient.kt:181` and `:190`: close 1005 maps to null. No close log or error callback; engine can keep buffering while manager thinks it is streaming. Fixed. 1000 remains intentionally quiet.
- `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:234-240`: missing socket/session, disconnected state, or a false WebSocket.send result return false without logging. `app/src/main/java/com/tinydictation/android/transcription/engines/DeepgramEngine.kt:139` and `:173` ignore that result. Existing behavior retained; abnormal close/onFailure callbacks still report errors.
- `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:277-279` and `:291-293`: Finalize/KeepAlive return false silently; `app/src/main/java/com/tinydictation/android/transcription/engines/DeepgramEngine.kt:179` and `:196` ignore these results. Retained; no new retry policy introduced.
- `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:177`, `:187`, `:196`: stale or client-closed socket callbacks return without logging. Intentional lifecycle protection in `DeepgramSocketEvents.kt:3-4`; retained.
- `app/src/main/java/com/tinydictation/android/transcription/DeepgramClient.kt:215`, `:222-225`: blank/non-transcript/empty-transcript messages return silently; normal for metadata and empty results, except the Error-frame bug above.
- `app/src/main/java/com/tinydictation/android/transcription/TranscriptionManager.kt:299` and `:490`: callback delivery is suppressed after listening stops; intentional lifecycle behavior, retained.

401/403 and network failures are **not** silently swallowed on current remote default: `DeepgramClient.kt:199-204` logs/forwards, `DeepgramErrors.kt:12-19` maps authentication errors, `DeepgramEngine.kt:111-113` and `TranscriptionManager.kt:300` / `:491` log, and `DictationKeyboardService.kt:521-530` logs/toasts. The existing toast was transient, not a persistent keyboard message.

## Patch boundaries and regression evidence

- New error-only keyboard TextView: `app/src/main/res/layout/view_keyboard.xml:32-40`. Hidden normally, wrap-content; no key-row heights or tabbed-panel dimensions changed. No new controls. Cleared on the next nonblank transcript at `DictationKeyboardService.kt:470-472`.
- Existing 401/403/network/abnormal-close text now also stays visible in the keyboard at `DictationKeyboardService.kt:530-533`.
- No timeouts, buffering caps, retries, stream gates, HTTP authentication behavior, or fatal/nonfatal policies were added or changed. Reporting formerly swallowed errors uses the existing policy.
- Version becomes `1.43.2` / `371`; About CHANGELOG records the patch.

Regression tests, reviewed as source only:

1. `com.tinydictation.android.transcription.DeepgramProtocolTest.nova3UsesKeytermsAndMultilingualLanguage` (`app/src/test/java/com/tinydictation/android/transcription/DeepgramProtocolTest.kt:29`). Against baseline, the `language=multi` and `keyterm` assertions fail and the absence-of-keywords assertion fails. Patched builder produces all expected values while preserving replacements/numerals/dictation.
2. `com.tinydictation.android.transcription.DeepgramSocketEventsTest.closeWithoutStatusReportsConnectionLoss` (`app/src/test/java/com/tinydictation/android/transcription/DeepgramSocketEventsTest.kt:66`). Against baseline, `requireNotNull(mapDeepgramError(closeCode=1005))` fails. Patched mapping returns the expected nonfatal connection error.
3. `DeepgramProtocolTest.nova2PreservesKeywordsAndExplicitLanguage` guards unchanged Nova-2 behavior. Existing DeepgramErrorsTest covers 401/403, network failure, 1008, and 1011; existing socket tests guard client-close/stale-event suppression.

No tests or builds were run on this VPS. Red/green execution, Android compilation, keyboard geometry/accessibility, actual 401/403 display, and successful live transcription remain unproven. Run regression tests on an allowed build host, then install on Valentin's phone and test both microphone modes, valid/invalid/restricted keys, and network loss. A device is required to establish the actual phone cause.
