package com.threemail.android.data.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outbound mail-PGP composition - temporarily disabled alongside
 * [OpenPgpController] (see that class for why the BC 1.78 crypto module was
 * reverted to a stub).
 *
 * [compose] now always reports every recipient as unresolvable and returns a
 * null envelope, so [com.threemail.android.sync.SendMailWorker]'s strict-mode
 * decision falls through to sending plaintext.
 */
@Singleton
class MailPgpOutbound @Inject constructor() {

    /**
     * Summary of recipient key resolution. Shape preserved so `SendMailWorker`
     * compiles unchanged; in the disabled state [envelopeBytes] is always null
     * and every recipient lands in [unresolvable].
     */
    data class CompositionOutcome(
        val envelopeBytes: ByteArray?,
        val resolvedFromCache: Set<String>,
        val resolvedViaWkd: Set<String>,
        val unresolvable: Set<String>,
        val unparseable: Set<String>
    )

    @Suppress("UNUSED_PARAMETER")
    suspend fun compose(
        accountId: Long,
        plaintext: ByteArray,
        recipients: List<String>
    ): Result<CompositionOutcome> = Result.success(
        CompositionOutcome(
            envelopeBytes = null,
            resolvedFromCache = emptySet(),
            resolvedViaWkd = emptySet(),
            unresolvable = recipients.map { it.lowercase() }.toSet(),
            unparseable = emptySet()
        )
    )
}
