package app.devper.appplayer.data.upnp

/**
 * Minimal, dependency-free XML DOM parser — enough for UPnP device descriptions,
 * SOAP envelopes and DIDL-Lite. Namespaces are not resolved: [XmlElement.name] is the
 * local name (prefix stripped) and lookups are case-sensitive on that local name.
 */
class XmlElement(
    val qName: String,
    val attributes: Map<String, String>,
) {
    val name: String = qName.substringAfter(':')
    val children: MutableList<XmlElement> = mutableListOf()
    private val textBuilder = StringBuilder()
    val text: String get() = textBuilder.toString()

    internal fun appendText(s: String) {
        textBuilder.append(s)
    }

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlElement> = children.filter { it.name == name }

    fun childText(name: String): String? = child(name)?.text?.trim()

    /** Attribute lookup by local name (ignores prefix, e.g. `dlna:profileID`). */
    fun attr(name: String): String? =
        attributes[name] ?: attributes.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    /** Depth-first search for the first descendant (or self) with the given local name. */
    fun find(name: String): XmlElement? {
        if (this.name == name) return this
        for (c in children) c.find(name)?.let { return it }
        return null
    }

    override fun toString(): String = "<$qName ${attributes}>${text.take(40)}"
}

class XmlParseException(message: String) : Exception(message)

object Xml {

    fun parse(input: String): XmlElement {
        val p = Parser(input)
        return p.parseDocument()
    }

    fun escape(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val end = s.indexOf(';', i)
                if (end > i && end - i <= 10) {
                    val ent = s.substring(i + 1, end)
                    val decoded: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePointToString)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePointToString)
                        else -> null
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePointToString(cp: Int): String = when {
        cp < 0 || cp > 0x10FFFF -> ""
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf(((v shr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun parseDocument(): XmlElement {
            val stack = ArrayDeque<XmlElement>()
            var root: XmlElement? = null
            while (pos < s.length) {
                val lt = s.indexOf('<', pos)
                if (lt < 0) break
                if (lt > pos && stack.isNotEmpty()) stack.last().appendText(unescape(s.substring(pos, lt)))
                pos = lt
                when {
                    s.startsWith("<?", pos) -> pos = indexOrThrow("?>", pos) + 2
                    s.startsWith("<!--", pos) -> pos = indexOrThrow("-->", pos) + 3
                    s.startsWith("<![CDATA[", pos) -> {
                        val end = indexOrThrow("]]>", pos)
                        stack.lastOrNull()?.appendText(s.substring(pos + 9, end))
                        pos = end + 3
                    }
                    s.startsWith("<!", pos) -> pos = skipDoctype(pos)
                    s.startsWith("</", pos) -> {
                        val end = indexOrThrow(">", pos)
                        val closing = s.substring(pos + 2, end).trim()
                        // Be lenient: pop until we find the matching element.
                        while (stack.isNotEmpty()) {
                            val top = stack.removeLast()
                            if (top.qName == closing) break
                        }
                        pos = end + 1
                        if (stack.isEmpty() && root != null) return root
                    }
                    else -> {
                        val (el, selfClosing) = parseStartTag()
                        if (stack.isEmpty()) {
                            if (root == null) root = el
                        } else {
                            stack.last().children.add(el)
                        }
                        if (!selfClosing) stack.addLast(el)
                        else if (stack.isEmpty()) return el
                    }
                }
            }
            return root ?: throw XmlParseException("No root element")
        }

        private fun skipDoctype(start: Int): Int {
            var depth = 0
            var i = start
            while (i < s.length) {
                when (s[i]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) return i + 1
                }
                i++
            }
            throw XmlParseException("Unterminated DOCTYPE")
        }

        private fun indexOrThrow(token: String, from: Int): Int {
            val i = s.indexOf(token, from)
            if (i < 0) throw XmlParseException("Expected '$token' after $from")
            return i
        }

        private fun parseStartTag(): Pair<XmlElement, Boolean> {
            pos++ // '<'
            val nameStart = pos
            while (pos < s.length && !s[pos].isWhitespace() && s[pos] != '>' && s[pos] != '/') pos++
            val qName = s.substring(nameStart, pos)
            if (qName.isEmpty()) throw XmlParseException("Empty tag name at $nameStart")
            val attrs = LinkedHashMap<String, String>()
            while (pos < s.length) {
                skipWs()
                if (pos >= s.length) break
                val c = s[pos]
                if (c == '>') {
                    pos++
                    return XmlElement(qName, attrs) to false
                }
                if (c == '/' && s.startsWith("/>", pos)) {
                    pos += 2
                    return XmlElement(qName, attrs) to true
                }
                val aStart = pos
                while (pos < s.length && s[pos] != '=' && !s[pos].isWhitespace() && s[pos] != '>' && s[pos] != '/') pos++
                val aName = s.substring(aStart, pos)
                skipWs()
                if (pos < s.length && s[pos] == '=') {
                    pos++
                    skipWs()
                    val quote = s[pos]
                    if (quote == '"' || quote == '\'') {
                        val end = indexOrThrow(quote.toString(), pos + 1)
                        attrs[aName] = unescape(s.substring(pos + 1, end))
                        pos = end + 1
                    } else {
                        val vStart = pos
                        while (pos < s.length && !s[pos].isWhitespace() && s[pos] != '>') pos++
                        attrs[aName] = unescape(s.substring(vStart, pos))
                    }
                } else if (aName.isNotEmpty()) {
                    attrs[aName] = ""
                } else {
                    pos++
                }
            }
            throw XmlParseException("Unterminated tag <$qName")
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
