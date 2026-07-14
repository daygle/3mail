package com.threemail.android.util

import com.threemail.android.domain.model.EmailAddress

/**
 * Parses free-text recipient fields (comma/semicolon separated) into structured
 * addresses. Supports the "Display Name <addr@host>" form. Pure JVM logic.
 */
object AddressParser {

    private val NAMED = Regex("^\\s*(.*?)\\s*<\\s*(.+?)\\s*>\\s*$")

    fun parse(text: String): List<EmailAddress> =
        text.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { token ->
                val match = NAMED.matchEntire(token)
                if (match != null) {
                    EmailAddress(
                        name = match.groupValues[1].trim().trim('"'),
                        address = match.groupValues[2].trim()
                    )
                } else {
                    EmailAddress(address = token)
                }
            }

    fun format(addresses: List<EmailAddress>): String =
        addresses.joinToString(", ") { it.toString() }

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValid(address: String): Boolean = EMAIL.matches(address.trim())
}
