package com.tinydictation.android.service

import android.view.inputmethod.EditorInfo

object EditorPrivacy {

    fun allowsCloudText(inputType: Int, imeOptions: Int): Boolean {
        return true
    }
}
