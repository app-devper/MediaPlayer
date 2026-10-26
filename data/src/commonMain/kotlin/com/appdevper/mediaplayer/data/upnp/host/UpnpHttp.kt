package com.appdevper.mediaplayer.data.upnp.host

import com.appdevper.mediaplayer.data.upnp.Soap
import com.appdevper.mediaplayer.data.upnp.Xml
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.utils.io.charsets.Charsets

/** A SOAP control request: the action name and its arguments. */
internal class SoapRequest(val action: String, val args: Map<String, String>)

/** Parses a SOAP control request, or null when the body isn't a SOAP envelope. */
internal suspend fun ApplicationCall.receiveSoap(): SoapRequest? {
    val body = runCatching { Xml.parse(receiveText()).find("Body")?.children?.firstOrNull() }.getOrNull() ?: return null
    // Action from `SOAPACTION: "urn:...:AVTransport:1#Play"`, falling back to the body element.
    val action = request.header("SOAPACTION")?.trim('"', ' ')?.substringAfter('#')?.takeIf { it.isNotEmpty() } ?: body.name
    return SoapRequest(action, body.children.associate { it.name to it.text.trim() })
}

internal suspend fun ApplicationCall.respondXml(xml: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(xml, ContentType.Text.Xml.withCharset(Charsets.UTF_8), status)
}

internal suspend fun ApplicationCall.respondSoap(serviceType: String, action: String, values: List<Pair<String, String>> = emptyList()) {
    respondXml(Soap.response(serviceType, action, values))
}

internal suspend fun ApplicationCall.respondSoapFault(code: Int, description: String) {
    respondXml(Soap.fault(code, description), HttpStatusCode.InternalServerError)
}

/** Starts a CIO server on the first free port of [ports] (0 = any). Returns the server and its port. */
internal suspend fun startHttpServer(ports: List<Int>, routes: Route.() -> Unit): Pair<EmbeddedServer<*, *>, Int> {
    var last: Throwable? = null
    for (port in ports) {
        try {
            val s = embeddedServer(CIO, port = port, host = "0.0.0.0") { routing { routes() } }
            s.startSuspend(wait = false)
            val actual = s.engine.resolvedConnectors().firstOrNull()?.port ?: port
            return s to actual
        } catch (e: Throwable) {
            last = e
        }
    }
    throw last ?: IllegalStateException("No free port")
}
