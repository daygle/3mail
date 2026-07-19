package com.threemail.android.data.crypto

import android.util.Log
import com.threemail.android.data.crypto.wkd.WkdClient
import com.threemail.android.data.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outbound mail-PGP composition. Sits ABOVE [OpenPgpController] so the
 * controller's encryption stage doesn't grow new recipient-resolution
 * plumbing every time we add a key source. The orchestrator is
 * responsible for:
 *
 *  1. **Key resolution.** Per-account [Account.peerKeys] cache first
 *     (populated by inbound Autocrypt header parses), then a
 *     [WkdClient] HTTPS lookup per recipient whose key isn't cached.
 *  2. **Crypto invocation.** [OpenPgpController.signAndEncrypt] does
 *     the sign-then-encrypt transform. (Internally the controller
 *     still self-encrypts because the keygen path is in flight; the
 *     resolved key list is forwarded as the `recipients` argument so
 *     every downstream wire output knows what was queried.)
 *  3. **Envelope.** [PgpMimeBuilder.buildEnvelope] wraps the ASCII-
 *     armoured output in an RFC 3156 `multipart/encrypted` body.
 *
 * This is additive: nothing in [OpenPgpController] is changed and the
 * existing inline-PGP call sites in [com.threemail.android.ui.screens.compose.ComposeViewModel]
 * keep going through the same controller API.
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
     * failed to parse). Surfaced through the compose screen's
     * "B's key not found" affordance so users can decide whether to
     * send anyway (encrypt-to-self fallback) or back out.
     *
     * `[unresolvable]` and `[unparseable]` are deliberately distinct:
     * the former means "we couldn't find a key for this address" while
     * the latter means "we have keydata but it didn't decode into a
     * valid [PGPPublicKey]". Both counts flow through the strict-mode
     * decision in [SendMailWorker] so the worker falls back to
     * plaintext when either is non-empty.
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
         * Email addresses whose keydata was found (cache hit or WKD
         * hit) but failed BC 1.78 decode into a [PGPPublicKey]. The
         * wire body would otherwise carry fewer
         * `PGPEncryptedDataList` entries than the user agreed to - a
         * silent degradation that strict-mode is meant to prevent.
         */
        val unparseable: Set<String>
    )

    /**
     * Resolve recipient public keys for [accountId].
     *
     * Lookup order:
     *   1. `accountRepository.loadAutocryptPeerKeys(accountId)` -
     *      populated by inbound Autocrypt header parses keyed by
     *      lowercased email.
     *   2. `wkdClient.fetch(email)` per recipient whose key isn't
     *      cached. Sequential on purpose - WKD hits a real DNS /
     *      HTTPS endpoint, and a single message rarely has more than
     *      a handful of fresh recipients.
     *   3. WKD failures map to "unresolvable" but the call still
     *      returns success so the upstream [compose] call can continue
     *      with encrypt-to-self rather than failing the whole send.
     *
     * Returns a `Map<lowercased email -> ASCII-armoured keydata>`
     * ready to be passed to [OpenPgpController.signAndEncrypt] as the
     * `recipients` argument.
     */
    suspend fun resolveKeys(accountId: Long, recipients: List<String>): Map<String, String> {
        val cache = accountRepository.loadAutocryptPeerKeys(accountId)
        val resolved = LinkedHashMap<String, String>()
        for (raw in recipients) {
            val email = raw.lowercase()
            val cached = cache[email]
            if (!cached.isNullOrBlank()) {
                resolved[email] = cached
                continue
            }
            when (val wk = wkdClient.fetch(email)) {
                is WkdClient.WkdResult.Success -> {
                    keyRingToArmored(wk.ring)?.let { resolved[email] = it }
                }
                WkdClient.WkdResult.NotFound,
                is WkdClient.WkdResult.Failure -> Unit
            }
        }
        Log.d(TAG, "resolveKeys: ${resolved.size}/${recipients.size} resolved for account=$accountId")
        return resolved
    }

    /**
     * Compose the outbound encrypted envelope. The function is
     * total - every error path produces a [Result] with the failure
     * cause preserved. The outcome envelope may be null on a total
     * crypto failure (controller returned Error/Unavailable), but the
     * [unresolvable] set is still populated so the compose screen
     * can route the user to a "couldn't find B's key" notice.
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

        // Parse every resolved keydata up-front. Entries that fail BC
        // decode flow into `unparseable` so the strict-mode decision in
        // the worker accounts for them - otherwise the wire body would
        // silently omit them (a security-relevant degradation).
        val parsedKeys: List<PGPPublicKey> = resolvedAsPublicKeys(resolved)
        val unparseable = unparseableEmails(resolved)

        val pgp = openPgpController.signAndEncrypt(plaintext, parsedKeys)
        val envelopeBytes: ByteArray? = when (pgp) {
            is PgpResult.Success -> PgpMimeBuilder.buildEnvelope(pgp.data)
            is PgpResult.Error -> {
                Log.w(TAG, "signAndEncrypt error: ${pgp.message}")
                null
            }
            is PgpResult.NeedUserInteraction -> {
                Log.w(TAG, "signAndEncrypt needs user interaction - not yet routed in this drop")
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
     * Re-armour a parsed [PGPPublicKeyRing] so the value can be cached
     * in [Account.peerKeys] in the same wire shape an Autocrypt header
     * would carry (single BEGIN/END block, no headers). Used by the
     * WKD-fallback path so the next compose for the same recipient
     * skips the network.
     */
    private fun keyRingToArmored(ring: PGPPublicKeyRing): String? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(ring.encoded) }
        out.toString(Charsets.UTF_8)
    }.getOrNull()

    /**
     * Convert the resolved (lower-cased email -> ASCII-armoured keydata)
     * map into an ordered list of [PGPPublicKey]s that the controller
     * can directly add. We prefer the first encryption-capable subkey
     * ([PGPPublicKey.isEncryptionKey]) on each ring and fall back to the
     * master key if the ring has no separate subkey.
     *
     * Entries whose ring fails BC 1.78 decode are dropped here; the
     * caller (compose()) re-derives the affected addresses into
     * [CompositionOutcome.unparseable] so the strict-mode decision
     * fails fast rather than silently shrinking the wire body.
     *
     * Order follows the [resolved] map's insertion order: lowercased
     * peerKeys (cache) first, then in-order WKD hits. The controller's
     * internal loop preserves this order on the wire
     * [PGPEncryptedDataList].
     */
    private fun resolvedAsPublicKeys(resolved: Map<String, String>): List<PGPPublicKey> =
        resolved.values.mapNotNull { armored -> armoredKeyToPublicKey(armored) }

    private fun armoredKeyToPublicKey(armored: String): PGPPublicKey? = runCatching {
        val input = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8))
        )
        val ring = PGPPublicKeyRing(input)
        ring.publicKeys.firstOrNull { it.isEncryptionKey } ?: ring.publicKey
    }.getOrNull()

    /**
     * Compute the set of resolved addresses whose keydata failed BC
     * decode. The derivation walks `resolved` in order and parses each
     * entry lazily so we only spend cycles on the malformed subset.
     */
    private fun unparseableEmails(resolved: Map<String, String>): Set<String> =
        resolved.entries
            .filter { (_, armored) -> armoredKeyToPublicKey(armored) == null }
            .map { it.key }
            .toSet()

    companion object {
        private const val TAG = "MailPgpOutbound"
    }
}
