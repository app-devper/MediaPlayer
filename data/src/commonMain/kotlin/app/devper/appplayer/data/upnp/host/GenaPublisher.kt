package app.devper.appplayer.data.upnp.host

import app.devper.appplayer.data.upnp.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * GENA eventing for one local service: SUBSCRIBE / renew / UNSUBSCRIBE, an initial event for each
 * new subscriber, and NOTIFY to everyone on [notifyChanged].
 */
internal class GenaPublisher(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    /** The `<e:propertyset>` body describing the service's current evented state. */
    private val propertySet: () -> String,
) {
    private class Subscription(val sid: String, val callback: String, var expiresAt: TimeSource.Monotonic.ValueTimeMark) {
        var seq = 0L
    }

    private val mutex = Mutex()
    private val subscriptions = LinkedHashMap<String, Subscription>()

    // Keeps SEQ order per subscriber: one NOTIFY round at a time.
    private val sendMutex = Mutex()

    suspend fun subscribe(call: ApplicationCall) {
        val timeout = parseTimeout(call.request.header("TIMEOUT"))
        val renewSid = call.request.header("SID")
        if (renewSid != null) {
            val renewed = mutex.withLock {
                subscriptions[renewSid]?.also { it.expiresAt = TimeSource.Monotonic.markNow() + timeout.seconds }
            }
            return if (renewed == null) call.respond(HttpStatusCode.PreconditionFailed) else call.respondSubscribed(renewSid, timeout)
        }
        val callback = parseCallback(call.request.header("CALLBACK"), call.request.origin.remoteAddress)
        if (call.request.header("NT") != "upnp:event" || callback == null) return call.respond(HttpStatusCode.PreconditionFailed)

        val sub = Subscription("uuid:${LocalDevice.randomUuid()}", callback, TimeSource.Monotonic.markNow() + timeout.seconds)
        mutex.withLock {
            pruneLocked()
            if (subscriptions.size >= MAX_SUBSCRIPTIONS) subscriptions.remove(subscriptions.keys.first())
            subscriptions[sub.sid] = sub
        }
        call.respondSubscribed(sub.sid, timeout)
        // The initial event must arrive after the SUBSCRIBE response.
        scope.launch {
            delay(100)
            sendMutex.withLock { send(sub, propertySet()) }
        }
    }

    suspend fun unsubscribe(call: ApplicationCall) {
        val removed = mutex.withLock { subscriptions.remove(call.request.header("SID")) }
        call.respond(if (removed == null) HttpStatusCode.PreconditionFailed else HttpStatusCode.OK, "")
    }

    fun notifyChanged() {
        scope.launch {
            sendMutex.withLock {
                val subs = mutex.withLock { pruneLocked(); subscriptions.values.toList() }
                if (subs.isEmpty()) return@withLock
                val body = propertySet()
                subs.forEach { send(it, body) }
            }
        }
    }

    suspend fun clear() = mutex.withLock { subscriptions.clear() }

    private suspend fun send(sub: Subscription, body: String) {
        val seq = sub.seq
        sub.seq = if (seq >= MAX_SEQ) 1 else seq + 1
        runCatching {
            http.request(sub.callback) {
                method = HttpMethod("NOTIFY")
                header("NT", "upnp:event")
                header("NTS", "upnp:propchange")
                header("SID", sub.sid)
                header("SEQ", seq.toString())
                setBody(TextContent(body, ContentType.Text.Xml.withCharset(Charsets.UTF_8)))
            }
        }
    }

    private fun pruneLocked() {
        subscriptions.values.removeAll { it.expiresAt.hasPassedNow() }
    }

    private suspend fun ApplicationCall.respondSubscribed(sid: String, timeout: Int) {
        response.header("SID", sid)
        response.header("TIMEOUT", "Second-$timeout")
        respond(HttpStatusCode.OK, "")
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 1800
        private const val MAX_SUBSCRIPTIONS = 32
        private const val MAX_SEQ = 4_294_967_295L

        /** `<e:propertyset>` carrying an AVTransport/RenderingControl `LastChange` event. */
        fun lastChange(eventXml: String): String = properties(listOf("LastChange" to eventXml))

        fun properties(values: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
            for ((k, v) in values) append("<e:property><$k>").append(Xml.escape(v)).append("</$k></e:property>")
            append("</e:propertyset>")
        }

        /** `Second-N` → N seconds, clamped; `infinite` and junk get the default. */
        fun parseTimeout(header: String?): Int =
            header?.trim()?.removePrefix("Second-")?.toIntOrNull()?.coerceIn(60, DEFAULT_TIMEOUT) ?: DEFAULT_TIMEOUT

        /**
         * First `http://` URL of `<url1><url2>…`. Only callbacks on the subscribing host are accepted,
         * so a LAN client can't make this device send requests to a third party.
         */
        fun parseCallback(header: String?, subscriberHost: String): String? =
            Regex("<([^>]+)>").findAll(header.orEmpty())
                .map { it.groupValues[1].trim() }
                .firstOrNull { url ->
                    runCatching { Url(url) }.getOrNull()?.let { it.protocol.name == "http" && it.host.trim('[', ']') == subscriberHost.trim('[', ']') } == true
                }
    }
}
