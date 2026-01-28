# Keyboard Visual Configuration

This file documents all configurable values for the keyboard appearance.
**Update this file when changing any visual values.**

---

## KEYBOARD PANEL (QWERTY)

| Variable | Value | File | Line |
|----------|-------|------|------|
| `panel_total_height` | 320dp | `keyboard_tabbed_panel.xml` | 123 |
| `prediction_strip_height` | 36dp | `keyboard_qwerty_panel.xml` | 17 |
| `number_row_height` | 42dp | `keyboard_qwerty_panel.xml` | 79 |
| `character_row_height` | weight=1 (flexible) | `keyboard_qwerty_panel.xml` | 100, 120, 140 |
| `space_bar_row_height` | weight=1 (same as character) | `keyboard_qwerty_panel.xml` | 162 |
| `row_spacing` | 8dp (uniform) | `keyboard_qwerty_panel.xml` | 81, 101, 121, 141 |
| `key_horizontal_margin` | 4dp | `styles.xml` | 8 |
| `key_text_size` | 22sp | `styles.xml` | 10 |
| `key_corner_radius` | 6dp | `keyboard_key_background.xml` | 7, 13 |

## KEYBOARD KEY COLORS

| Variable | Value | File | Line |
|----------|-------|------|------|
| `keyboard_background` | #000000 | `colors.xml` | 39 |
| `regular_key_background` | #2D2D2F | `colors.xml` | 40 |
| `special_key_background` | #2D2D2F | TODO: needs separate color | — |
| `key_text_color` | #FFFFFF | `colors.xml` | 41 |
| `key_pressed_color` | #6750A4 | `colors.xml` | 4 |

## DICTATION BAR

| Variable | Value | File | Line |
|----------|-------|------|------|
| `dictation_bar_background` | #000000 | `view_keyboard.xml` | 53 |
| `dictation_bar_button_background` | #2D2D2F | `keyboard_button_background.xml` | 12 |
| `dictation_bar_icon_color` | #FFFFFF | via `app:tint` | — |
| `dictation_bar_button_size` | 48dp | `view_keyboard.xml` | each button |
| `dictation_bar_button_spacing` | 6dp | `view_keyboard.xml` | marginStart |
| `dictation_bar_min_height` | 64dp | `view_keyboard.xml` | 54 |
| `dictation_bar_padding_horizontal` | 8dp | `view_keyboard.xml` | 51 |
| `dictation_bar_padding_vertical` | 10dp | `view_keyboard.xml` | 52 |
| `dictation_bar_button_corner_radius` | 6dp | `keyboard_button_background.xml` | 7, 13 |

---

## KNOWN ISSUES (TODO)

- [ ] **Special key background**: Shift, backspace, enter, !#1 use same color as regular keys (need accent)
- [ ] **Dictation bar icons broken**: Space, backspace, enter icons have hardcoded #000000 fill
- [ ] **History icon faint**: Uses #49454F stroke instead of #FFFFFF

### Icon files needing fix:
- `ic_space_bar.xml` - change fillColor to #FFFFFF
- `ic_backspace.xml` - change fillColor to #FFFFFF
- `ic_enter.xml` - change fillColor to #FFFFFF
- `ic_history.xml` - change strokeColor to #FFFFFF

---

## CHANGELOG

- **v1.18.25**: Uniform row spacing (8dp), space bar row same height as character rows
