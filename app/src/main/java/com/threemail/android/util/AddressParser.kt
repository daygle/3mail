package com.threemail.android.util

import com.threemail.android.domain.model.EmailAddress

/**
 * Parses free-text recipient fields (comma/semicolon separated) into structured
 * addresses. Supports the "Display Name <addr@host>" form and quoted display
 * names, which may contain the separator characters themselves
 * (`"Doe, Jane" <jane@example.com>`). [format] re-quotes such names, so
 * `parse(format(addresses))` returns the original list. Pure JVM logic.
 */
object AddressParser {

    private val NAMED = Regex("^\\s*(.*?)\\s*<\\s*(.+?)\\s*>\\s*$")

    /** Field separators; ignored when they appear inside a quoted name. */
    private const val SEPARATORS = ",;\n"

    fun parse(text: String): List<EmailAddress> =
        splitTopLevel(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { token ->
                val match = NAMED.matchEntire(token)
                if (match != null) {
                    EmailAddress(
                        name = unquote(match.groupValues[1].trim()),
                        address = match.groupValues[2].trim()
                    )
                } else {
                    EmailAddress(address = token)
                }
            }

    fun format(addresses: List<EmailAddress>): String =
        addresses.joinToString(", ") { formatOne(it) }

    /** Formats a single address, quoting its display name when needed. */
    fun format(address: EmailAddress): String = formatOne(address)

    private fun formatOne(address: EmailAddress): String {
        if (address.name.isBlank()) return address.address
        return "${quoteName(address.name)} <${address.address}>"
    }

    /**
     * Quotes a display name that would not survive [parse] unquoted - one
     * containing a separator, an angle bracket, a quote, a backslash or a line
     * break, or one padded with whitespace. Quotes and backslashes inside are
     * escaped so [unquote] restores the original name.
     */
    private fun quoteName(name: String): String {
        val needsQuotes = name.any { it in QUOTED_NAME_SPECIALS } ||
            name.first().isWhitespace() || name.last().isWhitespace()
        if (!needsQuotes) return name
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** Characters that force a display name to be quoted. */
    private const val QUOTED_NAME_SPECIALS = "\",;<>\\\n\r"

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValid(address: String): Boolean = EMAIL.matches(address.trim())

    /**
     * Splits on [SEPARATORS] outside double quotes. Inside quotes a backslash
     * escapes the next character, so `\"` does not close the quote.
     */
    private fun splitTopLevel(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> {
                    current.append(c); escaped = false
                }
                inQuotes && c == '\\' -> {
                    current.append(c); escaped = true
                }
                c == '"' -> {
                    inQuotes = !inQuotes; current.append(c)
                }
                !inQuotes && c in SEPARATORS -> {
                    tokens.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
        }
        tokens.add(current.toString())
        return tokens
    }

    /**
     * Strips one layer of surrounding double quotes and resolves the backslash
     * escapes they allow (`\"` -> `"`, `\\` -> `\`). Bare or partially quoted
     * names are returned trimmed but otherwise untouched.
     */
    private fun unquote(raw: String): String {
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val inner = raw.substring(1, raw.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                sb.append(inner[i + 1]); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
