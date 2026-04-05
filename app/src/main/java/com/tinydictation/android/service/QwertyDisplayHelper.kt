package com.tinydictation.android.service

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tinydictation.android.R

/**
 * Helper class for updating the QWERTY panel display state.
 *
 * Handles all view-only updates for the QWERTY keyboard panel:
 * key label updates for numbers/letters/symbols mode, shift/caps display,
 * number row and long-press hint visibility.
 *
 * This class has no side effects — it only reads state from parameters
 * and writes to views in the provided qwertyPanel.
 */
internal class QwertyDisplayHelper(private val qwertyPanel: View) {

    /**
     * Updates all keyboard keys based on current mode (letters vs numbers/symbols).
     */
    fun updateKeyboardMode(isNumbersMode: Boolean, isShiftActive: Boolean, isCapsLock: Boolean, isGermanMode: Boolean = false) {
        val numbersRow1 = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )
        val numbersRow2 = mapOf(
            R.id.key_a to "@", R.id.key_s to "#", R.id.key_d to "$", R.id.key_f to "%",
            R.id.key_g to "&", R.id.key_h to "-", R.id.key_j to "+", R.id.key_k to "(",
            R.id.key_l to ")"
        )
        val numbersRow3 = mapOf(
            R.id.key_z to "!", R.id.key_x to "\"", R.id.key_c to "'", R.id.key_v to ":",
            R.id.key_b to ";", R.id.key_n to "/", R.id.key_m to "?"
        )

        val lettersRow1 = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p"
        )
        val lettersRow2 = mapOf(
            R.id.key_a to "a", R.id.key_s to "s", R.id.key_d to "d", R.id.key_f to "f",
            R.id.key_g to "g", R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k",
            R.id.key_l to "l"
        )
        val lettersRow3 = mapOf(
            R.id.key_z to "z", R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v",
            R.id.key_b to "b", R.id.key_n to "n", R.id.key_m to "m"
        )

        if (isNumbersMode) {
            for ((id, char) in numbersRow1 + numbersRow2 + numbersRow3) {
                qwertyPanel.findViewById<TextView>(id)?.text = char
            }
            qwertyPanel.findViewById<TextView>(R.id.key_numbers)?.text = "ABC"
            qwertyPanel.findViewById<TextView>(R.id.key_shift)?.text = "#+="
        } else {
            val useUppercase = isShiftActive || isCapsLock
            for ((id, char) in lettersRow1 + lettersRow2 + lettersRow3) {
                qwertyPanel.findViewById<TextView>(id)?.text =
                    if (useUppercase) char.uppercase() else char
            }
            qwertyPanel.findViewById<TextView>(R.id.key_numbers)?.text = "123"
            updateShiftKeyDisplay(isGermanMode = isGermanMode, isCapsLock = isCapsLock, isShiftActive = isShiftActive)
        }
    }

    /**
     * Updates the shift key display based on current state.
     * Shows German mode indicator (DE) when active.
     */
    fun updateShiftKeyDisplay(isGermanMode: Boolean, isCapsLock: Boolean, isShiftActive: Boolean) {
        val shiftButton = qwertyPanel.findViewById<TextView>(R.id.key_shift)
        shiftButton?.text = when {
            isGermanMode && isCapsLock -> "DE⇪"
            isGermanMode && isShiftActive -> "DE⇧"
            isGermanMode -> "DE"
            isCapsLock -> "⇪"
            isShiftActive -> "⇧"
            else -> "⇧"
        }
        updateLetterKeyDisplays(isShiftActive, isCapsLock)
    }

    /**
     * Updates all letter keys to show uppercase or lowercase.
     */
    fun updateLetterKeyDisplays(isShiftActive: Boolean, isCapsLock: Boolean) {
        val letterKeys = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g", R.id.key_h to "h",
            R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l", R.id.key_z to "z",
            R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )
        val useUppercase = isShiftActive || isCapsLock
        for ((id, char) in letterKeys) {
            qwertyPanel.findViewById<TextView>(id)?.text =
                if (useUppercase) char.uppercase() else char
        }
    }

    /**
     * Updates symbols display for secondary symbols (when shift is pressed in numbers mode).
     */
    fun updateSymbolsDisplay(isShiftActive: Boolean) {
        val primaryRow1 = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )
        val primaryRow2 = mapOf(
            R.id.key_a to "@", R.id.key_s to "#", R.id.key_d to "$", R.id.key_f to "%",
            R.id.key_g to "&", R.id.key_h to "-", R.id.key_j to "+", R.id.key_k to "(",
            R.id.key_l to ")"
        )
        val primaryRow3 = mapOf(
            R.id.key_z to "!", R.id.key_x to "\"", R.id.key_c to "'", R.id.key_v to ":",
            R.id.key_b to ";", R.id.key_n to "/", R.id.key_m to "?"
        )

        val secondaryRow1 = mapOf(
            R.id.key_q to "~", R.id.key_w to "`", R.id.key_e to "|", R.id.key_r to "•",
            R.id.key_t to "√", R.id.key_y to "π", R.id.key_u to "÷", R.id.key_i to "×",
            R.id.key_o to "§", R.id.key_p to "∆"
        )
        val secondaryRow2 = mapOf(
            R.id.key_a to "€", R.id.key_s to "£", R.id.key_d to "¥", R.id.key_f to "^",
            R.id.key_g to "°", R.id.key_h to "=", R.id.key_j to "{", R.id.key_k to "}",
            R.id.key_l to "\\"
        )
        val secondaryRow3 = mapOf(
            R.id.key_z to "©", R.id.key_x to "®", R.id.key_c to "™", R.id.key_v to "✓",
            R.id.key_b to "[", R.id.key_n to "]", R.id.key_m to "_"
        )

        if (isShiftActive) {
            for ((id, char) in secondaryRow1 + secondaryRow2 + secondaryRow3) {
                qwertyPanel.findViewById<TextView>(id)?.text = char
            }
            qwertyPanel.findViewById<TextView>(R.id.key_shift)?.text = "123"
        } else {
            for ((id, char) in primaryRow1 + primaryRow2 + primaryRow3) {
                qwertyPanel.findViewById<TextView>(id)?.text = char
            }
            qwertyPanel.findViewById<TextView>(R.id.key_shift)?.text = "#+="
        }
    }

    /**
     * Updates long-press hint visibility on keys with umlaut long-press actions.
     * Hints are shown in letters mode and hidden in numbers/symbols mode.
     */
    fun updateLongPressHints(isNumbersMode: Boolean, isGermanMode: Boolean) {
        val hintVisibility = if (isNumbersMode) View.GONE else View.VISIBLE
        val umlauthintIds = listOf(R.id.hint_a, R.id.hint_o_umlaut, R.id.hint_u_umlaut)
        for (id in umlauthintIds) {
            qwertyPanel.findViewById<TextView>(id)?.visibility = hintVisibility
        }
        val aHint = qwertyPanel.findViewById<TextView>(R.id.hint_a)
        aHint?.text = if (isGermanMode) "ä" else "@"
    }

    /**
     * Updates number row visibility and number hints on top row keys.
     */
    fun updateNumberRowVisibility(showNumberRow: Boolean) {
        val numberRow = qwertyPanel.findViewById<LinearLayout>(R.id.numberRow)
        numberRow?.visibility = if (showNumberRow) View.VISIBLE else View.GONE

        val hintVisibility = if (showNumberRow) View.GONE else View.VISIBLE
        val hintIds = listOf(
            R.id.hint_q, R.id.hint_w, R.id.hint_e, R.id.hint_r, R.id.hint_t,
            R.id.hint_y, R.id.hint_u, R.id.hint_i, R.id.hint_o, R.id.hint_p
        )
        for (id in hintIds) {
            qwertyPanel.findViewById<TextView>(id)?.visibility = hintVisibility
        }
    }
}
