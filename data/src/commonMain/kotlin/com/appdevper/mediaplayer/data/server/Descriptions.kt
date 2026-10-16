package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml

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

    private fun arg(name: String, dir: String, variable: String) =
        "<argument><name>$name</name><direction>$dir</direction><relatedStateVariable>$variable</relatedStateVariable></argument>"

    private fun action(name: String, vararg args: String) =
        "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"

    private fun variable(name: String, type: String, events: Boolean = false, allowed: List<String> = emptyList()) = buildString {
        append("<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>")
        if (allowed.isNotEmpty()) append("<allowedValueList>${allowed.joinToString("") { "<allowedValue>$it</allowedValue>" }}</allowedValueList>")
        append("</stateVariable>")
    }

    private fun scpd(actions: String, variables: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actions</actionList><serviceStateTable>$variables</serviceStateTable></scpd>"

    val contentDirectory: String = scpd(
        actions = action("GetSearchCapabilities", arg("SearchCaps", "out", "SearchCapabilities")) +
            action("GetSortCapabilities", arg("SortCaps", "out", "SortCapabilities")) +
            action("GetSystemUpdateID", arg("Id", "out", "SystemUpdateID")) +
            action(
                "Browse",
                arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"),
                arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
                arg("Filter", "in", "A_ARG_TYPE_Filter"),
                arg("StartingIndex", "in", "A_ARG_TYPE_Index"),
                arg("RequestedCount", "in", "A_ARG_TYPE_Count"),
                arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
                arg("Result", "out", "A_ARG_TYPE_Result"),
                arg("NumberReturned", "out", "A_ARG_TYPE_Count"),
                arg("TotalMatches", "out", "A_ARG_TYPE_Count"),
                arg("UpdateID", "out", "A_ARG_TYPE_UpdateID"),
            ),
        variables = variable("SearchCapabilities", "string") +
            variable("SortCapabilities", "string") +
            variable("SystemUpdateID", "ui4", events = true) +
            variable("A_ARG_TYPE_ObjectID", "string") +
            variable("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")) +
            variable("A_ARG_TYPE_Filter", "string") +
            variable("A_ARG_TYPE_Index", "ui4") +
            variable("A_ARG_TYPE_Count", "ui4") +
            variable("A_ARG_TYPE_SortCriteria", "string") +
            variable("A_ARG_TYPE_Result", "string") +
            variable("A_ARG_TYPE_UpdateID", "ui4"),
    )

    val connectionManager: String = scpd(
        actions = action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo"), arg("Sink", "out", "SinkProtocolInfo")) +
            action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
            action(
                "GetCurrentConnectionInfo",
                arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"),
                arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
                arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"),
                arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
                arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"),
                arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                arg("Direction", "out", "A_ARG_TYPE_Direction"),
                arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"),
            ),
        variables = variable("SourceProtocolInfo", "string", events = true) +
            variable("SinkProtocolInfo", "string", events = true) +
            variable("CurrentConnectionIDs", "string", events = true) +
            variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")) +
            variable("A_ARG_TYPE_ConnectionManager", "string") +
            variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")) +
            variable("A_ARG_TYPE_ProtocolInfo", "string") +
            variable("A_ARG_TYPE_ConnectionID", "i4") +
            variable("A_ARG_TYPE_AVTransportID", "i4") +
            variable("A_ARG_TYPE_RcsID", "i4"),
    )

    fun soapResponse(serviceType: String, action: String, values: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:${action}Response xmlns:u=\"$serviceType\">")
        for ((k, v) in values) append("<$k>").append(Xml.escape(v)).append("</$k>")
        append("</u:${action}Response></s:Body></s:Envelope>")
    }
}
