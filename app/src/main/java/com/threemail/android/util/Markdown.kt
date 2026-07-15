package com.threemail.android.util

/**
 * Minimal Markdown-to-HTML converter for the composer. Supports the small subset
 * the formatting toolbar can produce: bold, italic, links, unordered/ordered
 * lists, and paragraphs. Pure JVM logic so it can be unit-tested.
 */
object Markdown {

    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<![*])[*_](?![*\\s])(.+?)(?<![\\s*])[*_](?![*])")
    private val LINK = Regex("\\[(.+?)]\\((https?://[^)\\s]+)\\)")

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
        text = LINK.replace(text) { m -> "<a href=\"${escapeUrl(m.groupValues[2])}\">${escape(m.groupValues[1])}</a>" }
        text = BOLD.replace(text) { m -> "<strong>${m.groupValues[1]}</strong>" }
        text = ITALIC.replace(text) { m -> "<em>${m.groupValues[1]}</em>" }
        return text
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
