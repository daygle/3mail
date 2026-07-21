package com.threemail.android.data.crypto

import android.util.Log
import com.threemail.android.data.crypto.wkd.WkdClient
import com.threemail.android.data.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outbound mail-PGP composition. Sits ABOVE [OpenPgpController] so the
 * controller's encryption stage doesn't grow new recipient-resolution
 * plumbing every time we add a key source. The orchestrator is
 * responsible for:
 *
 *  1. **Key resolution.** Per-account `Account.peerKeys` cache first
 *     (populated by inbound Autocrypt header parses via
 *     [AutocryptLearner]), then a [WkdClient] HTTPS lookup per recipient
 *     whose key isn't cached. Fresh WKD hits are written back into the
 *     cache (existing entries win) so the next compose for the same
 *     recipient skips the network.
 *  2. **Crypto invocation.** [OpenPgpController.signAndEncrypt] does the
 *     sign-then-encrypt transform against the resolved recipient keys
 *     (plus the sender's own subkey for Sent-folder readability).
 *  3. **Envelope.** [PgpMimeBuilder.buildEnvelope] wraps the ASCII-
 *     armoured output in an RFC 3156 `multipart/encrypted` body.
 */
@Singleton
class MailPgpOutbound @Inject constructor(
    private val openPgpController: OpenPgpController,
    private val accountRepository: AccountRepository,
    private val wkdClient: WkdClient
) {

    /**
     * Summary of which recipient addresses resolved, where the key
     * came from, and which couldn't be resolved (or whose keydata
     * failed to parse).
     *
     * `[unresolvable]` and `[unparseable]` are deliberately distinct:
     * the former means "we couldn't find a key for this address" while
     * the latter means "we have keydata but it didn't decode into a
     * valid [PGPPublicKey]". Both counts flow through the strict-mode
     * decision in [com.threemail.android.sync.SendMailWorker] so the
     * worker falls back to plaintext when either is non-empty.
     */
    data class CompositionOutcome(
        /**
         * Encrypted wire bytes (RFC 3156 multipart/encrypted envelope)
         * or `null` when every stage failed. Callers shouldn't proceed
         * with sending on a null envelope.
         */
        val envelopeBytes: ByteArray?,
        /** Email addresses whose keys came from the per-account cache. */
        val resolvedFromCache: Set<String>,
        /** Email addresses whose keys came from a fresh WKD lookup. */
        val resolvedViaWkd: Set<String>,
        /** Email addresses still unresolvable after Autocrypt + WKD. */
        val unresolvable: Set<String>,
        /**
         * Email addresses whose keydata was found (cache hit or WKD hit)
         * but failed BC decode into a [PGPPublicKey]. The wire body would
         * otherwise carry fewer `PGPEncryptedDataList` entries than the
         * user agreed to - a silent degradation that strict-mode is meant
         * to prevent.
         */
        val unparseable: Set<String>
    )

    /** RFC 8180 `Autocrypt:` header value for the active account; see
     *  [OpenPgpController.autocryptHeaderValue]. Delegated here so
     *  [com.threemail.android.sync.SendMailWorker] doesn't need a second
     *  crypto dependency wired through [com.threemail.android.sync.ThreeMailWorkerFactory]. */
    suspend fun autocryptHeaderValue(): String? = openPgpController.autocryptHeaderValue()

    /**
     * Resolve recipient public-key data for [accountId].
     *
     * Lookup order:
     *   1. `accountRepository.loadAutocryptPeerKeys(accountId)` -
     *      populated by inbound Autocrypt header parses keyed by
     *      lowercased email.
     *   2. `wkdClient.fetch(email)` per recipient whose key isn't
     *      cached. Sequential on purpose - WKD hits a real HTTPS
     *      endpoint, and a single message rarely has more than a
     *      handful of fresh recipients.
     *   3. WKD failures map to "unresolvable" but the call still
     *      returns so the upstream [compose] can fall back to
     *      plaintext rather than failing the whole send.
     *
     * Fresh WKD hits are merged back into the peer-key cache
     * (existing entries win - a cached Autocrypt or manually imported
     * key outranks a network fetch).
     *
     * Returns a `Map<lowercased email -> keydata>` where keydata is
     * armoured (WKD) or raw base64 (Autocrypt) - [PgpEngine.parsePublicKeyRing]
     * accepts both.
     */
    suspend fun resolveKeys(accountId: Long, recipients: List<String>): Map<String, String> {
        val cache = accountRepository.loadAutocryptPeerKeys(accountId)
        val resolved = LinkedHashMap<String, String>()
        val wkdAdditions = LinkedHashMap<String, String>()
        for (raw in recipients) {
            val email = raw.lowercase()
            val cached = cache[email]
            if (!cached.isNullOrBlank()) {
                resolved[email] = cached
                continue
            }
            when (val wk = wkdClient.fetch(email)) {
                is WkdClient.WkdResult.Success -> {
                    keyRingToArmored(wk.ring)?.let {
                        resolved[email] = it
                        wkdAdditions[email] = it
                    }
                }
                WkdClient.WkdResult.NotFound,
                is WkdClient.WkdResult.Failure -> Unit
            }
        }
        if (wkdAdditions.isNotEmpty()) {
            runCatching {
                val merged = LinkedHashMap<String, String>().apply {
                    putAll(wkdAdditions)
                    putAll(cache) // existing entries win on collision
                }
                accountRepository.replaceAutocryptPeerKeys(accountId, merged)
            }.onFailure { e ->
                Log.w(TAG, "Failed to persist WKD keys for account=$accountId: ${e.message}")
            }
        }
        Log.d(TAG, "resolveKeys: ${resolved.size}/${recipients.size} resolved for account=$accountId")
        return resolved
    }

    /**
     * Compose the outbound encrypted envelope. The function is total -
     * every error path produces a [Result] with the failure cause
     * preserved. The outcome envelope may be null on a total crypto
     * failure (controller returned Error/Unavailable), but the
     * [CompositionOutcome.unresolvable] set is still populated so the
     * caller can route the user to a "couldn't find B's key" notice.
     */
    suspend fun compose(
        accountId: Long,
        plaintext: ByteArray,
        recipients: List<String>
    ): Result<CompositionOutcome> = withContext(Dispatchers.IO) {
        val cacheSnapshot = accountRepository.loadAutocryptPeerKeys(accountId).keys
        val resolved = resolveKeys(accountId, recipients)
        val resolvedFromCache = resolved.keys intersect cacheSnapshot
        val resolvedViaWkd = resolved.keys - cacheSnapshot
        val unresolvable = recipients.map { it.lowercase() }.toSet() - resolved.keys

        // Parse every resolved keydata up-front, exactly once per entry.
        // Entries that fail BC decode flow into `unparseable` so the
        // strict-mode decision in the worker accounts for them - otherwise
        // the wire body would silently omit them (a security-relevant
        // degradation).
        val parsedByEmail: Map<String, PGPPublicKey?> =
            resolved.mapValues { (_, keyData) -> keyDataToEncryptionKey(keyData) }
        val parsedKeys: List<PGPPublicKey> = parsedByEmail.values.filterNotNull()
        val unparseable = parsedByEmail.filterValues { it == null }.keys.toSet()

        val pgp = openPgpController.signAndEncrypt(plaintext, parsedKeys)
        val envelopeBytes: ByteArray? = when (pgp) {
            is PgpResult.Success -> PgpMimeBuilder.buildEnvelope(pgp.data)
            is PgpResult.Error -> {
                Log.w(TAG, "signAndEncrypt error: ${pgp.message}")
                null
            }
            is PgpResult.NeedUserInteraction -> {
                Log.w(TAG, "signAndEncrypt needs user interaction - not routed")
                null
            }
            PgpResult.Unavailable -> {
                Log.w(TAG, "PGP unavailable in current runtime")
                null
            }
        }
        Result.success(
            CompositionOutcome(
                envelopeBytes = envelopeBytes,
                resolvedFromCache = resolvedFromCache,
                resolvedViaWkd = resolvedViaWkd,
                unresolvable = unresolvable,
                unparseable = unparseable
            )
        )
    }

    /**
     * Re-armour a parsed [PGPPublicKeyRing] so the value can be cached in
     * the peer-key map in a stable shape. [PgpEngine.parsePublicKeyRing]
     * reads it back on the compose path.
     */
    private fun keyRingToArmored(ring: PGPPublicKeyRing): String? =
        runCatching { PgpEngine.armor(ring.encoded) }.getOrNull()

    /**
     * Decode cached keydata (armoured or Autocrypt raw base64) and select
     * the ring's encryption-capable key. Null on parse failure or a ring
     * with no encryption key - both count as "unparseable" upstream.
     */
    private fun keyDataToEncryptionKey(keyData: String): PGPPublicKey? =
        PgpEngine.parsePublicKeyRing(keyData)?.let(PgpEngine::encryptionKeyOf)

    companion object {
        private const val TAG = "MailPgpOutbound"
    }
}
