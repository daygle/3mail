package com.threemail.android.data.remote.autoconfig

import com.threemail.android.domain.model.Security

/** How an account authenticates when added through the provider picker. */
enum class ProviderAuth {
    /** Google OAuth (Credential Manager). No password / server fields. */
    OAUTH_GOOGLE,

    /** Classic IMAP/SMTP password (often a provider-issued app-specific password). */
    PASSWORD
}

/**
 * A built-in mail-provider preset. Picking one in the add-account flow fills the
 * incoming/outgoing server settings so the user only needs their email (and,
 * for password providers, a password). "Other" providers fall back to
 * [com.threemail.android.data.remote.autoconfig.AutoconfigClient] discovery or
 * manual entry.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    /** Email domains that map to this provider (lowercased). */
    val domains: List<String>,
    val auth: ProviderAuth,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: Security,
    /**
     * True when the provider requires an app-specific password (2FA accounts
     * can't use the login password over IMAP). Surfaced as a hint in the UI.
     */
    val needsAppPassword: Boolean = false
)

/**
 * Registry of built-in provider presets plus domain lookup. Autoconfig
 * discovery ([AutoconfigClient]) handles anything not listed here.
 */
object MailProviders {

    val GMAIL = ProviderPreset(
        id = "gmail",
        displayName = "Gmail",
        domains = listOf("gmail.com", "googlemail.com"),
        auth = ProviderAuth.OAUTH_GOOGLE,
        imapHost = "imap.gmail.com",
        imapPort = 993,
        imapSecurity = Security.SSL_TLS,
        smtpHost = "smtp.gmail.com",
        smtpPort = 465,
        smtpSecurity = Security.SSL_TLS
    )

    val OUTLOOK = ProviderPreset(
        id = "outlook",
        displayName = "Outlook",
        domains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        auth = ProviderAuth.PASSWORD,
        imapHost = "outlook.office365.com",
        imapPort = 993,
        imapSecurity = Security.SSL_TLS,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        smtpSecurity = Security.STARTTLS,
        needsAppPassword = true
    )

    val ICLOUD = ProviderPreset(
        id = "icloud",
        displayName = "iCloud",
        domains = listOf("icloud.com", "me.com", "mac.com"),
        auth = ProviderAuth.PASSWORD,
        imapHost = "imap.mail.me.com",
        imapPort = 993,
        imapSecurity = Security.SSL_TLS,
        smtpHost = "smtp.mail.me.com",
        smtpPort = 587,
        smtpSecurity = Security.STARTTLS,
        needsAppPassword = true
    )

    val YAHOO = ProviderPreset(
        id = "yahoo",
        displayName = "Yahoo",
        domains = listOf("yahoo.com", "ymail.com", "rocketmail.com"),
        auth = ProviderAuth.PASSWORD,
        imapHost = "imap.mail.yahoo.com",
        imapPort = 993,
        imapSecurity = Security.SSL_TLS,
        smtpHost = "smtp.mail.yahoo.com",
        smtpPort = 465,
        smtpSecurity = Security.SSL_TLS,
        needsAppPassword = true
    )

    val FASTMAIL = ProviderPreset(
        id = "fastmail",
        displayName = "Fastmail",
        domains = listOf("fastmail.com", "fastmail.fm"),
        auth = ProviderAuth.PASSWORD,
        imapHost = "imap.fastmail.com",
        imapPort = 993,
        imapSecurity = Security.SSL_TLS,
        smtpHost = "smtp.fastmail.com",
        smtpPort = 465,
        smtpSecurity = Security.SSL_TLS,
        needsAppPassword = true
    )

    /** All presets, in the order they should appear in the provider picker. */
    val ALL: List<ProviderPreset> = listOf(GMAIL, OUTLOOK, ICLOUD, YAHOO, FASTMAIL)

    /** Preset whose domain list contains [domain] (case-insensitive), or null. */
    fun forDomain(domain: String): ProviderPreset? {
        val d = domain.lowercase()
        return ALL.firstOrNull { d in it.domains }
    }

    /** Preset matching the domain of [email], or null when unknown / malformed. */
    fun forEmail(email: String): ProviderPreset? {
        val domain = email.substringAfter('@', "").takeIf { it.isNotBlank() } ?: return null
        return forDomain(domain)
    }
}
