package com.music.auris.data.podcast

/**
 * A small XML reader for podcast feeds.
 *
 * Android's reader is a DOM parse (`javax.xml`), which Kotlin/Native does not
 * have. This is the same job done by hand: enough of XML to read a feed, and
 * nothing else.
 *
 * What it handles, because feeds really do carry all of it:
 *  - elements and attributes, with namespace prefixes read literally
 *    (`itunes:duration` is the name, not a namespace to resolve);
 *  - text and CDATA sections, concatenated;
 *  - the five predefined entities and numeric character references;
 *  - comments, processing instructions and the doctype, which are skipped.
 *
 * What it does not do: resolve external entities, follow DTDs, or fetch
 * anything. A feed is untrusted input, and a reader that cannot be talked
 * into opening a URL is the point.
 */
internal object PodcastFeedXml {

    /** One element, with its attributes and its text. */
    data class Element(
        val name: String,
        val attributes: Map<String, String>,
        val text: String,
        val children: List<Element>,
    ) {
        /** The first child element with this name (prefix included), or null. */
        fun child(name: String): Element? = children.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** The text of the first child element with this name, or null. */
        fun childText(name: String): String? = child(name)?.text
    }

    /**
     * An element while it is being read.
     *
     * The reader builds these and turns them into [Element]s at the end,
     * because an element's children and text arrive after the element
     * itself does: a parent that was handed to its own parent when it opened
     * would otherwise keep the copy it was handed — the one with no children
     * yet — and lose everything read inside it.
     */
    private class Node(val name: String, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val children = ArrayList<Node>()

        fun toElement(): Element = Element(
            name = name,
            attributes = attributes,
            text = text.toString(),
            children = children.map { it.toElement() },
        )
    }

    /** The root element of [xml], or null when there is none. */
    fun parse(xml: String): Element? {
        // The stack holds the elements that are open, with a nameless root at
        // the bottom to hold the document element.
        val stack = ArrayList<Node>()
        stack.add(Node("", emptyMap()))
        var i = 0
        val n = xml.length
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (lt > i) {
                val text = decode(xml.substring(i, lt))
                if (text.isNotEmpty()) stack.last().text.append(text)
            }
            if (xml.startsWith("<!--", lt)) {
                val end = xml.indexOf("-->", lt)
                i = if (end < 0) n else end + 3
                continue
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val end = xml.indexOf("]]>", lt)
                // CDATA is literal: no entity decoding inside it.
                val body = if (end < 0) xml.substring(lt + 9) else xml.substring(lt + 9, end)
                stack.last().text.append(body)
                i = if (end < 0) n else end + 3
                continue
            }
            if (xml.startsWith("<?", lt)) {
                val end = xml.indexOf("?>", lt)
                i = if (end < 0) n else end + 2
                continue
            }
            if (xml.startsWith("<!", lt)) {
                // A doctype or another declaration: skipped whole, brackets
                // included, so an internal subset does not confuse the scan.
                var depth = 0
                var j = lt
                while (j < n) {
                    when (xml[j]) {
                        '<' -> depth++
                        '>' -> {
                            depth--
                            if (depth == 0) {
                                j++
                                break
                            }
                        }
                    }
                    j++
                }
                i = j
                continue
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt).trim()
            i = gt + 1
            if (tag.startsWith("/")) {
                // A closing tag: pop to the matching open, if there is one.
                val name = tag.removePrefix("/").trim()
                var depth = stack.lastIndex
                while (depth > 0 && !stack[depth].name.equals(name, ignoreCase = true)) depth--
                // `removeRange` is not available on Kotlin/Native's ArrayList,
                // so the tail is dropped one element at a time.
                while (stack.size > depth) stack.removeAt(stack.size - 1)
                continue
            }
            val selfClosing = tag.endsWith("/")
            val body = if (selfClosing) tag.dropLast(1).trim() else tag
            val name = body.takeWhile { !it.isWhitespace() }
            val node = Node(name, attributes(body.drop(name.length)))
            stack.last().children.add(node)
            if (!selfClosing) stack.add(node)
        }
        return stack.first().children.firstOrNull()?.toElement()
    }

    /** The attributes in a start tag's tail: `url="x" type="y"`. */
    private fun attributes(tail: String): Map<String, String> {
        if (tail.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = tail.length
        while (i < n) {
            while (i < n && tail[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && !tail[i].isWhitespace() && tail[i] != '=') i++
            val name = tail.substring(nameStart, i).trim()
            while (i < n && tail[i].isWhitespace()) i++
            if (i >= n || tail[i] != '=') {
                if (name.isNotEmpty()) out[name] = ""
                continue
            }
            i++
            while (i < n && tail[i].isWhitespace()) i++
            if (i >= n) break
            val quote = tail[i]
            if (quote != '"' && quote != '\'') {
                // An unquoted value: read to the next space.
                val valueStart = i
                while (i < n && !tail[i].isWhitespace()) i++
                if (name.isNotEmpty()) out[name] = decode(tail.substring(valueStart, i))
                continue
            }
            i++
            val valueStart = i
            val end = tail.indexOf(quote, i)
            val raw = if (end < 0) tail.substring(valueStart) else tail.substring(valueStart, end)
            i = if (end < 0) n else end + 1
            if (name.isNotEmpty()) out[name] = decode(raw)
        }
        return out
    }

    /** A code point as text, surrogates and all — no JVM `Character` here. */
    private fun codePoint(code: Int): String {
        if (code <= 0xFFFF) return code.toChar().toString()
        val offset = code - 0x10000
        val high = (0xD800 + (offset shr 10)).toChar()
        val low = (0xDC00 + (offset and 0x3FF)).toChar()
        return "$high$low"
    }

    /** The five predefined entities and numeric character references. */
    fun decode(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val body = raw.substring(i + 1, end)
            val replacement = when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { codePoint(it) }
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { codePoint(it) }
                else -> null
            }
            if (replacement == null) {
                out.append(c)
                i++
            } else {
                out.append(replacement)
                i = end + 1
            }
        }
        return out.toString()
    }
}
