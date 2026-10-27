package com.appdevper.mediaplayer.data.upnp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Parses UPnP device description XML. Returns the root device and all embedded devices, flattened. */
object DeviceDescription {
    fun parse(xml: String, location: String): List<UpnpDevice> {
        val root = Xml.parse(xml)
        val base = root.childText("URLBase")?.takeIf { it.isNotEmpty() } ?: location
        val rootDevice = root.child("device") ?: return emptyList()
        val out = mutableListOf<UpnpDevice>()
        collect(rootDevice, base, location, out)
        return out
    }

    private fun collect(el: XmlElement, base: String, location: String, out: MutableList<UpnpDevice>) {
        val services = el.child("serviceList")?.children("service").orEmpty().map { s ->
            UpnpService(
                serviceType = s.childText("serviceType").orEmpty(),
                serviceId = s.childText("serviceId").orEmpty(),
                controlUrl = resolveUrl(base, s.childText("controlURL").orEmpty()),
                eventSubUrl = resolveUrl(base, s.childText("eventSubURL").orEmpty()),
                scpdUrl = resolveUrl(base, s.childText("SCPDURL").orEmpty()),
            )
        }
        val icon = el.child("iconList")?.children("icon").orEmpty()
            .filter { it.childText("mimetype")?.contains("png") == true || it.childText("mimetype")?.contains("jpeg") == true }
            .maxByOrNull { it.childText("width")?.toIntOrNull() ?: 0 }
            ?.childText("url")?.let { resolveUrl(base, it) }
        out += UpnpDevice(
            udn = el.childText("UDN").orEmpty(),
            friendlyName = el.childText("friendlyName").orEmpty().ifEmpty { "Unknown device" },
            deviceType = el.childText("deviceType").orEmpty(),
            manufacturer = el.childText("manufacturer").orEmpty(),
            modelName = el.childText("modelName").orEmpty(),
            location = location,
            iconUrl = icon,
            services = services,
        )
        el.child("deviceList")?.children("device")?.forEach { collect(it, base, location, out) }
    }
}

/**
 * UPnP control point: discovers devices via SSDP and keeps a live list of media servers
 * and renderers. Replaces Cling's `ControlPoint` + `Registry` from the old Android app.
 */
class ControlPoint(
    private val ssdp: SsdpEngine,
    private val http: HttpClient,
    private val scope: CoroutineScope,
) {
    private class Entry(val device: UpnpDevice, var expiresAt: TimeSource.Monotonic.ValueTimeMark)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val pendingLocations = HashSet<String>()

    private val _devices = MutableStateFlow<List<UpnpDevice>>(emptyList())
    val devices: StateFlow<List<UpnpDevice>> = _devices.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null

    // Addresses the user added (see addByAddress): asked again on every search and now and then, as
    // they may never be heard by multicast.
    private val addresses = MutableStateFlow<Set<String>>(emptySet())

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { ssdp.messages.collect { onMessage(it) } }
            launch {
                var ticks = 0
                while (isActive) {
                    delay(30_000)
                    if (++ticks % ADDRESS_RECHECK_TICKS == 0) addresses.value.forEach { launch { probe(it) } }
                    purgeExpired()
                }
            }
            refresh()
        }
    }

    /** Addresses to ask directly from now on (e.g. ones saved from earlier sessions). */
    fun remember(addresses: Collection<String>) {
        this.addresses.update { it + addresses }
        if (job != null) addresses.forEach { scope.launch { probe(it) } }
    }

    /**
     * Finds the media servers and renderers at [address] without multicast (an iPhone without
     * Apple's multicast entitlement, or a network that blocks it). [address] is a host or host:port,
     * asked with a unicast M-SEARCH on port 1900 (which UPnP devices answer as well) and on this
     * app's own server ports; or a device description URL, fetched as is. Returns what answered
     * within [timeoutMs], and remembers [address] when something did.
     */
    suspend fun addByAddress(address: String, timeoutMs: Long = 4_000): List<UpnpDevice> {
        val target = ServerAddress.parse(address) ?: return emptyList()
        start() // replies to the unicast search come in with the other SSDP messages
        val found = withTimeoutOrNull(timeoutMs) {
            probe(target.text)
            // Unicast search replies arrive like any other (onMessage); wait for them.
            devices.first { list -> list.any { target.matches(it.location) } }
        }
        val there = (found ?: devices.value).filter { target.matches(it.location) }
        if (there.isNotEmpty()) addresses.update { it + target.text }
        return there
    }

    private suspend fun probe(address: String) {
        val target = ServerAddress.parse(address) ?: return
        coroutineScope {
            if (target.host != null && ssdp.ensureStarted()) {
                val search = SsdpMessage.build(
                    "M-SEARCH * HTTP/1.1",
                    listOf(
                        "HOST" to "${target.host}:${SsdpEngine.SSDP_PORT}",
                        "MAN" to "\"ssdp:discover\"",
                        "MX" to "1",
                        "ST" to "ssdp:all",
                        "USER-AGENT" to SsdpEngine.USER_AGENT,
                    ),
                )
                launch { repeat(2) { ssdp.sendTo(search, target.host, SsdpEngine.SSDP_PORT); delay(300) } }
            }
            target.descriptionUrls.forEach { url ->
                launch { withTimeoutOrNull(PROBE_HTTP_TIMEOUT_MS) { fetchDescription(url, DIRECT_TTL_SECONDS) } }
            }
        }
    }

    /** Re-checks network, then sends M-SEARCH. Keeps already known devices. */
    fun refresh() {
        scope.launch {
            _searching.value = true
            try {
                if (ssdp.ensureStarted()) {
                    addresses.value.forEach { launch { probe(it) } }
                    ssdp.search(UpnpTypes.MEDIA_SERVER)
                    ssdp.search(UpnpTypes.MEDIA_RENDERER)
                    ssdp.search("ssdp:all")
                }
                delay(2_000)
            } finally {
                _searching.value = false
            }
        }
    }

    /** Clears the registry then searches again (used by pull-to-refresh / Wi‑Fi changes). */
    fun reset() {
        scope.launch {
            mutex.withLock {
                entries.clear()
                publish()
            }
            refresh()
        }
    }

    private suspend fun onMessage(msg: SsdpMessage) {
        if (msg.isSearch) return
        val udn = msg.udn ?: return
        if (msg.isNotify && msg.header("NTS")?.contains("byebye") == true) {
            mutex.withLock {
                if (entries.keys.removeAll { it == udn || it.startsWith("$udn/") }) publish()
            }
            return
        }
        val location = msg.header("LOCATION") ?: return
        val ttl = msg.maxAgeSeconds.coerceIn(60, 7200)
        val known = mutex.withLock {
            var hit = false
            for ((k, e) in entries) {
                if (k == udn || e.device.location == location) {
                    e.expiresAt = TimeSource.Monotonic.markNow() + ttl.seconds
                    hit = true
                }
            }
            if (!hit && !pendingLocations.add(location)) hit = true
            hit
        }
        if (known) return
        scope.launch { fetchPending(location, ttl) }
    }

    private suspend fun fetchDescription(location: String, ttl: Int) {
        mutex.withLock { if (!pendingLocations.add(location)) return }
        fetchPending(location, ttl)
    }

    private suspend fun fetchPending(location: String, ttl: Int) {
        try {
            val resp = http.get(location)
            if (!resp.status.isSuccess()) return
            val devices = DeviceDescription.parse(resp.bodyAsText(), location)
                .filter { it.isMediaServer || it.isRenderer }
            mutex.withLock {
                val expires = TimeSource.Monotonic.markNow() + ttl.seconds
                devices.forEach { d -> entries[d.udn] = Entry(d, expires) }
                if (devices.isNotEmpty()) publish()
            }
        } catch (_: Throwable) {
            // unreachable / malformed device — ignore, it will be retried on the next announcement
        } finally {
            mutex.withLock { pendingLocations.remove(location) }
        }
    }

    private suspend fun purgeExpired() = mutex.withLock {
        if (entries.values.removeAll { it.expiresAt.hasPassedNow() }) publish()
    }

    private fun publish() {
        _devices.update { entries.values.map { it.device }.sortedBy { it.friendlyName.lowercase() } }
    }

    fun device(udn: String): UpnpDevice? = _devices.value.firstOrNull { it.udn == udn }

    private companion object {
        const val ADDRESS_RECHECK_TICKS = 10 // × 30 s
        const val PROBE_HTTP_TIMEOUT_MS = 3_000L
        const val DIRECT_TTL_SECONDS = 1800
    }
}

/**
 * What the user typed to add a server: a description URL (http/https), or a host with an optional
 * port. A bare host is also tried on this app's own server ports, as its server answers there.
 */
internal class ServerAddress private constructor(val text: String, val host: String?, val descriptionUrls: List<String>) {

    /** A device found at [location] is this address's. */
    fun matches(location: String): Boolean =
        if (host == null) location == text else hostOf(location).equals(host, ignoreCase = true)

    companion object {
        private val OWN_SERVER_PORTS = listOf(8191, 8192, 8193, 8194)

        fun parse(input: String): ServerAddress? {
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
                return hostOf(text).takeIf { it.isNotEmpty() }?.let { ServerAddress(text, null, listOf(text)) }
            }
            if ('/' in text || '@' in text) return null
            val host = text.substringBefore(':')
            val port = text.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: return null }
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            val ports = port?.let(::listOf) ?: OWN_SERVER_PORTS
            return ServerAddress(text, host, ports.map { "http://$host:$it/description.xml" })
        }

        /** The host part of an http(s) URL. */
        fun hostOf(url: String): String =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
    }
}
