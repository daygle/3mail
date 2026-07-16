package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Standalone FTS4 virtual table used for full-text search across the cached mail
 * corpus. We intentionally do NOT use `@Fts4(contentEntity = MessageEntity::class)`
 * here: Room's auto-generated triggers would forward `bodyPlain` / `bodyHtml`
 * straight from `messages` into this table, which would crash SQLite (FTS tables
 * reject NULL inserts). Instead we maintain this table from custom triggers that
 * `COALESCE(?, '')` before insertion.
 *
 * Tokenizer: `unicode61` (better Unicode + diacritic handling vs the default
 * `simple`). Note this still does not tokenize CJK by character - for that users
 * would need a custom ICU tokenizer; the migration SQL mirrors this choice so
 * fresh and upgraded installs see the same schema.
 *
 * The tokenizer is passed as a plain string literal rather than the
 * [androidx.room.FtsTokenizer.UNICODE61] constant. Room 2.6.1's KSP processor
 * hits a `ClassCastException` when resolving the constant reference inside the
 * annotation parameter; the string literal sidesteps the bug. The constant
 * resolves to the same value, so behavior is identical.
 *
 * Columns indexed: subject, bodyPlain, bodyPreview, fromJson, toJson, ccJson.
 * The `rowid` column is implicit and joins back to `messages.id` 1:1.
 */
@Entity(tableName = "messages_fts")
@Fts4(tokenizer = "unicode61")
data class MessageSearchEntity(
    val subject: String,
    val bodyPlain: String,
    val bodyPreview: String,
    val fromJson: String,
    val toJson: String,
    val ccJson: String
)
