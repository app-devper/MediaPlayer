package com.appdevper.mediaplayer.data.upnp

import com.appdevper.mediaplayer.data.upnp.host.LocalDevice
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
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
    /** Where the addresses added by hand are kept across launches. */
    private val store: AddressStore = AddressStore.InMemory(),
    /** UDNs of devices this app runs itself (our own renderer, while it runs): never offered. */
    private val ownUdns: () -> Set<String> = { emptySet() },
    /** When announcements expire (tests pass virtual time). */
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    /** [direct]: found by asking its address ([addByAddress]), which keeps the location it was asked at. */
    private class Entry(val device: UpnpDevice, var expiresAt: ComparableTimeMark, val direct: Boolean = false)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val pendingLocations = HashSet<String>()

    // Every device found, ours too; what's offered leaves ours out.
    private val _devices = MutableStateFlow<List<UpnpDevice>>(emptyList())
    val devices: StateFlow<List<UpnpDevice>> = _devices.map(::offered).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun offered(list: List<UpnpDevice>): List<UpnpDevice> = ownUdns().let { own -> list.filter { it.udn !in own } }

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null

    // Addresses the user added (see addByAddress), each once whatever its letter case, kept in [store]:
    // asked again on every search and now and then, as they may never be heard by multicast.
    private val addresses = MutableStateFlow(store.load().map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase))

    /** Addresses the user added by hand, in the order added. */
    val addedAddresses: StateFlow<List<String>> = addresses.asStateFlow()

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

    /**
     * Stops asking [address] and drops the devices found there. One the network also announces comes
     * back with its next announcement.
     */
    suspend fun forget(address: String) {
        val text = address.trim()
        store.save(addresses.updateAndGet { list -> list.filterNot { it.equals(text, ignoreCase = true) } })
        val target = ServerAddress.parse(text) ?: return
        mutex.withLock {
            // A probe of it still under way mustn't put it back: its late answers are ignored a while.
            forgotten[target] = time.markNow() + FORGOTTEN_IGNORE_SECONDS.seconds
            if (entries.values.removeAll { target.matches(it.device.location) }) publish()
        }
        // Another saved address on the same host (e.g. host and host:port) still wants its devices.
        addresses.value.filter { other -> ServerAddress.parse(other)?.let { target.sameHost(it) } == true }
            .forEach { scope.launch { probe(it) } }
    }

    // Just-forgotten addresses, until when their late answers are ignored (under mutex).
    private val forgotten = HashMap<ServerAddress, ComparableTimeMark>()

    /** Asks [address] directly from now on, and keeps it (once, whatever its letter case). */
    private fun keep(address: String) {
        // In one update, so two searches of the same address at once keep it once.
        val before = addresses.getAndUpdate { list -> if (list.any { it.equals(address, ignoreCase = true) }) list else list + address }
        if (before.none { it.equals(address, ignoreCase = true) }) store.save(addresses.value)
    }

    /**
     * Finds the media servers and renderers at [address] without multicast (an iPhone without
     * Apple's multicast entitlement, or a network that blocks it). [address] is a host or host:port,
     * asked with a unicast M-SEARCH on port 1900 (which UPnP devices answer as well) and on this
     * app's own server ports; or a device description URL, fetched as is. Returns what answered
     * within [timeoutMs], not our own; an address that found something is kept ([addedAddresses]).
     */
    suspend fun addByAddress(address: String, timeoutMs: Long = 4_000): List<UpnpDevice> {
        val target = ServerAddress.parse(address) ?: return emptyList()
        // Added again right after being removed: its answers count again.
        mutex.withLock { forgotten.keys.removeAll { it.sameHost(target) } }
        start() // replies to the unicast search come in with the other SSDP messages
        val found = withTimeoutOrNull(timeoutMs) {
            probe(target.text)
            // Unicast search replies arrive like any other (onMessage); wait for them.
            _devices.first { list -> offered(list).any { target.matches(it.location) } }
        }
        val there = offered(found ?: _devices.value).filter { target.matches(it.location) }
        if (there.isNotEmpty()) keep(target.text)
        return there
    }

    private suspend fun probe(address: String) {
        val target = ServerAddress.parse(address) ?: return
        coroutineScope {
            val searchHost = target.searchHost
            if (searchHost != null && ssdp.ensureStarted()) {
                val search = SsdpMessage.build(
                    "M-SEARCH * HTTP/1.1",
                    listOf(
                        "HOST" to "$searchHost:${SsdpEngine.SSDP_PORT}",
                        "MAN" to "\"ssdp:discover\"",
                        "MX" to "1",
                        "ST" to "ssdp:all",
                        "USER-AGENT" to SsdpEngine.USER_AGENT,
                    ),
                )
                launch { repeat(2) { ssdp.sendTo(search, searchHost, SsdpEngine.SSDP_PORT); delay(300) } }
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
                val sameDevice = k == udn || k.startsWith("$udn/")
                if (e.device.location == location || sameDevice) {
                    e.expiresAt = time.markNow() + ttl.seconds
                    // Elsewhere: it moved (another port, a new address), so it's read again there, or
                    // commands would keep going to where it was. Not one found by its address, and a
                    // location read lately not again: a device announcing from two (Ethernet and
                    // Wi-Fi) would otherwise be read again on every announcement.
                    hit = hit || e.device.location == location || e.direct || readLately(location)
                }
            }
            if (!hit && !pendingLocations.add(location)) hit = true
            hit
        }
        if (known) return
        scope.launch { fetchPending(location, ttl) }
    }

    // When each location was last read (under mutex), so one isn't read again within REREAD_SECONDS.
    private val readAt = HashMap<String, ComparableTimeMark>()

    private fun readLately(location: String) = readAt[location]?.let { (it + REREAD_SECONDS.seconds).hasNotPassedNow() } == true

    private suspend fun fetchDescription(location: String, ttl: Int) {
        mutex.withLock { if (!pendingLocations.add(location)) return }
        fetchPending(location, ttl, direct = true)
    }

    private suspend fun fetchPending(location: String, ttl: Int, direct: Boolean = false) {
        mutex.withLock { readAt[location] = time.markNow() }
        try {
            val resp = http.get(location)
            if (!resp.status.isSuccess()) return
            val devices = DeviceDescription.parse(resp.bodyAsText(), location)
                .filter { it.isMediaServer || it.isRenderer }
            mutex.withLock {
                forgotten.values.removeAll { it.hasPassedNow() }
                if (forgotten.keys.any { it.matches(location) }) return
                val expires = time.markNow() + ttl.seconds
                // What was at another location under these UDNs moved here.
                devices.forEach { d -> entries[d.udn] = Entry(d, expires, direct) }
                if (devices.isNotEmpty()) publish()
            }
        } catch (_: Throwable) {
            // unreachable / malformed device — ignore, it will be retried on the next announcement
        } finally {
            // Probes cancel fetches (timeouts): the location must still be let go, or it's never fetched again.
            withContext(NonCancellable) { mutex.withLock { pendingLocations.remove(location) } }
        }
    }

    private suspend fun purgeExpired() = mutex.withLock {
        readAt.keys.removeAll { !readLately(it) }
        if (entries.values.removeAll { it.expiresAt.hasPassedNow() }) publish()
    }

    private fun publish() {
        _devices.update { entries.values.map { it.device }.sortedBy { it.friendlyName.lowercase() } }
    }

    fun device(udn: String): UpnpDevice? = offered(_devices.value).firstOrNull { it.udn == udn }

    private companion object {
        const val ADDRESS_RECHECK_TICKS = 10 // × 30 s
        const val PROBE_HTTP_TIMEOUT_MS = 3_000L
        const val DIRECT_TTL_SECONDS = 1800
        const val FORGOTTEN_IGNORE_SECONDS = 10
        const val REREAD_SECONDS = 600
    }
}

/**
 * What the user typed to add a server: a description URL (http/https), or a host with an optional
 * port. A bare host is also tried on this app's own server ports, as its server answers there.
 */
internal class ServerAddress private constructor(val text: String, val host: String?, val descriptionUrls: List<String>) {

    /**
     * Where a unicast M-SEARCH goes: IPv4 addresses only. A name isn't resolved for it (iOS sends to
     * literal addresses) and a device answers with its IP, which wouldn't match the name; names are
     * fetched over HTTP, where LOCATION keeps the name.
     */
    val searchHost: String? get() = host?.takeIf(::isIpv4)

    /** Both are on the same host (case-insensitive), whatever their ports. */
    fun sameHost(other: ServerAddress): Boolean =
        (host ?: hostOf(text)).equals(other.host ?: hostOf(other.text), ignoreCase = true)

    /** A device found at [location] is this address's. */
    fun matches(location: String): Boolean =
        if (host == null) location == text else hostOf(location).equals(host, ignoreCase = true)

    companion object {
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
            val ports = port?.let(::listOf) ?: LocalDevice.SERVER_PORTS
            return ServerAddress(text, host, ports.map { "http://$host:$it/description.xml" })
        }

        private fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
        }

        /** The host part of an http(s) URL. */
        fun hostOf(url: String): String =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
    }
}

/** Keeps the addresses added by hand ([ControlPoint.addedAddresses]) across launches. */
interface AddressStore {
    fun load(): List<String>
    fun save(addresses: List<String>)

    /** Not kept (tests, previews). */
    class InMemory(private var addresses: List<String> = emptyList()) : AddressStore {
        override fun load() = addresses
        override fun save(addresses: List<String>) {
            this.addresses = addresses
        }
    }
}
