package com.threemail.android.data.crypto

import android.util.Log
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.MailFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound Autocrypt-key learner per RFC 8180 §3. After every IMAP
 * fetchMessages* pass (i.e. every [MailSyncWorker] cycle that lands new
 * UIDs) we ask the server for each new UID's full header set, look for
 * `Autocrypt` and `Autocrypt-Gossip` headers, parse any we find via
 * [AutocryptHeader.parse], and merge the resolved keydata into
 * [com.threemail.android.domain.model.Account.peerKeys] so the next
 * compose skips the WKD round-trip.
 *
 * # Why a separate class?
 *
 * [MailRemote] deliberately hides the IMAP primitives behind
 * adapter-shaped methods so Gmail / POP3 can share the same call sites.
 * Header learning needs the IMAP-specific
 * [com.threemail.android.data.remote.imap.ImapClient.fetchMessageHeaders]
 * surface; lifting it onto `MailRemote` and stubbing the non-IMAP
 * paths would leak transport detail. Keeping the learner here keeps the
 * interface ([MailRemote]) clean and the IMAP-specific call site lives
 * where it belongs - in IMAP-specific code.
 *
 * # Idempotency
 *
 * - The cache lookup is the first thing the merge does; we never
 *   *overwrite* an existing entry because the cache might contain a
 *   manually-imported key with higher trust than a received gossip
 *   header (e.g. via the per-account key-import UI planned for a future
 *   drop). A re-sent gossip header on a fresh message would otherwise
 *   silently downgrade the trust.
 * - The merge is `putAll(existing) then putAll(new)` so new gossip
 *   entries *only land in slots the existing cache has not claimed*.
 * - Per-UID failures (network, malformed header, unknown type, etc.)
 *   are caught locally and the loop continues - one bad UID is not
 *   allowed to abort the rest of the sync's learning pass.
 *
 * # Where it fits in the worker
 *
 * Caller: [com.threemail.android.sync.MailSyncWorker] invokes
 * [learnFrom] AFTER [com.threemail.android.data.repository.MailRepository.saveMessages]
 * lands the new UIDs in Room. The merge call writes back to
 * [Account.autocryptKeysJson] via [AccountRepository.replaceAutocryptPeerKeys]
 * which is the canonical single-source-of-truth for the per-account
 * cache (see the prior-turn drop for [AccountRepository] JSON helpers).
 */
@Singleton
class AutocryptLearner @Inject constructor(
    private val accountRepository: AccountRepository,
    private val imapClientFactory: ImapClientFactory
) {

    /**
     * Outcome of a single learn pass. `newKeysLearned` is the count of
     * fresh addresses we wrote to the cache; `uidsInspected` is the
     * count of UIDs we issued `fetchMessageHeaders` for. Callers can
     * surface this via a debug log or status notification - production
     * today just discards it.
     */
    data class LearnOutcome(
        val newKeysLearned: Int,
        val uidsInspected: Int
    )

    /**
     * Read each UID's header set via [com.threemail.android.data.remote.imap.ImapClient.fetchMessageHeaders],
     * parse any `Autocrypt` / `Autocrypt-Gossip` header, and merge the
     * result into the per-account [com.threemail.android.domain.model.Account.peerKeys]
     * cache. Returns a [LearnOutcome] describing what changed.
     *
     * The method is total: every step that could fail has a defensive
     * branch inside [runCatching] and a swallowed log line via [Log.w].
     * We don't want a bad gossip header to abort a sync pass.
     */
    suspend fun learnFrom(
        account: Account,
        folder: MailFolder,
        uids: List<Long>
    ): Result<LearnOutcome> = withContext(Dispatchers.IO) {
        // Only IMAP carries header fetching in the live transport layer;
        // Gmail reads headers from the REST metadata fetch in one round
        // and POP3 has no per-message server fetching. The learner
        // returns success(0) for both - callers can call uniformly.
        if (account.accountType != AccountType.IMAP) {
            return@withContext Result.success(LearnOutcome(newKeysLearned = 0, uidsInspected = 0))
        }
        if (uids.isEmpty()) {
            return@withContext Result.success(LearnOutcome(newKeysLearned = 0, uidsInspected = 0))
        }

        // Skip UIDs that may have come from a server fetch but not
        // actually have a UID (rare for IMAP but defensible - the
        // message might have been synthesised locally before a UID
        // assignment). `> 0L` filters that case out before the
        // network round-trip.
        val validUids = uids.filter { it > 0L }
        if (validUids.isEmpty()) {
            return@withContext Result.success(LearnOutcome(newKeysLearned = 0, uidsInspected = 0))
        }

        val existingKeys = accountRepository.loadAutocryptPeerKeys(account.id)
        // Per-pass dedupe so two UIDs from the same sender don't
        // overwrite each other with the second one's header; first
        // one wins, matching the `existing-then-new` merge order
        // described in the class KDoc.
        val passAdditions = LinkedHashMap<String, String>()
        val imapClient = imapClientFactory.create(account)
        var uidsInspected = 0

        for (uid in validUids) {
            runCatching {
                val headers = imapClient.fetchMessageHeaders(folder.serverId, uid).getOrNull()
                    ?: return@runCatching
                uidsInspected++
                // RFC 8180 lets the same wire format appear as
                // `Autocrypt` (own mail) or `Autocrypt-Gossip` (received
                // mail). The inbox side overwhelmingly surfaces the
                // `Autocrypt-Gossip` variant; we accept either.
                val autocryptValue = headers["Autocrypt-Gossip"]?.firstOrNull()
                    ?: headers["Autocrypt"]?.firstOrNull()
                    ?: return@runCatching
                val parsed = AutocryptHeader.parse(autocryptValue) ?: return@runCatching
                val email = parsed.email
                // Don't write past the existing-cache boundary or
                // duplicate within this pass.
                if (existingKeys.containsKey(email) || passAdditions.containsKey(email)) {
                    return@runCatching
                }
                passAdditions[email] = parsed.keyDataBase64
                Log.d(
                    TAG,
                    "Learned ${if (parsed.preferEncrypt == "mutual") "mutual " else ""}" +
                        "Autocrypt key for $email (uid=$uid, account=${account.id})"
                )
            }.onFailure { e ->
                Log.w(TAG, "Autocrypt learning failed for uid=$uid on ${account.email}: ${e.message}")
            }
        }

        if (passAdditions.isNotEmpty()) {
            val merged = LinkedHashMap<String, String>().apply {
                putAll(existingKeys)
                putAll(passAdditions)
            }
            runCatching {
                accountRepository.replaceAutocryptPeerKeys(account.id, merged)
            }.onFailure { e ->
                Log.e(TAG, "Failed to persist merged peerKeys for ${account.email}", e)
                return@withContext Result.failure(e)
            }
        }

        Result.success(
            LearnOutcome(
                newKeysLearned = passAdditions.size,
                uidsInspected = uidsInspected
            )
        )
    }

    companion object {
        private const val TAG = "AutocryptLearner"
    }
}
