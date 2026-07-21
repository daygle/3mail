package com.threemail.android.ui.screens.account

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.R
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.autoconfig.AutoconfigClient
import com.threemail.android.data.remote.autoconfig.ProviderPreset
import com.threemail.android.data.remote.gmail.GoogleAuthHelper
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Security
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val googleAuthHelper: GoogleAuthHelper,
    private val mailRemoteFactory: MailRemoteFactory,
    private val autoconfigClient: AutoconfigClient,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    data class UiState(
        val email: String = "",
        val displayName: String = "",
        val password: String = "",
        val server: String = "",
        val port: String = "993",
        val outgoingServer: String = "",
        val outgoingPort: String = "587",
        /**
         * Connection security applied to IMAP and SMTP. Defaults to SSL_TLS
         * to match the implicit-SSL default the legacy `useEncryption=true`
         * row used to produce, so existing users see no behaviour change on
         * upgrade.
         */
        val security: Security = Security.SSL_TLS,
        val accountType: AccountType = AccountType.IMAP,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null,
        val pendingGmailEmail: String = "",
        val pendingGmailDisplayName: String = "",
        /**
         * Non-blocking informational banner shown on the Add Account screen
         * when the IMAP connect handshake surfaced a server capability that
         * warrants a UX change. Today only one trigger fires this: a STARTTLS
         * auto-upgrade from Security.NONE, where the user picked cleartext
         * but the server can do better, so we transparently bump to STARTTLS
         * and explain the change.
         *
         * Cleared by [updateSecurity] so the banner doesn't outlive the
         * user's choice to manually pick a different chip.
         */
        val upgradeBanner: String? = null,
        /** True while an autoconfig lookup is in flight (Other-provider path). */
        val isDiscovering: Boolean = false,
        /** Result banner after an autoconfig lookup ("found" / "not found"). */
        val discoveryMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun updateEmail(value: String) { _uiState.value = _uiState.value.copy(email = value) }
    fun updateDisplayName(value: String) { _uiState.value = _uiState.value.copy(displayName = value) }
    fun updatePassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }
    fun updateServer(value: String) { _uiState.value = _uiState.value.copy(server = value) }
    fun updatePort(value: String) { _uiState.value = _uiState.value.copy(port = value) }
    fun updateOutgoingServer(value: String) { _uiState.value = _uiState.value.copy(outgoingServer = value) }
    fun updateOutgoingPort(value: String) { _uiState.value = _uiState.value.copy(outgoingPort = value) }
    fun updateSecurity(value: Security) {
        val state = _uiState.value
        _uiState.value = state.copy(
            security = value,
            port = securityPort(state.port, state.security, value, ::defaultIncomingPort),
            outgoingPort = securityPort(state.outgoingPort, state.security, value, ::defaultOutgoingPort),
            // The user just overrode the auto-upgrade; the banner is no
            // longer contextually true. Clear it.
            upgradeBanner = null
        )
    }
    fun updateAccountType(value: AccountType) {
        val state = _uiState.value
        // Reset the incoming port to the default for the new protocol so a user
        // switching IMAP <-> POP3 lands on 993/143 vs 995/110 without editing.
        _uiState.value = state.copy(
            accountType = value,
            port = defaultIncomingPortFor(value, state.security).toString()
        )
    }

    /**
     * Standard incoming port for the chosen protocol + security mode. Used as
     * the fallback in [save] when the user hasn't typed one, and as the
     * auto-reset target in [updateSecurity] when the user is still on the
     * previous default. Reads the current [UiState.accountType].
     */
    private fun defaultIncomingPort(security: Security): Int =
        defaultIncomingPortFor(_uiState.value.accountType, security)

    private fun defaultIncomingPortFor(accountType: AccountType, security: Security): Int =
        when (accountType) {
            AccountType.POP3 -> when (security) {
                Security.SSL_TLS -> 995
                Security.STARTTLS, Security.NONE -> 110
            }
            // IMAP (and Gmail, which never reaches this manual path).
            else -> when (security) {
                Security.SSL_TLS -> 993
                Security.STARTTLS, Security.NONE -> 143
            }
        }

    /**
     * Standard SMTP submission port for the chosen security mode. Mirrors
     * [defaultIncomingPort]; SSL_TLS SMTP typically lives on 465 (SMTPS),
     * STARTTLS on 587 (submission), NONE on 25 (legacy relay).
     */
    private fun defaultOutgoingPort(security: Security): Int = when (security) {
        Security.SSL_TLS -> 465
        Security.STARTTLS -> 587
        Security.NONE -> 25
    }
    fun updateError(message: String?) { _uiState.value = _uiState.value.copy(error = message) }

    /**
     * Pre-fill the incoming/outgoing server fields from a built-in provider
     * [preset] (the password-auth path). Gmail is handled separately via
     * [signInWithGoogle], so this is only reached for IMAP presets.
     */
    fun applyProvider(preset: ProviderPreset) {
        _uiState.value = _uiState.value.copy(
            accountType = AccountType.IMAP,
            server = preset.imapHost,
            port = preset.imapPort.toString(),
            outgoingServer = preset.smtpHost,
            outgoingPort = preset.smtpPort.toString(),
            security = preset.imapSecurity,
            upgradeBanner = null,
            discoveryMessage = null,
            error = null
        )
    }

    /**
     * Autoconfig discovery for an arbitrary domain (the "Other" path). Looks the
     * email's domain up via [AutoconfigClient] and pre-fills the server fields
     * on success, or surfaces a "not found" hint so the user enters them by hand.
     */
    fun discoverAutoconfig() {
        val email = _uiState.value.email.trim()
        if (!email.contains('@')) return
        _uiState.value = _uiState.value.copy(isDiscovering = true, discoveryMessage = null)
        viewModelScope.launch {
            val cfg = autoconfigClient.discover(email)
            _uiState.value = if (cfg != null) {
                _uiState.value.copy(
                    isDiscovering = false,
                    accountType = AccountType.IMAP,
                    server = cfg.imapHost,
                    port = cfg.imapPort.toString(),
                    outgoingServer = cfg.smtpHost,
                    outgoingPort = cfg.smtpPort.toString(),
                    security = cfg.imapSecurity,
                    displayName = _uiState.value.displayName.ifBlank { cfg.displayName.orEmpty() },
                    discoveryMessage = context.getString(R.string.autoconfig_found)
                )
            } else {
                _uiState.value.copy(
                    isDiscovering = false,
                    discoveryMessage = context.getString(R.string.autoconfig_not_found)
                )
            }
        }
    }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            googleAuthHelper.signInWithGoogle(context).onSuccess { userInfo ->
                saveGmailAccount(userInfo.email, userInfo.displayName.orEmpty())
            }.onFailure { error ->
                updateError("Google Sign-In failed: ${error.message}")
            }
        }
    }

    fun save() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                // First pass: build an Account with the user-picked
                // security and probe the server. We need a connect to read
                // the CAPABILITY list before we can decide whether to bump
                // Security.NONE -> Security.STARTTLS.
                val provisional = buildAccount(state)
                val probe = mailRemoteFactory.create(provisional).testConnection()
                probe.onSuccess { caps ->
                    // Auto-upgrade: the user picked cleartext, but the
                    // server can do STARTTLS - transparently bump the UI to
                    // STARTTLS so the saved Account matches what the
                    // server can actually serve. We only ever upgrade (never
                    // downgrade); only NONE -> STARTTLS. SSL_TLS stays put
                    // because the server can't un-encrypt a connection.
                    if (state.security == Security.NONE && caps.has("STARTTLS")) {
                        applySecurityUpgrade(
                            newSecurity = Security.STARTTLS,
                            bannerMessage = context.getString(
                                R.string.security_starttls_auto_upgrade
                            )
                        )
                    }
                    // Second pass: rebuild the Account from the (possibly
                    // upgraded) UI state and save. We don't re-probe with
                    // the upgraded security - the previous CAPABILITY read
                    // already confirmed STARTTLS works on this server, and
                    // re-doing the connect costs ~300ms for nothing.
                    val finalAccount = buildAccount(_uiState.value)
                    val accountId = accountRepository.addAccount(finalAccount)
                    syncScheduler.enqueueImmediateSync(accountId)
                    // New accounts follow the global default cadence (override
                    // 0). Schedule now so the account syncs periodically without
                    // waiting for the next app launch's reconcile.
                    syncScheduler.schedulePeriodicSyncForAccount(accountId, DEFAULT_SYNC_INTERVAL_MINUTES)
                    _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
                }.onFailure { error ->
                    Log.e(TAG, "Save failed", error)
                    _uiState.value = _uiState.value.copy(isSaving = false, error = error.message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save crashed", e)
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    /**
     * Build the [Account] domain object the repository persists. Centralised
     * so [save] can rebuild it once before the connect probe and again
     * after the auto-upgrade (since the security + ports may have changed
     * between the two passes).
     */
    private fun buildAccount(state: UiState): Account = Account(
        email = state.email,
        displayName = state.displayName.ifBlank { state.email.substringBefore("@") },
        accountType = state.accountType,
        incomingServer = state.server.ifBlank { null },
        incomingPort = state.port.toIntOrNull() ?: defaultIncomingPort(state.security),
        outgoingServer = state.outgoingServer.ifBlank { null },
        outgoingPort = state.outgoingPort.toIntOrNull() ?: defaultOutgoingPort(state.security),
        security = state.security,
        password = state.password.ifBlank { null }
    )

    /**
     * Apply a security change driven by code (the auto-upgrade banner), not
     * the user. Identical port-reset rules to [updateSecurity] (preserve
     * hand-edited ports, otherwise reset to the default for the new mode)
     * so the visible UI is indistinguishable from a manual chip tap.
     * Sets the banner message explaining the change.
     */
    private fun applySecurityUpgrade(newSecurity: Security, bannerMessage: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            security = newSecurity,
            port = securityPort(state.port, state.security, newSecurity, ::defaultIncomingPort),
            outgoingPort = securityPort(state.outgoingPort, state.security, newSecurity, ::defaultOutgoingPort),
            upgradeBanner = bannerMessage
        )
    }

    /**
     * Port-update rule shared between [updateSecurity] and
     * [applySecurityUpgrade]: when the field still holds the default port
     * for the previous security mode (or is unparseable), reset to the
     * default for the new mode; otherwise leave it alone so a hand-edited
     * stub-server port isn't clobbered.
     */
    private fun securityPort(
        current: String,
        previousSecurity: Security,
        newSecurity: Security,
        defaultFor: (Security) -> Int
    ): String {
        val parsed = current.toIntOrNull()
        return if (parsed == null || parsed == defaultFor(previousSecurity)) {
            defaultFor(newSecurity).toString()
        } else {
            current
        }
    }

    fun saveGmailAccount(email: String, displayName: String) {
        _uiState.value = _uiState.value.copy(
            isSaving = true,
            error = null,
            recoverableAuthIntent = null,
            pendingGmailEmail = email,
            pendingGmailDisplayName = displayName
        )
        viewModelScope.launch {
            try {
                // Gmail uses OAuth XOAUTH2 for both IMAP and SMTP, so the
                // connection-security option is irrelevant on this path. We
                // pin it to SSL_TLS (the historical default) anyway so a
                // potential future fallback to plain IMAP inherits the safest
                // setting.
                val account = Account(
                    email = email,
                    displayName = displayName.ifBlank { email.substringBefore("@") },
                    accountType = AccountType.GMAIL,
                    incomingServer = "imap.gmail.com",
                    incomingPort = 993,
                    security = Security.SSL_TLS,
                    password = null,
                    syncEnabled = true
                )
                val test = mailRemoteFactory.create(account).testConnection()
                test.onSuccess {
                    val id = accountRepository.addAccount(account)
                    syncScheduler.enqueueImmediateSync(id)
                    syncScheduler.schedulePeriodicSyncForAccount(id, DEFAULT_SYNC_INTERVAL_MINUTES)
                    _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
                }.onFailure {
                    Log.e(TAG, "Gmail save failed", it)
                    _uiState.value = _uiState.value.copy(isSaving = false, error = it.message)
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    recoverableAuthIntent = e.intent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gmail save crashed", e)
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        val state = _uiState.value
        saveGmailAccount(state.pendingGmailEmail, state.pendingGmailDisplayName)
    }

    private companion object {
        private const val TAG = "AddAccountViewModel"

        /**
         * Cadence a freshly-added account starts on. Matches the app-wide
         * default and WorkManager's 15-minute minimum period; if the user has
         * changed the global default, the next app-launch reconcile corrects
         * this account to it (the account's own override is 0 = follow default).
         */
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 15L
    }
}
