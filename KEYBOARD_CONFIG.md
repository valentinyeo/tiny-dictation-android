# Keyboard Visual Configuration

This file documents all configurable values for the keyboard appearance.
**Update this file when changing any visual values.**

---

## KEYBOARD PANEL (QWERTY)

| Variable | Value | File | Line |
|----------|-------|------|------|
| `panel_total_height` | 324dp | `keyboard_tabbed_panel.xml` | 123 |
| `prediction_strip_height` | 36dp | `keyboard_qwerty_panel.xml` | 17 |
| `number_row_height` | 43dp | `keyboard_qwerty_panel.xml` | 79 |
| `character_row_height` | weight=1 (~54dp) | `keyboard_qwerty_panel.xml` | 100, 120, 140 |
| `space_bar_row_height` | weight=1 (same as character) | `keyboard_qwerty_panel.xml` | 162 |
| `row_spacing` | 8dp (uniform) | `keyboard_qwerty_panel.xml` | 81, 101, 121, 141 |
| `key_horizontal_margin` | 4dp | `styles.xml` | 8 |
| `key_text_size` | 22sp | `styles.xml` | 10 |
| `key_corner_radius` | 6dp | `keyboard_key_background.xml` | 7, 13 |

## KEYBOARD KEY COLORS

| Variable | Value | File |
|----------|-------|------|
| `keyboard_background` | #000000 | `colors.xml` |
| `regular_key_background` | #2D2D2F | `colors.xml` → `keyboard_key_background.xml` |
| `special_key_background` | #1A1A1A | `colors.xml` → `keyboard_key_accent_background.xml` |
| `key_text_color` | #FFFFFF | `colors.xml` |
| `key_pressed_color` | #6750A4 | `colors.xml` |

## DICTATION BAR

| Variable | Value | File |
|----------|-------|------|
| `dictation_bar_background` | #000000 | `view_keyboard.xml` |
| `dictation_bar_button_background` | #1A1A1A | `keyboard_button_background.xml` |
| `dictation_bar_icon_color` | #FFFFFF | icon xml files |
| `dictation_bar_button_size` | 48dp | `view_keyboard.xml` |
| `dictation_bar_button_spacing` | 6dp | `view_keyboard.xml` |
| `dictation_bar_min_height` | 64dp | `view_keyboard.xml` |
| `dictation_bar_padding_horizontal` | 8dp | `view_keyboard.xml` |
| `dictation_bar_padding_vertical` | 10dp | `view_keyboard.xml` |
| `dictation_bar_button_corner_radius` | 6dp | `keyboard_button_background.xml` |

---

## COLOR REFERENCE

| Color Name | Hex | Used For |
|------------|-----|----------|
| `keyboard_background` | #000000 | QWERTY panel background |
| `keyboard_key_background` | #2D2D2F | Regular character/number keys |
| `dictation_bar_button` | #1A1A1A | Dictation bar buttons + special keys |
| `keyboard_key_text` | #FFFFFF | Key text, icons |
| `primary` | #6750A4 | Pressed state |

---

## CHANGELOG

- **v1.18.27**: Dictation bar buttons #1A1A1A, special keys #1A1A1A
- **v1.18.26**: Fix dictation bar icons (white), number row 43dp, panel 324dp
- **v1.18.25**: Uniform row spacing (8dp), space bar row same height as character rows
