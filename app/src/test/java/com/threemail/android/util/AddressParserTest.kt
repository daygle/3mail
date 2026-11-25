package com.threemail.android.util

import com.threemail.android.domain.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {

    @Test
    fun `parses plain comma separated addresses`() {
        val result = AddressParser.parse("a@x.com, b@y.com")
        assertEquals(2, result.size)
        assertEquals("a@x.com", result[0].address)
        assertEquals("b@y.com", result[1].address)
    }

    @Test
    fun `parses named addresses`() {
        val result = AddressParser.parse("Jane Doe <jane@x.com>; \"Bob\" <bob@y.com>")
        assertEquals("Jane Doe", result[0].name)
        assertEquals("jane@x.com", result[0].address)
        assertEquals("Bob", result[1].name)
        assertEquals("bob@y.com", result[1].address)
    }

    @Test
    fun `quoted names keep separator characters intact`() {
        val result = AddressParser.parse("\"Doe, Jane\" <jane@x.com>; \"Smith; John\" <john@y.com>")
        assertEquals(2, result.size)
        assertEquals("Doe, Jane", result[0].name)
        assertEquals("jane@x.com", result[0].address)
        assertEquals("Smith; John", result[1].name)
        assertEquals("john@y.com", result[1].address)
    }

    @Test
    fun `escaped quotes inside a quoted name are unescaped`() {
        val result = AddressParser.parse("\"Jane \\\"JJ\\\" Doe\" <jane@x.com>, bob@y.com")
        assertEquals(2, result.size)
        assertEquals("Jane \"JJ\" Doe", result[0].name)
        assertEquals("bob@y.com", result[1].address)
    }

    @Test
    fun `newline separated quoted names stay intact`() {
        val result = AddressParser.parse("\"Doe, Jane\" <jane@x.com>\nbob@y.com")
        assertEquals(2, result.size)
        assertEquals("Doe, Jane", result[0].name)
        assertEquals("bob@y.com", result[1].address)
    }

    @Test
    fun `ignores blank tokens`() {
        assertEquals(1, AddressParser.parse(" , a@x.com , ").size)
    }

    @Test
    fun `formats round trip`() {
        val addrs = listOf(EmailAddress("Jane", "jane@x.com"), EmailAddress(address = "bob@y.com"))
        assertEquals("Jane <jane@x.com>, bob@y.com", AddressParser.format(addrs))
    }

    @Test
    fun `validates addresses`() {
        assertTrue(AddressParser.isValid("a@b.com"))
        assertFalse(AddressParser.isValid("not-an-email"))
        assertFalse(AddressParser.isValid("a@b"))
    }
}
