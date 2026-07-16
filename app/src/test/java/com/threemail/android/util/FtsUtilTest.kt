package com.threemail.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsUtilTest {

    @Test
    fun `empty input returns empty match`() {
        assertEquals("", FtsUtil.sanitize(""))
        assertEquals("", FtsUtil.sanitize("   "))
        assertEquals("", FtsUtil.sanitize("\n\t  \n"))
    }

    @Test
    fun `single token is wrapped in quotes`() {
        assertEquals("\"hello\"", FtsUtil.sanitize("hello"))
    }

    @Test
    fun `multiple tokens become implicit AND`() {
        assertEquals("\"hello\" \"world\"", FtsUtil.sanitize("hello world"))
        assertEquals("\"hello\" \"world\"", FtsUtil.sanitize("  hello   world  "))
    }

    @Test
    fun `double quotes in input are stripped not escaped`() {
        // FTS4 phrase escapes by doubling quotes; we drop them outright to keep
        // the parser robust regardless of what the user types.
        assertEquals("\"foo\" \"bar\"", FtsUtil.sanitize("\"foo\" \"bar\""))
        assertEquals("\"unmatched\"", FtsUtil.sanitize("\"unmatched"))
    }

    @Test
    fun `fts reserved tokens are treated as literal search terms`() {
        // "OR 1=1" - naive input - gets wrapped as "OR" "1=1", which FTS4 treats
        // as two literal search terms rather than a boolean expression.
        assertEquals("\"OR\" \"1=1\"", FtsUtil.sanitize("OR 1=1"))
        assertEquals("\"foo\" \"OR\"", FtsUtil.sanitize("foo OR"))
        assertEquals("\"(crash-me*)\"", FtsUtil.sanitize("(crash-me*)"))
    }

    @Test
    fun `input is truncated to MAX_QUERY_LENGTH before processing`() {
        val longInput = "a".repeat(FtsUtil.MAX_QUERY_LENGTH + 50)
        val out = FtsUtil.sanitize(longInput)
        // 150 'a's truncate to (MAX_QUERY_LENGTH - 2) 'a's - that single
        // token, wrapped in quotes, is the expected output. The -2 leaves room
        // for the quote chars so the output is itself <= MAX_QUERY_LENGTH,
        // which is what makes the idempotency test hold.
        val expectedToken = "\"${"a".repeat(FtsUtil.MAX_QUERY_LENGTH - 2)}\""
        assertEquals(expectedToken, out)
    }

    @Test
    fun `truncation across a token boundary never dangles an unbalanced quote`() {
        // A long multi-token query whose sanitized form overflows MAX_QUERY_LENGTH
        // and whose truncation point lands after (or inside) a completed token.
        // The old code appended a stray closing quote to an already-balanced
        // prefix, producing e.g. `"ab" "ab""` which fails the FTS4 parser.
        val out = FtsUtil.sanitize((1..40).joinToString(" ") { "ab" })
        assertTrue("output overflowed the cap: $out", out.length <= FtsUtil.MAX_QUERY_LENGTH)
        assertEquals("unbalanced quotes in $out", 0, out.count { it == '"' } % 2)
        assertTrue("output ends mid-token in $out", out.isEmpty() || out.endsWith("\""))
        // Idempotency: re-sanitizing the output must be a fixed point.
        assertEquals(out, FtsUtil.sanitize(out))
    }

    @Test
    fun `email address survives sanitization`() {
        assertEquals("\"user@domain.com\"", FtsUtil.sanitize("user@domain.com"))
    }

    @Test
    fun `cjk and accented characters are preserved`() {
        assertEquals("\"CJK日本語\"", FtsUtil.sanitize("CJK日本語"))
        assertEquals("\"café\"", FtsUtil.sanitize("café"))
    }

    @Test
    fun `leading and trailing whitespace ignored`() {
        assertEquals("\"foo\"", FtsUtil.sanitize("   foo   "))
    }

    @Test
    fun `internal runs of whitespace collapse to a single space`() {
        assertEquals("\"a\" \"b\" \"c\"", FtsUtil.sanitize("a   b\t\nc"))
    }

    @Test
    fun `sanitize is idempotent - double application yields the same match expression`() {
        // The strongest property we can assert: pass the sanitizer any input,
        // then pass its OWN output through the sanitizer again. The second
        // pass must produce the identical string. This catches unbalanced
        // quotes, accidental double-escaping, and re-introduction of
        // non-AND tokens.
        val inputs = listOf(
            "hello",
            "hello world",
            "  split   on   whitespace  ",
            "\"matches\"",
            "\"unmatched",
            "OR",
            "foo OR",
            "(crash-me*)",
            "user@domain.com",
            "café",
            "日本語 mixed english",
            // Adversarial
            "\"\"\"foo\"\"",
            "a".repeat(FtsUtil.MAX_QUERY_LENGTH + 200)
        )
        for (raw in inputs) {
            val once = FtsUtil.sanitize(raw)
            val twice = FtsUtil.sanitize(once)
            assertEquals("Round-trip failed for input=$raw; once=$once twice=$twice", once, twice)
        }
    }
}
