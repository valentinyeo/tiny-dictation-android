package com.tinydictation.android.transcription

fun acceptsDeepgramEvent(eventSocket: Any, currentSocket: Any?, closedByClient: Boolean): Boolean {
    return eventSocket === currentSocket && !closedByClient
}
