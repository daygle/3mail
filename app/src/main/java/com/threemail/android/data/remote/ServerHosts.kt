package com.threemail.android.data.remote

/**
 * Host inference shared by the IMAP and POP3 transports when an account has no
 * explicit server configured.
 *
 * Email domains are case-insensitive (RFC 5321 §2.3.5) and so are DNS names
 * (RFC 4343): `User@Gmail.COM` must resolve to `imap.gmail.com` exactly like
 * `user@gmail.com`, and `IMAP.example.com` must map to `smtp.example.com`.
 */
internal object ServerHosts {

    /** True when [email]'s domain part is [domain], compared case-insensitively. */
    fun isDomain(email: String, domain: String): Boolean =
        email.trim().endsWith("@$domain", ignoreCase = true)

    /**
     * Rewrites the protocol label in an inferred host - `imap.example.com` or
     * `POP.example.com` becomes `smtp.example.com` - leaving the rest intact.
     * Returns null when there is no host to rewrite.
     */
    fun smtpFor(incomingServer: String?, label: String): String? =
        incomingServer?.replace(label, "smtp", ignoreCase = true)
}
