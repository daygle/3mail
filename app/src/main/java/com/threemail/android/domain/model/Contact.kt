package com.threemail.android.domain.model

/**
 * A single contact as surfaced by the contact autocomplete. One contact may have
 * several email addresses; the composer always picks one at a time.
 */
data class Contact(
    val id: Long,
    val displayName: String,
    val emails: List<String>
)
