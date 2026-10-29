package com.appdevper.mediaplayer.data.upnp.host

import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** What a SOAP action answers: its out arguments, or a UPnP fault. */
internal sealed interface SoapReply {
    data class Ok(val values: List<Pair<String, String>> = emptyList()) : SoapReply
    data class Fault(val code: Int, val description: String) : SoapReply

    companion object {
        val invalidAction = Fault(401, "Invalid Action")
        val invalidArgs = Fault(402, "Invalid Args")
    }
}

/**
 * One service of a [LocalDevice]: served at `/<path>.xml` (its SCPD), `/<path>/control` and
 * `/<path>/event`.
 */
internal class LocalService(
    val type: String,
    /** The part after `urn:upnp-org:serviceId:`. */
    val id: String,
    val path: String,
    val scpd: String,
    /** The `<e:propertyset>` of its evented state now (see [GenaPublisher.properties]). */
    val events: () -> String,
    val control: suspend (SoapRequest) -> SoapReply,
)

/** A UPnP root device this app runs: the server or the renderer. */
internal class DeviceSpec(
    val type: String,
    val name: String,
    val udn: String,
    val description: String,
    /** `DMS-1.50`, `DMR-1.50`. */
    val dlnaDoc: String,
    val services: List<LocalService>,
    /** `/` serves a page about the device. */
    val presentation: Boolean = false,
)

/**
 * Runs a [DeviceSpec] on the LAN: its HTTP server on the first free port of [ports], the
 * description, SCPD, control and eventing of each service, and its SSDP announcements. Everything
 * else the device serves goes in the `routes` passed to [start].
 */
internal class LocalDevice(
    private val ssdp: SsdpEngine,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val ports: List<Int>,
) {
    private var server: EmbeddedServer<*, *>? = null
    private var advertiser: SsdpAdvertiser? = null
    private var events: Map<String, GenaPublisher> = emptyMap()

    /** `http://<ip>:<port>` while running. */
    var baseUrl: String? = null
        private set

    val location: String? get() = baseUrl?.let { "$it/description.xml" }

    /**
     * Starts [spec]; throws when there's no network or no free port. [serving] gets the base URL once
     * HTTP is up and before the device is announced. Not thread-safe: callers hold a lock.
     */
    suspend fun start(spec: DeviceSpec, serving: (baseUrl: String) -> Unit = {}, routes: Route.() -> Unit = {}) {
        stop(byeBye = true)
        if (!ssdp.ensureStarted()) throw IllegalStateException("Not connected to a local network")
        val ip = ssdp.localIp ?: throw IllegalStateException("No LAN address")
        events = spec.services.associate { it.id to GenaPublisher(http, scope, it.events) }
        val description = description(spec)
        val (engine, port) = startHttpServer(ports) {
            get("/description.xml") { call.respondXml(description) }
            for (service in spec.services) serviceRoutes(service, events.getValue(service.id))
            routes()
        }
        server = engine
        baseUrl = "http://$ip:$port"
        serving(baseUrl!!)
        advertiser = SsdpAdvertiser(ssdp, scope, spec.udn, listOf(spec.type) + spec.services.map { it.type }, location!!)
            .also { it.start() }
    }

    /** Lets the subscribers of [serviceId] know its evented state changed. */
    fun notifyChanged(serviceId: String) {
        events[serviceId]?.notifyChanged()
    }

    suspend fun stop(byeBye: Boolean) {
        advertiser?.stop(byeBye)
        advertiser = null
        events.values.forEach { it.clear() }
        events = emptyMap()
        server?.let { s -> withContext(Dispatchers.IO) { runCatching { s.stop(200, 1_000) } } }
        server = null
        baseUrl = null
    }

    private fun Route.serviceRoutes(service: LocalService, gena: GenaPublisher) {
        get("/${service.path}.xml") { call.respondXml(service.scpd) }
        post("/${service.path}/control") {
            val request = call.receiveSoap() ?: return@post call.respondSoapFault(402, "Invalid Args")
            when (val reply = service.control(request)) {
                is SoapReply.Ok -> call.respondSoap(service.type, request.action, reply.values)
                is SoapReply.Fault -> call.respondSoapFault(reply.code, reply.description)
            }
        }
        route("/${service.path}/event", HttpMethod("SUBSCRIBE")) { handle { gena.subscribe(call) } }
        route("/${service.path}/event", HttpMethod("UNSUBSCRIBE")) { handle { gena.unsubscribe(call) } }
    }

    companion object {
        /** Where this app's server listens first; a host added by address is also asked on these. */
        val SERVER_PORTS = listOf(8191, 8192, 8193, 8194)
        val RENDERER_PORTS = listOf(8201, 8202, 8203, 8204)

        fun description(spec: DeviceSpec): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">\n")
            append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n  <device>\n")
            append("    <deviceType>${spec.type}</deviceType>\n")
            append("    <friendlyName>${Xml.escape(spec.name)}</friendlyName>\n")
            append("    <manufacturer>appdevper</manufacturer>\n")
            append("    <manufacturerURL>https://github.com/app-devper</manufacturerURL>\n")
            append("    <modelDescription>${Xml.escape(spec.description)}</modelDescription>\n")
            append("    <modelName>MediaPlayer</modelName>\n")
            append("    <modelNumber>2.0</modelNumber>\n")
            append("    <modelURL>https://github.com/app-devper/MediaPlayer</modelURL>\n")
            append("    <serialNumber>${Xml.escape(spec.udn.removePrefix("uuid:"))}</serialNumber>\n")
            append("    <UDN>${Xml.escape(spec.udn)}</UDN>\n")
            append("    <dlna:X_DLNADOC>${spec.dlnaDoc}</dlna:X_DLNADOC>\n")
            if (spec.presentation) append("    <presentationURL>/</presentationURL>\n")
            append("    <serviceList>\n")
            for (s in spec.services) {
                append("      <service>\n")
                append("        <serviceType>${s.type}</serviceType>\n")
                append("        <serviceId>urn:upnp-org:serviceId:${s.id}</serviceId>\n")
                append("        <SCPDURL>/${s.path}.xml</SCPDURL>\n")
                append("        <controlURL>/${s.path}/control</controlURL>\n")
                append("        <eventSubURL>/${s.path}/event</eventSubURL>\n")
                append("      </service>\n")
            }
            append("    </serviceList>\n  </device>\n</root>\n")
        }

        /** A random (version 4) UUID, for UDNs and subscription ids. */
        fun randomUuid(): String {
            val b = Random.nextBytes(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}

/**
 * ConnectionManager, as both devices have it: one connection (ID 0) that's always there, playing
 * out for a server (it has a source) and in for a renderer (it has a sink).
 */
internal object ConnectionManager {
    fun service(source: String, sink: String): LocalService {
        val renderer = sink.isNotEmpty()
        return LocalService(
            type = UpnpTypes.CONNECTION_MANAGER,
            id = "ConnectionManager",
            path = "cm",
            scpd = scpd,
            events = { GenaPublisher.properties(listOf("SourceProtocolInfo" to source, "SinkProtocolInfo" to sink, "CurrentConnectionIDs" to "0")) },
            control = { request ->
                when (request.action) {
                    "GetProtocolInfo" -> SoapReply.Ok(listOf("Source" to source, "Sink" to sink))
                    "GetCurrentConnectionIDs" -> SoapReply.Ok(listOf("ConnectionIDs" to "0"))
                    "GetCurrentConnectionInfo" -> SoapReply.Ok(
                        listOf(
                            "RcsID" to if (renderer) "0" else "-1",
                            "AVTransportID" to if (renderer) "0" else "-1",
                            "ProtocolInfo" to "",
                            "PeerConnectionManager" to "",
                            "PeerConnectionID" to "-1",
                            "Direction" to if (renderer) "Input" else "Output",
                            "Status" to "OK",
                        ),
                    )
                    else -> SoapReply.invalidAction
                }
            },
        )
    }

    private val scpd: String = Scpd.document(
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
