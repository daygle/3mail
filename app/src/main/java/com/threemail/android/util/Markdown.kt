package com.threemail.android.util

/**
 * Minimal Markdown-to-HTML converter for the composer. Supports the small subset
 * the formatting toolbar can produce: bold, italic, links, inline images,
 * unordered/ordered lists, and paragraphs. Pure JVM logic so it can be
 * unit-tested.
 */
object Markdown {

    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<![*])[*_](?![*\\s])(.+?)(?<![\\s*])[*_](?![*])")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)")

    fun toHtml(markdown: String): String {
        val blocks = markdown.replace("\r\n", "\n").split(Regex("\n{2,}"))
        val html = blocks.joinToString("\n") { block -> renderBlock(block.trim()) }
        return "<html><body style=\"font-family:sans-serif;font-size:14px;line-height:1.5\">$html</body></html>"
    }

    /**
     * Escapes a URL for safe embedding as an HTML attribute value. Also normalizes
     * the scheme to http/https so attackers can't insert `javascript:` (already
     * filtered by [LINK], but defense in depth).
     */
    internal fun escapeUrl(url: String): String {
        val safe = url
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return if (!safe.lowercase().startsWith("http://") && !safe.lowercase().startsWith("https://") && !safe.lowercase().startsWith("mailto:")) {
            "https://$safe"
        } else safe
    }

    /**
     * Escapes an image src for safe embedding in an attribute value. Passes
     * cid:, data:, http(s)://, and mailto: schemes through verbatim (cid: is
     * how the composer refers to inline images). Any other scheme is
     * prefixed with https:// to defeat javascript:-style payloads.
     */
    internal fun escapeImgSrc(src: String): String {
        val safe = src.replace("&", "&amp;").replace("\"", "&quot;")
        val lower = safe.lowercase()
        if (lower.startsWith("cid:") || lower.startsWith("data:") ||
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            return safe
        }
        return "https://$safe"
    }

    private fun renderBlock(block: String): String {
        if (block.isEmpty()) return ""
        val lines = block.split("\n")
        return when {
            lines.all { it.trimStart().startsWith("- ") || it.trimStart().startsWith("* ") } ->
                "<ul>" + lines.joinToString("") { "<li>${inline(it.trimStart().removeRange(0, 2))}</li>" } + "</ul>"
            lines.all { it.trimStart().matches(Regex("^\\d+\\.\\s.*")) } ->
                "<ol>" + lines.joinToString("") { "<li>${inline(it.trimStart().replaceFirst(Regex("^\\d+\\.\\s"), ""))}</li>" } + "</ol>"
            else -> "<p>" + lines.joinToString("<br>") { inline(it) } + "</p>"
        }
    }

    private fun inline(raw: String): String {
        var text = escape(raw)
        // IMAGE must run before LINK so `![alt](url)` is not consumed as a link.
        text = IMAGE.replace(text) { m ->
            val alt = escapeAttr(m.groupValues[1])
            val src = escapeImgSrc(m.groupValues[2])
            "<img src=\"$src\" alt=\"$alt\" style=\"max-width:100%;height:auto;\">"
        }
        text = LINK.replace(text) { m -> "<a href=\"${escapeUrl(m.groupValues[2])}\">${escape(m.groupValues[1])}</a>" }
        text = BOLD.replace(text) { m -> "<strong>${m.groupValues[1]}</strong>" }
        text = ITALIC.replace(text) { m -> "<em>${m.groupValues[1]}</em>" }
        return text
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Escapes both element text and attribute values, including quotes. */
    private fun escapeAttr(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
