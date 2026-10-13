package com.threemail.android.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `plain names pass through`() {
        assertEquals("report.pdf", SafeFileName.sanitize("report.pdf"))
    }

    @Test
    fun `path components are stripped`() {
        assertEquals("x.xml", SafeFileName.sanitize("../../shared_prefs/x.xml"))
        assertEquals("evil.exe", SafeFileName.sanitize("..\\..\\evil.exe"))
        assertEquals("passwd", SafeFileName.sanitize("/etc/passwd"))
    }

    @Test
    fun `traversal and empty names fall back`() {
        assertEquals("file", SafeFileName.sanitize(".."))
        assertEquals("file", SafeFileName.sanitize("."))
        assertEquals("file", SafeFileName.sanitize("dir/"))
        assertEquals("attachment", SafeFileName.sanitize(null, fallback = "attachment"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("a.txt", SafeFileName.sanitize("a\u0000.txt"))
    }

    @Test
    fun `resolveInside keeps the file in the target directory`() {
        val dir = tmp.newFolder("attachments")
        val file = SafeFileName.resolveInside(dir, "../../shared_prefs/x.xml")
        assertEquals(dir.canonicalFile, file.parentFile)
        assertEquals("x.xml", file.name)
    }
}
