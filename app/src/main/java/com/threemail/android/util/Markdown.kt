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
        text = LINK.replace(text) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
        text = BOLD.replace(text) { m -> "<strong>${m.groupValues[1]}</strong>" }
        text = ITALIC.replace(text) { m -> "<em>${m.groupValues[1]}</em>" }
        return text
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
