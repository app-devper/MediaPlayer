package app.devper.appplayer.data.upnp

/** DIDL-Lite parsing and generation (ContentDirectory Browse results and AVTransport metadata). */
object Didl {

    private const val HEADER =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" " +
            "xmlns:sec=\"http://www.sec.co.kr/\">"

    /** Subtitle MIME types (in res protocolInfo) → the type we keep. */
    private val subtitleTypes = mapOf(
        "text/srt" to "srt", "application/x-subrip" to "srt", "text/x-srt" to "srt", "application/srt" to "srt",
        "text/vtt" to "vtt",
    )

    /** Types some servers give any caption file (Samsung's smi/caption, text/plain): what it is shows in the URL. */
    private val genericCaptionTypes = setOf("smi/caption", "text/plain")

    /** srt / vtt for a subtitle res, "" for another caption type we can't read, null for not a subtitle. */
    private fun subtitleType(r: DidlResource): String? {
        val mime = r.mimeType.lowercase()
        subtitleTypes[mime]?.let { return it }
        if (mime !in genericCaptionTypes) return null
        val ext = r.uri.substringBefore('?').substringAfterLast('.').lowercase()
        return if (ext == "srt" || ext == "vtt") ext else if (mime == "smi/caption") "" else null
    }

    private fun isWebAddress(uri: String) = uri.lowercase().let { it.startsWith("http://") || it.startsWith("https://") }

    fun parse(didl: String): List<DidlObject> {
        if (didl.isBlank()) return emptyList()
        val root = Xml.parse(didl)
        return root.children.mapNotNull { el ->
            when (el.name) {
                "container" -> parseObject(el, isContainer = true)
                "item" -> parseObject(el, isContainer = false)
                else -> null
            }
        }
    }

    private fun parseObject(el: XmlElement, isContainer: Boolean): DidlObject {
        val (subtitleRes, resources) = el.children("res").map { r ->
            DidlResource(
                uri = r.text.trim(),
                protocolInfo = r.attr("protocolInfo").orEmpty(),
                size = r.attr("size")?.toLongOrNull(),
                duration = r.attr("duration"),
                resolution = r.attr("resolution"),
            )
        }.partition { subtitleType(it) != null }
        // Kept apart from the media's own resources, so a subtitle is never what plays; only types we read.
        val subtitles = (
            subtitleRes.map { DidlSubtitle(it.uri, subtitleType(it).orEmpty()) } +
                (el.children("CaptionInfoEx") + el.children("CaptionInfo")).map { DidlSubtitle(it.text.trim(), it.attr("type")?.lowercase() ?: "srt") }
            ).filter { (it.type == "srt" || it.type == "vtt") && isWebAddress(it.uri) }.distinctBy { it.uri }
        return DidlObject(
            id = el.attr("id").orEmpty(),
            parentId = el.attr("parentID").orEmpty(),
            title = el.childText("title").orEmpty().ifEmpty { "(untitled)" },
            upnpClass = el.childText("class").orEmpty(),
            isContainer = isContainer,
            childCount = el.attr("childCount")?.toIntOrNull(),
            artist = el.childText("artist") ?: el.childText("creator"),
            album = el.childText("album"),
            albumArtUri = el.childText("albumArtURI")?.takeIf { it.isNotEmpty() },
            resources = resources,
            subtitles = subtitles,
        )
    }

    /**
     * Builds a DIDL-Lite document for the given objects (unescaped XML). [subtitleResources]: list
     * subtitles as res too (for Browse); metadata for a renderer has only sec:CaptionInfoEx, since a
     * strict renderer may refuse an item with more than one res.
     */
    fun generate(objects: List<DidlObject>, subtitleResources: Boolean = true): String = buildString {
        append(HEADER)
        for (o in objects) appendObject(o, subtitleResources)
        append("</DIDL-Lite>")
    }

    private fun StringBuilder.appendObject(o: DidlObject, subtitleResources: Boolean) {
        val tag = if (o.isContainer) "container" else "item"
        append('<').append(tag)
        append(" id=\"").append(Xml.escape(o.id)).append('"')
        append(" parentID=\"").append(Xml.escape(o.parentId)).append('"')
        append(" restricted=\"1\"")
        if (o.isContainer) {
            append(" searchable=\"0\"")
            o.childCount?.let { append(" childCount=\"").append(it).append('"') }
        }
        append('>')
        append("<dc:title>").append(Xml.escape(o.title)).append("</dc:title>")
        append("<upnp:class>").append(Xml.escape(o.upnpClass)).append("</upnp:class>")
        o.artist?.let {
            append("<dc:creator>").append(Xml.escape(it)).append("</dc:creator>")
            append("<upnp:artist>").append(Xml.escape(it)).append("</upnp:artist>")
        }
        o.album?.let { append("<upnp:album>").append(Xml.escape(it)).append("</upnp:album>") }
        o.albumArtUri?.let { append("<upnp:albumArtURI>").append(Xml.escape(it)).append("</upnp:albumArtURI>") }
        for (r in o.resources) {
            append("<res protocolInfo=\"").append(Xml.escape(r.protocolInfo)).append('"')
            r.size?.let { append(" size=\"").append(it).append('"') }
            r.duration?.let { append(" duration=\"").append(Xml.escape(it)).append('"') }
            r.resolution?.let { append(" resolution=\"").append(Xml.escape(it)).append('"') }
            append('>').append(Xml.escape(r.uri)).append("</res>")
        }
        // Both ways DLNA players look for subtitles: a res of a subtitle type, and Samsung's CaptionInfoEx.
        for (s in o.subtitles) {
            if (subtitleResources) {
                append("<res protocolInfo=\"http-get:*:").append(if (s.type == "vtt") "text/vtt" else "text/srt").append(":*\">")
                append(Xml.escape(s.uri)).append("</res>")
            }
            append("<sec:CaptionInfoEx sec:type=\"").append(Xml.escape(s.type)).append("\">").append(Xml.escape(s.uri)).append("</sec:CaptionInfoEx>")
        }
        append("</").append(tag).append('>')
    }

    /** DLNA protocolInfo for a plain HTTP stream that supports byte-range seeking. */
    fun protocolInfo(mime: String): String {
        val profile = when (mime) {
            "image/jpeg" -> "DLNA.ORG_PN=JPEG_LRG;"
            "image/png" -> "DLNA.ORG_PN=PNG_LRG;"
            "audio/mpeg" -> "DLNA.ORG_PN=MP3;"
            else -> ""
        }
        val flags = if (mime.startsWith("image/")) "00F00000000000000000000000000000" else "01700000000000000000000000000000"
        return "http-get:*:$mime:${profile}DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
    }
}
