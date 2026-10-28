# Domain terms

The words the code and its reviews use for playback. Where a term names a type, the type is given.

**Playback session** (`PlaybackSession`): the app's one queue, its current item and where that item
plays. Everything that plays or controls playback goes through it: our screens, the mini player, the
media notification and lock screen, DLNA controllers and AirPlay senders. It sends each command to
the current target's transport, and moves the queue on when an item ends by itself.

**Target** (`PlaybackTarget`): where the current item plays. **Local** is this device, and
**Renderer** is a DLNA renderer such as a TV or a speaker.

**Transport** (`Transport`): what plays the current item for a target. It takes the commands play,
pause, stop and seek, and knows its last status. There are two transports:
- **Local player** (`LocalPlayer`) is this device's player, one for the app. It plays whether a
  player screen shows it or not, and holds the rules every platform shares: when an item counts as
  stopped, Play after the end or after an error, and pausing a video in the background.
- **Renderer transport** (`RendererTransport`) drives a DLNA renderer over the network and polls it.

**Playback engine** (`PlaybackEngine`): a platform media player as the local player drives it, with
no rules of its own. Android uses ExoPlayer (`ExoPlaybackEngine`), iOS uses AVPlayer
(`AvPlaybackEngine`), and desktop uses FFmpeg (`FfmpegPlaybackEngine`).

**Stopped**, not **paused**: an item loaded without playing, one that finished, or one a controller
stopped. It is reported as `STOPPED`, and it rewinds before it plays again.

**Ended**: an item played to its end by itself (`RendererStatus.ended`). This, and only this, moves
the queue on. Stopping an item or leaving the player screen does not.

**Receiver**: this device taking playback from another device. The DLNA receiver
(`CastReceiverSession`) takes "Play to" from other apps. The AirPlay receiver
(`AirPlayReceiverSession`) takes mirroring, video and audio. Both play through the playback session
on the local player.

**Sender**: the device casting to us. An AirPlay sender that plays its own video or music here
decides what comes next: our next and previous go to its queue (`SenderCommand`).
