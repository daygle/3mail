package com.threemail.android.domain.model

/**
 * Connection security applied to both IMAP and SMTP. Replaces the legacy
 * `useEncryption: Boolean` (where `true` meant implicit SSL/TLS) with a
 * tri-state enum that also exposes STARTTLS, which many self-hosted IMAP
 * providers require.
 *
 * The on-disk representation in
 * [com.threemail.android.data.local.entity.AccountEntity] is still the
 * (useEncryption, useStartTls) boolean pair - kept that way because an
 * additive column migration in SQLite is far safer than a column-type swap.
 * [com.threemail.android.data.repository.AccountRepository] reconciles the
 * two and enforces the invariant that exactly one of SSL_TLS, STARTTLS, or
 * NONE is representable in domain code.
 *
 * Gmail accounts ignore this entirely: they authenticate via OAuth XOAUTH2
 * and the Gmail REST API rather than IMAP/SMTP.
 */
enum class Security { NONE, STARTTLS, SSL_TLS }/**
 * The folder type a server-side folder maps to on a single account. Used by
 * the per-account folder-role override UI: each account's
 * [com.threemail.android.domain.model.Account.folderRoles] map keys a
 * [FolderType] to the IMAP `folder.fullName` chosen to occupy that role.
 *
 * `FolderType` itself is declared in [com.threemail.android.domain.model.MailFolder.kt]
 * (same package) - this comment block exists here purely so the
 * [com.threemail.android.domain.model.Account] data class below has the
 * reference target it can KDoc-link to. Adding a second `enum class FolderType`
 * in this file would conflict, so the type lives next to the `MailFolder`
 * value class it parameterises.
 */

/**
 * Lets a user send from a different
 * address / display name than the account's primary address (e.g. a shared or
 * plus-addressed alias), each with its own optional signature. Persisted as a
 * JSON list on the account row (see
 * [com.threemail.android.data.local.entity.AccountEntity.identitiesJson]).
 */
data class Identity(
    val displayName: String = "",
    val email: String,
    /** Per-identity signature; blank falls back to the account signature, then omits entirely. */
    val signature: String = ""
)

data class Account(
    val id: Long = 0,
    val email: String,
    val displayName: String,
    val accountType: AccountType,
    val incomingServer: String? = null,
    val incomingPort: Int = 993,
    // Outgoing (SMTP submission) server. When null, ImapClient falls back to a
    // best-effort guess from the email domain / incoming server. Set explicitly
    // for providers whose SMTP host isn't derivable from the IMAP host.
    val outgoingServer: String? = null,
    val outgoingPort: Int = 587,
    /**
     * Connection security for the INCOMING (IMAP/POP3) server. The outgoing
     * (SMTP) server has its own [outgoingSecurity]; historically the two were
     * a single shared value, so this field kept its plain `security` name to
     * avoid a wide rename.
     */
    val security: Security = Security.SSL_TLS,
    /**
     * Connection security for the OUTGOING (SMTP submission) server. Split from
     * [security] so a user whose provider fetches over one mode and submits
     * over another (e.g. IMAPS/993 in, STARTTLS/587 out) can configure each.
     */
    val outgoingSecurity: Security = Security.STARTTLS,
    /**
     * Login username for the INCOMING server. `null`/blank means "use the
     * account [email]" - the historical behaviour, and still the common case.
     * Split out because some providers (or self-hosted setups) authenticate
     * IMAP/POP3 with a bare login name rather than the full address.
     */
    val incomingUsername: String? = null,
    /**
     * Login username for the OUTGOING (SMTP) server. `null`/blank falls back to
     * the incoming login (see [outgoingLogin]), which in turn falls back to
     * [email]. Set only when SMTP submission needs different credentials than
     * fetching.
     */
    val outgoingUsername: String? = null,
    /**
     * Password for the INCOMING server. Persisted in the encrypted
     * [com.threemail.android.data.security.CredentialStore], never the DB. For
     * Gmail this is null (OAuth). Also used as the outgoing password when
     * [outgoingPassword] is unset.
     */
    val password: String? = null,
    /**
     * Password for the OUTGOING (SMTP) server. `null`/blank falls back to the
     * incoming [password] (see [outgoingSecret]), matching the common case
     * where both servers share one credential.
     */
    val outgoingPassword: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    /** Per-account signature; blank means no signature on outgoing mail from this account. */
    val signature: String = "",
    /**
     * Per-account mail-check frequency in minutes. `0` means "use the global
     * default sync interval".
     */
    val syncIntervalMinutes: Long = 0,
    /** Per-account new-mail notification toggle (gated by the global switch). */
    val notificationsEnabled: Boolean = true,
    /**
     * Additional send-as identities (aliases). The account's own [email] /
     * [displayName] is always the implicit primary identity; these are extra
     * addresses the user can pick in the composer's From selector.
     */
    val identities: List<Identity> = emptyList(),
    /**
     * Per-account folder-role overrides. Keyed by [FolderType] (Inbox, SENT,
     * DRAFTS, TRASH, SPAM, ARCHIVE, ALL_MAIL) and valued by the IMAP
     * `folder.fullName` chosen to fill that role. Empty (default) means the
     * name-matching heuristic in ImapClient is authoritative for that
     * account. Persisted as a JSON-encoded column on the account row (see
     * [com.threemail.android.data.local.entity.AccountEntity.folderRolesJson]).
     */
    val folderRoles: Map<FolderType, String> = emptyMap(),
    /**
     * Per-account OpenPGP peer-key cache populated by Autocrypt headers
     * (RFC 8180) and WKD lookups (RFC 9582). Keys are lowercased email
     * addresses; values are base64-blocked keydata exactly as carried
     * in the original Autocrypt header / WKD response. We never re-format
     * here so the importer can cache the exact wire form for fidelity.
     */
    val peerKeys: Map<String, String> = emptyMap(),
    /**
     * Extra IMAP folder `serverId`s to watch for push (IMAP IDLE) in addition
     * to the always-watched INBOX. Opt-in and normally empty: each entry costs
     * a dedicated persistent IDLE connection. IMAP-only - ignored for Gmail
     * (Google push) and POP3 (no push at all).
     */
    val pushFolders: List<String> = emptyList()
) {
    /**
     * The username to authenticate the incoming (IMAP/POP3) server with:
     * the explicit [incomingUsername] if set, otherwise the account [email].
     */
    val incomingLogin: String
        get() = incomingUsername?.takeIf { it.isNotBlank() } ?: email

    /**
     * The username to authenticate the outgoing (SMTP) server with: the
     * explicit [outgoingUsername] if set, otherwise the incoming login (which
     * itself falls back to [email]).
     */
    val outgoingLogin: String
        get() = outgoingUsername?.takeIf { it.isNotBlank() } ?: incomingLogin

    /**
     * The secret to authenticate the outgoing (SMTP) server with: the explicit
     * [outgoingPassword] if set, otherwise the incoming [password]. Null only
     * when neither is configured (e.g. Gmail, which authenticates via OAuth).
     */
    val outgoingSecret: String?
        get() = outgoingPassword?.takeIf { it.isNotBlank() } ?: password
}
