package app.devper.appplayer.airplay

/** iOS can't act as an AirPlay receiver for other devices. */
actual fun createAirPlayReceiver(): AirPlayReceiver? = null
