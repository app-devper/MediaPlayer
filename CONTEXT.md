# Domain terms

The words the code and its reviews use for playback. Where a term names a type, the type is given.

**Playback session** (`PlaybackSession`): the app's one queue, its current item and where that item
plays. Everything that plays or controls playback goes through it: our screens, the mini player, the
media notification and lock screen, DLNA controllers and AirPlay senders. It sends each command to
the current target's transport, and moves the queue on when an item ends by itself.

**Target** (`PlaybackTarget`): where the current item plays. **Local** is this device, and
**Renderer** is a DLNA renderer such as a TV or a speaker.

**Transport** (`Transport`): what plays the current item for a target. It takes the commands play,
pause, stop, seek, volume and mute, and reports one playback (`TransportPlayback`: status, volume,
mute, error), which the session shows as the state's `playback`. There are two transports:
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

**Subtitle choice** (`SubtitleChoice`): what the local player shows under a video: **Off**, a
**File** next to the video (`Movie.th.srt`), or a **Track** inside the file. The local player owns it
(`LocalPlayer.subtitles`): it is kept when the same item loads again, starts off for another item,
and follows a track picked in the platform's own controls (AVKit's menu).

**Media server session** (`MediaServerSession`): this device's DLNA server as the app runs it. It
starts at launch when set to, and restarts once while running when what it publishes changes (its
name, what it shares, its folders), wherever the change comes from.

**Receiver**: this device taking playback from another device. The DLNA receiver
(`CastReceiverSession`) takes "Play to" from other apps. The AirPlay receiver
(`AirPlayReceiverSession`) takes mirroring, video and audio. Both play through the playback session
on the local player.

**Receiver link** (`ReceiverLink`): a receiver's line to the playback session. Its commands apply at
once. The receiver **owns** the player from its load until something else is picked or cast here,
or the item moves to a renderer; until then its other commands are refused (volume and mute apply
whenever this device plays). The session records which receiver cast the item (`castBy`).

**Sender**: the device casting to us. An AirPlay sender that plays its own video or music here
decides what comes next: our next and previous go to its queue (`SenderCommand`). The session reaches
it through `SenderControl`.

**Now playing** (`NowPlaying`): what plays now and where its controls go, for every screen and the
system's media controls: nothing, an item of our queue (here or on a renderer), or a sender's music.
It says whether play/pause applies (not for a photo) and whether the sender decides next and
previous.
