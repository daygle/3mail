package com.threemail.android.util

import java.io.File
import java.io.IOException

/**
 * Helpers for turning an untrusted file name (an email attachment's
 * `Content-Disposition` filename, a ContentProvider display name) into a
 * single path segment that can't escape the directory it's written into.
 */
object SafeFileName {

    /**
     * Strip any path components and reject traversal segments ("." / "..")
     * so a name like `../../shared_prefs/x.xml` can't climb out of the target
     * directory. Pure string ops (no File(name)) so we never build a File
     * from the raw, unsanitized value. Falls back to [fallback] when nothing
     * usable is left.
     */
    fun sanitize(name: String?, fallback: String = "file"): String {
        val cleaned = (name ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    /**
     * Resolve [name] (sanitized via [sanitize]) inside [dir], and verify the
     * canonical result is still a direct child of [dir] - a belt-and-braces
     * check against symlinks or any platform-specific separator the string
     * sanitizer didn't anticipate.
     */
    fun resolveInside(dir: File, name: String?, fallback: String = "file"): File {
        val canonicalDir = dir.canonicalFile
        val file = File(canonicalDir, sanitize(name, fallback)).canonicalFile
        if (file.parentFile != canonicalDir) {
            throw IOException("Refusing to write outside ${canonicalDir.path}")
        }
        return file
    }
}
