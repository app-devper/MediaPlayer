package com.appdevper.mediaplayer.domain.model

/**
 * What plays now and how it's controlled, wherever it plays: one answer for the player screen, the
 * mini player, the sidebar and the system's media controls. Play/pause, next and previous on
 * [com.appdevper.mediaplayer.domain.session.PlaybackSession] go where this says.
 */
sealed interface NowPlaying {
    /** Play/pause applies: true while playing (or about to), false paused or stopped. Null: no play/pause. */
    val playing: Boolean?

    /** Next and previous go to an AirPlay sender's queue, not ours (which hides shuffle and repeat). */
    val senderSkips: Boolean

    data object Nothing : NowPlaying {
        override val playing: Boolean? get() = null
        override val senderSkips: Boolean get() = false
    }

    /** [entry], the current item of our queue, playing on [target]. */
    data class Item(
        val entry: MediaEntry,
        val target: PlaybackTarget,
        override val playing: Boolean?,
        override val senderSkips: Boolean,
    ) : NowPlaying {
        /** A photo: shown, not played. */
        val isPhoto: Boolean get() = entry.kind == MediaKind.IMAGE
    }

    /** Music an AirPlay sender plays here (our player is paused meanwhile). */
    data class SenderAudio(
        /** The sender's name. */
        val sender: String?,
        val song: AirPlayNowPlaying?,
        /** It takes play/pause, next and previous. */
        val controllable: Boolean,
        /** As far as we know: the sender doesn't say when it's paused from its own screen. */
        val paused: Boolean,
    ) : NowPlaying {
        override val playing: Boolean? get() = if (controllable) !paused else null
        override val senderSkips: Boolean get() = controllable
    }
}
