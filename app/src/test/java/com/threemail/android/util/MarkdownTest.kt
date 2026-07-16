package com.threemail.android.util

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `bold renders strong`() {
        assertTrue(Markdown.toHtml("Hello **world**").contains("<strong>world</strong>"))
    }

    @Test
    fun `italic renders em`() {
        assertTrue(Markdown.toHtml("_hi_").contains("<em>hi</em>"))
    }

    @Test
    fun `bullet list renders ul`() {
        val html = Markdown.toHtml("- a\n- b")
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>a</li>"))
        assertTrue(html.contains("<li>b</li>"))
    }

    @Test
    fun `numbered list renders ol`() {
        val html = Markdown.toHtml("1. a\n2. b")
        assertTrue(html.contains("<ol>"))
        assertTrue(html.contains("<li>a</li>"))
    }

    @Test
    fun `link renders anchor`() {
        assertTrue(Markdown.toHtml("[Google](https://g.com)").contains("<a href=\"https://g.com\">Google</a>"))
    }

    @Test
    fun `html special chars are escaped`() {
        val html = Markdown.toHtml("a < b & c")
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
    }

    @Test
    fun `image markdown renders img tag`() {
        val html = Markdown.toHtml("![alt text](https://example.com/x.png)")
        assertTrue(html.contains("<img src=\"https://example.com/x.png\""))
        assertTrue(html.contains("alt=\"alt text\""))
        // Image syntax must NOT be rendered as a regular link.
        assertTrue(!html.contains("<a "))
    }

    @Test
    fun `image with cid source keeps the cid scheme`() {
        val html = Markdown.toHtml("![img](cid:abc123@3mail)")
        assertTrue(html.contains("<img src=\"cid:abc123@3mail\""))
        // cid: must not be repointed to https://cid:
        assertTrue(!html.contains("src=\"https://cid:"))
    }
}
