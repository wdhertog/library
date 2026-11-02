package nl.wdhertog.library.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CSV en TSV, zoals Excel en andere apps ze maken: scheidingsteken (; , of tab) en
 * tekencodering (UTF-8, anders Windows-1252) worden automatisch herkend.
 */
object CsvReader {

    fun read(bytes: ByteArray): Table {
        val text = decode(bytes).removePrefix("﻿")
        return tableOf(parse(text, detectDelimiter(text)))
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }

    /** Het teken dat in de eerste regel (buiten aanhalingstekens) het vaakst voorkomt. */
    internal fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(';' to 0, ',' to 0, '\t' to 0)
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> quoted = !quoted
                !quoted && (c == '\n' || c == '\r') -> break
                !quoted && c in counts -> counts[c] = counts.getValue(c) + 1
            }
        }
        return counts.maxBy { it.value }.takeIf { it.value > 0 }?.key ?: ','
    }

    /** RFC 4180: velden tussen aanhalingstekens mogen scheidingstekens, regeleinden en "" bevatten. */
    internal fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"' && text.getOrNull(i + 1) == '"') {
                    field.append('"')
                    i++
                } else if (c == '"') {
                    quoted = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    delimiter -> {
                        row += field.toString()
                        field.clear()
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row += field.toString()
                        field.clear()
                        rows += row
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
