package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.host.Scpd

/** Static UPnP description documents served by the built-in media server. */
internal object Descriptions {

    fun device(udn: String, friendlyName: String, platform: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>${UpnpTypes.MEDIA_SERVER}</deviceType>
    <friendlyName>${Xml.escape(friendlyName)}</friendlyName>
    <manufacturer>appdevper</manufacturer>
    <manufacturerURL>https://github.com/app-devper</manufacturerURL>
    <modelDescription>MediaPlayer DLNA server</modelDescription>
    <modelName>MediaPlayer</modelName>
    <modelNumber>2.0</modelNumber>
    <modelURL>https://github.com/app-devper/MediaPlayer</modelURL>
    <serialNumber>${Xml.escape(udn.removePrefix("uuid:"))}</serialNumber>
    <UDN>$udn</UDN>
    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
    <presentationURL>/</presentationURL>
    <serviceList>
      <service>
        <serviceType>${UpnpTypes.CONTENT_DIRECTORY}</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/cds.xml</SCPDURL>
        <controlURL>/cds/control</controlURL>
        <eventSubURL>/cds/event</eventSubURL>
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

    val contentDirectory: String = Scpd.document(
        actions = Scpd.action("GetSearchCapabilities", Scpd.arg("SearchCaps", "out", "SearchCapabilities")) +
            Scpd.action("GetSortCapabilities", Scpd.arg("SortCaps", "out", "SortCapabilities")) +
            Scpd.action("GetSystemUpdateID", Scpd.arg("Id", "out", "SystemUpdateID")) +
            Scpd.action(
                "Browse",
                Scpd.arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"),
                Scpd.arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
                Scpd.arg("Filter", "in", "A_ARG_TYPE_Filter"),
                Scpd.arg("StartingIndex", "in", "A_ARG_TYPE_Index"),
                Scpd.arg("RequestedCount", "in", "A_ARG_TYPE_Count"),
                Scpd.arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
                Scpd.arg("Result", "out", "A_ARG_TYPE_Result"),
                Scpd.arg("NumberReturned", "out", "A_ARG_TYPE_Count"),
                Scpd.arg("TotalMatches", "out", "A_ARG_TYPE_Count"),
                Scpd.arg("UpdateID", "out", "A_ARG_TYPE_UpdateID"),
            ),
        variables = Scpd.variable("SearchCapabilities", "string") +
            Scpd.variable("SortCapabilities", "string") +
            Scpd.variable("SystemUpdateID", "ui4", events = true) +
            Scpd.variable("A_ARG_TYPE_ObjectID", "string") +
            Scpd.variable("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")) +
            Scpd.variable("A_ARG_TYPE_Filter", "string") +
            Scpd.variable("A_ARG_TYPE_Index", "ui4") +
            Scpd.variable("A_ARG_TYPE_Count", "ui4") +
            Scpd.variable("A_ARG_TYPE_SortCriteria", "string") +
            Scpd.variable("A_ARG_TYPE_Result", "string") +
            Scpd.variable("A_ARG_TYPE_UpdateID", "ui4"),
    )

    val connectionManager: String = Scpd.document(
        actions = Scpd.action("GetProtocolInfo", Scpd.arg("Source", "out", "SourceProtocolInfo"), Scpd.arg("Sink", "out", "SinkProtocolInfo")) +
            Scpd.action("GetCurrentConnectionIDs", Scpd.arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
            Scpd.action(
                "GetCurrentConnectionInfo",
                Scpd.arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"),
                Scpd.arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
                Scpd.arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"),
                Scpd.arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
                Scpd.arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"),
                Scpd.arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                Scpd.arg("Direction", "out", "A_ARG_TYPE_Direction"),
                Scpd.arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"),
            ),
        variables = Scpd.variable("SourceProtocolInfo", "string", events = true) +
            Scpd.variable("SinkProtocolInfo", "string", events = true) +
            Scpd.variable("CurrentConnectionIDs", "string", events = true) +
            Scpd.variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")) +
            Scpd.variable("A_ARG_TYPE_ConnectionManager", "string") +
            Scpd.variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")) +
            Scpd.variable("A_ARG_TYPE_ProtocolInfo", "string") +
            Scpd.variable("A_ARG_TYPE_ConnectionID", "i4") +
            Scpd.variable("A_ARG_TYPE_AVTransportID", "i4") +
            Scpd.variable("A_ARG_TYPE_RcsID", "i4"),
    )
}
