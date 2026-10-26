package com.appdevper.mediaplayer.airplay

/** iOS can't act as an AirPlay receiver for other devices. */
actual fun createAirPlayReceiver(): AirPlayReceiver? = null
