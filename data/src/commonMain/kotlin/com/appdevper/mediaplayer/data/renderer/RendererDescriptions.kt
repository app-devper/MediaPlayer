package com.appdevper.mediaplayer.data.renderer

import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.host.Scpd.action
import com.appdevper.mediaplayer.data.upnp.host.Scpd.arg
import com.appdevper.mediaplayer.data.upnp.host.Scpd.document
import com.appdevper.mediaplayer.data.upnp.host.Scpd.variable

/** Static UPnP description documents served by the built-in media renderer. */
internal object RendererDescriptions {

    fun device(udn: String, friendlyName: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>${UpnpTypes.MEDIA_RENDERER}</deviceType>
    <friendlyName>${Xml.escape(friendlyName)}</friendlyName>
    <manufacturer>appdevper</manufacturer>
    <manufacturerURL>https://github.com/app-devper</manufacturerURL>
    <modelDescription>MediaPlayer DLNA renderer</modelDescription>
    <modelName>MediaPlayer</modelName>
    <modelNumber>2.0</modelNumber>
    <modelURL>https://github.com/app-devper/MediaPlayer</modelURL>
    <serialNumber>${Xml.escape(udn.removePrefix("uuid:"))}</serialNumber>
    <UDN>$udn</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>${UpnpTypes.AV_TRANSPORT}</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/avt.xml</SCPDURL>
        <controlURL>/avt/control</controlURL>
        <eventSubURL>/avt/event</eventSubURL>
      </service>
      <service>
        <serviceType>${UpnpTypes.RENDERING_CONTROL}</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/rcs.xml</SCPDURL>
        <controlURL>/rcs/control</controlURL>
        <eventSubURL>/rcs/event</eventSubURL>
      </service>
      <service>
        <serviceType>${UpnpTypes.CONNECTION_MANAGER}</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/cm.xml</SCPDURL>
        <controlURL>/cm/control</controlURL>
        <eventSubURL>/cm/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
""".trimStart()

    private val instanceId = arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")

    val avTransport: String = document(
        actions = action(
            "SetAVTransportURI", instanceId,
            arg("CurrentURI", "in", "AVTransportURI"),
            arg("CurrentURIMetaData", "in", "AVTransportURIMetaData"),
        ) +
            action(
                "GetMediaInfo", instanceId,
                arg("NrTracks", "out", "NumberOfTracks"),
                arg("MediaDuration", "out", "CurrentMediaDuration"),
                arg("CurrentURI", "out", "AVTransportURI"),
                arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"),
                arg("NextURI", "out", "NextAVTransportURI"),
                arg("NextURIMetaData", "out", "NextAVTransportURIMetaData"),
                arg("PlayMedium", "out", "PlaybackStorageMedium"),
                arg("RecordMedium", "out", "RecordStorageMedium"),
                arg("WriteStatus", "out", "RecordMediumWriteStatus"),
            ) +
            action(
                "GetTransportInfo", instanceId,
                arg("CurrentTransportState", "out", "TransportState"),
                arg("CurrentTransportStatus", "out", "TransportStatus"),
                arg("CurrentSpeed", "out", "TransportPlaySpeed"),
            ) +
            action(
                "GetPositionInfo", instanceId,
                arg("Track", "out", "CurrentTrack"),
                arg("TrackDuration", "out", "CurrentTrackDuration"),
                arg("TrackMetaData", "out", "CurrentTrackMetaData"),
                arg("TrackURI", "out", "CurrentTrackURI"),
                arg("RelTime", "out", "RelativeTimePosition"),
                arg("AbsTime", "out", "AbsoluteTimePosition"),
                arg("RelCount", "out", "RelativeCounterPosition"),
                arg("AbsCount", "out", "AbsoluteCounterPosition"),
            ) +
            action(
                "GetDeviceCapabilities", instanceId,
                arg("PlayMedia", "out", "PossiblePlaybackStorageMedia"),
                arg("RecMedia", "out", "PossibleRecordStorageMedia"),
                arg("RecQualityModes", "out", "PossibleRecordQualityModes"),
            ) +
            action(
                "GetTransportSettings", instanceId,
                arg("PlayMode", "out", "CurrentPlayMode"),
                arg("RecQualityMode", "out", "CurrentRecordQualityMode"),
            ) +
            action("GetCurrentTransportActions", instanceId, arg("Actions", "out", "CurrentTransportActions")) +
            action("Stop", instanceId) +
            action("Play", instanceId, arg("Speed", "in", "TransportPlaySpeed")) +
            action("Pause", instanceId) +
            action(
                "Seek", instanceId,
                arg("Unit", "in", "A_ARG_TYPE_SeekMode"),
                arg("Target", "in", "A_ARG_TYPE_SeekTarget"),
            ) +
            action("Next", instanceId) +
            action("Previous", instanceId) +
            action("SetPlayMode", instanceId, arg("NewPlayMode", "in", "CurrentPlayMode")),
        variables = variable(
            "TransportState", "string",
            allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT"),
        ) +
            variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")) +
            variable("TransportPlaySpeed", "string", allowed = listOf("1")) +
            variable("NumberOfTracks", "ui4") +
            variable("CurrentMediaDuration", "string") +
            variable("AVTransportURI", "string") +
            variable("AVTransportURIMetaData", "string") +
            variable("NextAVTransportURI", "string") +
            variable("NextAVTransportURIMetaData", "string") +
            variable("PlaybackStorageMedium", "string", allowed = listOf("NETWORK", "NONE")) +
            variable("RecordStorageMedium", "string", allowed = listOf("NOT_IMPLEMENTED")) +
            variable("RecordMediumWriteStatus", "string", allowed = listOf("NOT_IMPLEMENTED")) +
            variable("CurrentTrack", "ui4") +
            variable("CurrentTrackDuration", "string") +
            variable("CurrentTrackMetaData", "string") +
            variable("CurrentTrackURI", "string") +
            variable("RelativeTimePosition", "string") +
            variable("AbsoluteTimePosition", "string") +
            variable("RelativeCounterPosition", "i4") +
            variable("AbsoluteCounterPosition", "i4") +
            variable("PossiblePlaybackStorageMedia", "string") +
            variable("PossibleRecordStorageMedia", "string") +
            variable("PossibleRecordQualityModes", "string") +
            variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")) +
            variable("CurrentRecordQualityMode", "string", allowed = listOf("NOT_IMPLEMENTED")) +
            variable("CurrentTransportActions", "string") +
            variable("LastChange", "string", events = true) +
            variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME")) +
            variable("A_ARG_TYPE_SeekTarget", "string") +
            variable("A_ARG_TYPE_InstanceID", "ui4"),
    )

    private val channel = arg("Channel", "in", "A_ARG_TYPE_Channel")

    val renderingControl: String = document(
        actions = action("ListPresets", instanceId, arg("CurrentPresetNameList", "out", "PresetNameList")) +
            action("SelectPreset", instanceId, arg("PresetName", "in", "A_ARG_TYPE_PresetName")) +
            action("GetVolume", instanceId, channel, arg("CurrentVolume", "out", "Volume")) +
            action("SetVolume", instanceId, channel, arg("DesiredVolume", "in", "Volume")) +
            action("GetMute", instanceId, channel, arg("CurrentMute", "out", "Mute")) +
            action("SetMute", instanceId, channel, arg("DesiredMute", "in", "Mute")),
        variables = variable("PresetNameList", "string") +
            variable("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")) +
            variable("Volume", "ui2") +
            variable("Mute", "boolean") +
            variable("LastChange", "string", events = true) +
            variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")) +
            variable("A_ARG_TYPE_InstanceID", "ui4"),
    )
}
