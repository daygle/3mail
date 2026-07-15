package com.threemail.android.util

/**
 * Helpers for turning free-form user search text into a safe SQLite FTS4 MATCH
 * expression.
 *
 * SQLite FTS4 supports full-text search through the MATCH operator, but a single
 * stray `"` or dangling operator (`OR`, `AND`, `*`, `:`) triggers
 * `SQLITE_SYNTAX`. Room's parameter binding protects against SQL injection; the
 * purpose here is to keep the FTS *parser* happy.
 *
 * Strategy: split the input on whitespace, strip any internal double quotes, and
 * treat every remaining token as a quoted literal. SQLite then performs implicit
 * AND across tokens (no `OR`/`AND` operators ever reach the parser). Empty input
 * returns an empty string so the caller can short-circuit before hitting the DAO.
 */
object FtsUtil {

    /** Hard upper bound on what we'll pay to parse a user-typed query. */
    const val MAX_QUERY_LENGTH = 100

    fun sanitize(raw: String): String {
        val sanitized = raw
            .replace("\"", "")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"$it\"" }
        if (sanitized.length <= MAX_QUERY_LENGTH) return sanitized
        // Output exceeds the cap. Truncate to MAX_QUERY_LENGTH, then make sure
        // we still end on a valid closing quote so the FTS parser is happy.
        // - If the truncation landed exactly on a closing quote, we're done.
        // - If it landed mid-token (no space in the remainder), append `"` to
        //   close the lone token. The result may be MAX_QUERY_LENGTH + 1 chars
        //   in this one edge case, which is fine: the next pass sees the same
        //   string and produces the same string (idempotency preserved).
        // - If it landed between tokens, truncate to the last space and append
        //   `"` to close the final kept token.
        val truncated = sanitized.take(MAX_QUERY_LENGTH)
        if (truncated.endsWith("\"")) return truncated
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 0) truncated.substring(0, lastSpace) + "\""
        else truncated + "\""
    }
}
