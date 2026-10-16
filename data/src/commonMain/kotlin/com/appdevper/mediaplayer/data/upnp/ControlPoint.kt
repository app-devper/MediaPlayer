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

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { ssdp.messages.collect { onMessage(it) } }
            launch {
                while (isActive) {
                    delay(30_000)
                    purgeExpired()
                }
            }
            refresh()
        }
    }

    /** Re-checks network, then sends M-SEARCH. Keeps already known devices. */
    fun refresh() {
        scope.launch {
            _searching.value = true
            try {
                if (ssdp.ensureStarted()) {
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
        scope.launch { fetchDescription(location, ttl) }
    }

    private suspend fun fetchDescription(location: String, ttl: Int) {
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
}
