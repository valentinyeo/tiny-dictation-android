# Tiny Dictation - Todo List

## In Progress
- [ ] (none)

## Pending

### Keyboard Enhancements
- [x] **Emoji button** - Jetpack EmojiPicker, expandable panel (v1.11.37)
- [x] **Simple full keyboard** - Basic QWERTY for when dictation isn't enough (v1.11.38)
- [ ] **Cursor keys row** - Expandable row with ←↑↓→ keys for text navigation
- [ ] **Giphy integration** - Add GIF picker alongside emoji picker (like WhatsApp)
- [ ] **Emoji size adjustment** - Make emojis slightly smaller in picker

### Transcription Improvements
- [ ] Upgrade Deepgram to Nova-3 (better noise handling)
- [ ] Add Whisper Small model option for offline noisy environments
- [ ] Make NoiseSuppressor optional (Nova-3 works better with raw audio)

### Other
- [ ] Settings screen reorganization (plan exists but not implemented)
- [ ] Review offline transcription plan (`~/.claude/plans/pure-nibbling-phoenix.md`)

## Completed
- [x] v1.11.38: QWERTY keyboard with shift/caps lock support
- [x] v1.11.37: Emoji picker with Jetpack EmojiPicker
- [x] v1.11.36: Fix language toggle not applying during recording
- [x] v1.11.35: Language switch auto-restarts mic
- [x] v1.11.34: Brightest pure red (#FF0000) for recording mic
- [x] v1.11.30: Version number more readable on keyboard
- [x] v1.11.28: Fix language persisting after device sleep/wake
- [x] Research: Best models for noisy environments (Nova-3, Whisper Small)
- [x] v1.11.27: Auto-stop after silence (traditional dictation mode)
- [x] v1.11.26: Imperial Red mic icon + multiple fixes
- [x] Remove "Type instead of paste" toggle from settings
- [x] Remove paste button from keyboard - text types directly
