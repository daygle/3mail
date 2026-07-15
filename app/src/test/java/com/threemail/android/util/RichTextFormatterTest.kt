package com.threemail.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextFormatterTest {

    @Test
    fun `bold wraps selection`() {
        val result = RichTextFormatter.bold(TextEdit("hello", 0, 5))
        assertEquals("**hello**", result.text)
    }

    @Test
    fun `bold on empty selection inserts markers and places cursor between`() {
        val result = RichTextFormatter.bold(TextEdit("hello", 2, 2))
        assertEquals("he****llo", result.text)
        assertEquals(4, result.selectionStart)
    }

    @Test
    fun `italic wraps selection`() {
        assertEquals("_hi_", RichTextFormatter.italic(TextEdit("hi", 0, 2)).text)
    }

    @Test
    fun `bullet list prefixes each line`() {
        assertEquals("- a\n- b", RichTextFormatter.bulletList(TextEdit("a\nb", 0, 3)).text)
    }

    @Test
    fun `bullet list toggles off when all prefixed`() {
        assertEquals("a\nb", RichTextFormatter.bulletList(TextEdit("- a\n- b", 0, 7)).text)
    }

    @Test
    fun `numbered list numbers each line`() {
        assertEquals("1. a\n2. b", RichTextFormatter.numberedList(TextEdit("a\nb", 0, 3)).text)
    }

    @Test
    fun `link wraps selection as markdown`() {
        val result = RichTextFormatter.link(TextEdit("click here", 0, 5), "https://x.com")
        assertEquals("[click](https://x.com) here", result.text)
    }

    @Test
    fun `link with empty selection uses placeholder label`() {
        val result = RichTextFormatter.link(TextEdit("", 0, 0), "https://x.com")
        assertEquals("[link](https://x.com)", result.text)
    }
}
