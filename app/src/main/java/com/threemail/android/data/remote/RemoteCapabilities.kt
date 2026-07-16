package com.threemail.android.data.remote

/**
 * Snapshot of the IMAP server's CAPABILITY response, captured once during the
 * initial [MailRemote.testConnection] handshake.
 *
 * `capabilities` holds the raw keyword list (case-preserved from the server
 * banner / explicit CAPABILITY reply). Callers should use [has] for the
 * common case of "does this server advertise STARTTLS?" rather than calling
 * `contains` directly - RFC 3501 keywords are case-insensitive in practice
 * (Dovecot, Cyrus, Gmail all uppercase them, but the RFC explicitly says
 * case must NOT be used as a discriminator).
 *
 * The Gmail remote returns an empty list rather than fabricating capability
 * names for the REST API, since Gmail auth never goes through IMAP and the
 * OAuth flow has no STARTTLS upgrade option.
 *
 * The fields are independent: a connect trial can succeed yet the IMAP
 * server might omit the CAPABILITY data (rare, but possible on
 * misconfigured self-hosted daemons). In that case the remote returns
 * `RemoteCapabilities(emptyList())` so the caller can still treat the
 * connection as established without assuming any specific capability.
 */
data class RemoteCapabilities(
    val capabilities: List<String>
) {
    /**
     * Case-insensitive CAPABILITY lookup. The IMAP RFCs treat keywords
     * case-insensitively on the wire; doing the same here lets the Add
     * Account flow auto-upgrade cleanly across the in-the-wild variety of
     * `STARTTLS` vs `starttls` vs `StartTLS` banners.
     */
    fun has(name: String): Boolean = capabilities.any { it.equals(name, ignoreCase = true) }
}
