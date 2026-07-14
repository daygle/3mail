package com.threemail.android.util

/** A text value plus a selection range, mirroring Compose's TextFieldValue. */
data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * Applies Markdown formatting to the selected range of a text field. Pure logic
 * so the composer toolbar behaviour can be unit-tested without Compose.
 */
object RichTextFormatter {

    fun bold(edit: TextEdit): TextEdit = wrap(edit, "**", "**")
    fun italic(edit: TextEdit): TextEdit = wrap(edit, "_", "_")

    fun link(edit: TextEdit, url: String): TextEdit {
        val (start, end) = ordered(edit)
        val label = edit.text.substring(start, end).ifEmpty { "link" }
        val replacement = "[$label]($url)"
        val newText = edit.text.replaceRange(start, end, replacement)
        val cursor = start + replacement.length
        return TextEdit(newText, cursor, cursor)
    }

    /** Prefixes each selected line with "- " (toggles off if all already bulleted). */
    fun bulletList(edit: TextEdit): TextEdit = linePrefix(edit, "- ")

    /** Prefixes selected lines with "1. ", "2. ", … */
    fun numberedList(edit: TextEdit): TextEdit {
        val (start, end) = ordered(edit)
        val lineStart = edit.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = edit.text.indexOf('\n', end).let { if (it < 0) edit.text.length else it }
        val block = edit.text.substring(lineStart, lineEnd)
        val numbered = block.split("\n").mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")
        val newText = edit.text.replaceRange(lineStart, lineEnd, numbered)
        return TextEdit(newText, lineStart, lineStart + numbered.length)
    }

    private fun wrap(edit: TextEdit, prefix: String, suffix: String): TextEdit {
        val (start, end) = ordered(edit)
        val selected = edit.text.substring(start, end)
        val replacement = "$prefix$selected$suffix"
        val newText = edit.text.replaceRange(start, end, replacement)
        return if (selected.isEmpty()) {
            val cursor = start + prefix.length
            TextEdit(newText, cursor, cursor)
        } else {
            TextEdit(newText, start, start + replacement.length)
        }
    }

    private fun linePrefix(edit: TextEdit, prefix: String): TextEdit {
        val (start, end) = ordered(edit)
        val lineStart = edit.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = edit.text.indexOf('\n', end).let { if (it < 0) edit.text.length else it }
        val block = edit.text.substring(lineStart, lineEnd)
        val lines = block.split("\n")
        val allPrefixed = lines.all { it.startsWith(prefix) }
        val updated = if (allPrefixed) {
            lines.joinToString("\n") { it.removePrefix(prefix) }
        } else {
            lines.joinToString("\n") { prefix + it }
        }
        val newText = edit.text.replaceRange(lineStart, lineEnd, updated)
        return TextEdit(newText, lineStart, lineStart + updated.length)
    }

    private fun ordered(edit: TextEdit): Pair<Int, Int> {
        val a = edit.selectionStart.coerceIn(0, edit.text.length)
        val b = edit.selectionEnd.coerceIn(0, edit.text.length)
        return minOf(a, b) to maxOf(a, b)
    }
}
