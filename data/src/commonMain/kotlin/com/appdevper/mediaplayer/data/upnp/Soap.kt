package com.appdevper.mediaplayer.data.upnp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets

object Soap {
    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((k, v) in args) append('<').append(k).append('>').append(Xml.escape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** A control response from a local service. */
    fun response(serviceType: String, action: String, values: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:${action}Response xmlns:u=\"$serviceType\">")
        for ((k, v) in values) append("<$k>").append(Xml.escape(v)).append("</$k>")
        append("</u:${action}Response></s:Body></s:Envelope>")
    }

    fun fault(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>${Xml.escape(description)}</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"

    /** Returns the action-response element's children as name -> text. */
    fun parseResponse(xml: String): Map<String, String> {
        val body = Xml.parse(xml).find("Body") ?: throw UpnpException("Malformed SOAP response")
        body.child("Fault")?.let { f ->
            val err = f.find("UPnPError")
            throw UpnpException(
                err?.childText("errorDescription") ?: f.childText("faultstring") ?: "SOAP fault",
                err?.childText("errorCode")?.toIntOrNull(),
            )
        }
        val response = body.children.firstOrNull() ?: return emptyMap()
        return response.children.associate { it.name to it.text }
    }
}

class SoapClient(private val http: HttpClient) {

    suspend fun invoke(
        service: UpnpService,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> {
        val resp = http.post(service.controlUrl) {
            header("SOAPACTION", "\"${service.serviceType}#$action\"")
            setBody(TextContent(Soap.envelope(service.serviceType, action, args), ContentType.Text.Xml.withCharset(Charsets.UTF_8)))
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            // SOAP faults come back as HTTP 500 with a Fault body.
            runCatching { Soap.parseResponse(text) }.exceptionOrNull()?.let { throw it }
            throw UpnpException("$action failed: HTTP ${resp.status.value}")
        }
        return Soap.parseResponse(text)
    }
}

/** ContentDirectory client (replaces Cling's `Browse` callback). */
class ContentDirectoryClient(private val soap: SoapClient) {

    suspend fun browseChildren(device: UpnpDevice, objectId: String): BrowseResult {
        val service = device.service(UpnpTypes.CONTENT_DIRECTORY) ?: throw UpnpException("Not a media server")
        val all = mutableListOf<DidlObject>()
        var total: Int
        do {
            val r = soap.invoke(
                service, "Browse",
                listOf(
                    "ObjectID" to objectId,
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "*",
                    "StartingIndex" to all.size.toString(),
                    "RequestedCount" to PAGE.toString(),
                    "SortCriteria" to "",
                ),
            )
            val page = Didl.parse(r["Result"].orEmpty())
            all += page
            val returned = r["NumberReturned"]?.toIntOrNull() ?: page.size
            total = r["TotalMatches"]?.toIntOrNull()?.takeIf { it > 0 } ?: all.size
        } while (returned > 0 && page.isNotEmpty() && all.size < total && all.size < MAX_ITEMS)
        return BrowseResult(all, total)
    }

    private companion object {
        const val PAGE = 200
        const val MAX_ITEMS = 5000
    }
}

data class TransportStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
)

/** AVTransport + RenderingControl client for a DLNA renderer (replaces old `UpnpPlayer`). */
class RendererClient(private val soap: SoapClient, val device: UpnpDevice) {
    private val avt = device.service(UpnpTypes.AV_TRANSPORT) ?: throw UpnpException("Not a renderer")
    private val rc = device.service(UpnpTypes.RENDERING_CONTROL)
    private val instance = "InstanceID" to "0"

    suspend fun load(item: DidlObject, autoPlay: Boolean = true) {
        val uri = item.url ?: throw UpnpException("Item has no playable resource")
        runCatching { stop() }
        soap.invoke(
            avt, "SetAVTransportURI",
            listOf(instance, "CurrentURI" to uri, "CurrentURIMetaData" to Didl.generate(listOf(item), subtitleResources = false)),
        )
        if (autoPlay) play()
    }

    suspend fun play() {
        soap.invoke(avt, "Play", listOf(instance, "Speed" to "1"))
    }

    suspend fun pause() {
        soap.invoke(avt, "Pause", listOf(instance))
    }

    suspend fun stop() {
        soap.invoke(avt, "Stop", listOf(instance))
    }

    suspend fun seek(positionMs: Long) {
        soap.invoke(avt, "Seek", listOf(instance, "Unit" to "REL_TIME", "Target" to formatUpnpTime(positionMs)))
    }

    suspend fun status(): TransportStatus {
        val t = soap.invoke(avt, "GetTransportInfo", listOf(instance))
        val p = soap.invoke(avt, "GetPositionInfo", listOf(instance))
        return TransportStatus(
            state = t["CurrentTransportState"].orEmpty(),
            positionMs = p["RelTime"]?.let(::parseUpnpTime) ?: 0,
            durationMs = p["TrackDuration"]?.let(::parseUpnpTime) ?: 0,
        )
    }

    val hasVolume: Boolean get() = rc != null

    suspend fun volume(): Int? {
        val s = rc ?: return null
        return soap.invoke(s, "GetVolume", listOf(instance, "Channel" to "Master"))["CurrentVolume"]?.toIntOrNull()
    }

    suspend fun setVolume(value: Int) {
        val s = rc ?: return
        soap.invoke(s, "SetVolume", listOf(instance, "Channel" to "Master", "DesiredVolume" to value.coerceIn(0, 100).toString()))
    }
}
