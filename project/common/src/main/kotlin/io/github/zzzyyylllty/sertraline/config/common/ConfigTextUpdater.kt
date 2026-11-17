package io.github.zzzyyylllty.sertraline.config.common

import java.io.File

/**
 * Adds missing configuration nodes by inserting text without rewriting any existing lines.
 *
 * Background: TabooLib writes configuration through a full night-config round-trip (`YamlWriter` = `toNodeTree` + `snakeyaml.serialize`).
 * night-config's comment model stores one String per path and cannot represent blank lines inside comment blocks.
 * TabooLib bridges the two with `comments.joinToString("\n")`, which converts null elements to the literal `null`,
 * causing every blank separator line to become `# null` when saved.
 *
 * Automatic configuration updates only need to add **missing** nodes, so existing text does not need to be rewritten:
 * - Preserve existing lines (values, comments, inline comments, indentation, and key order) as-is.
 * - Insert only missing nodes and their comments at the end of the correct parent section, using inferred indentation.
 * - Create missing intermediate sections when a parent section does not exist.
 *
 * Safety policy (prefer omitting a node over inserting it incorrectly):
 * - Skip files indented with tabs because indentation cannot be inferred reliably; an incorrect insertion could create duplicate keys and invalidate the configuration.
 * - Skip a node if a same-named key already exists at the same level but is not a section (for example, `a: 5`), or if the target leaf already exists.
 * - Skip a node if its parent section cannot be located; never guess its position.
 */
object ConfigTextUpdater {

    /**
     * @param presentPaths Dot-separated paths already in the configuration (determined by the caller using the loaded config, with semantics matching `config.contains`).
     * @return The number of nodes actually written; 0 means no changes were made (nothing was needed or all nodes were skipped).
     */
    fun appendMissingNodes(file: File, nodes: List<SConfigNode>, presentPaths: Set<String>): Int {
        if (!file.isFile) return 0

        val missing = nodes.filter { it.path !in presentPaths }
        if (missing.isEmpty()) return 0

        val original = file.readText()
        if (original.isBlank()) return 0
        // Do not guess indentation when tabs are used; skip the file.
        if (original.lineSequence().any { it.startsWith("\t") }) return 0

        val nl = if (original.contains("\r\n")) "\r\n" else "\n"
        val comments: Map<String, String> = nodes.associate { it.path to it.comment }

        var text = original
        var inserted = 0
        for (node in missing) {
            val updated = insertOne(text, node, comments, nl, separate = inserted == 0) ?: continue
            text = updated
            inserted++
        }
        if (inserted == 0) return 0

        if (!text.endsWith(nl)) text += nl
        file.writeText(text)
        return inserted
    }

    // ------------------------------------------------------------------ Single-node insertion

    private fun insertOne(
        text: String,
        node: SConfigNode,
        comments: Map<String, String>,
        nl: String,
        separate: Boolean,
    ): String? {
        val lines = text.split(nl)
        val trailingEmpty = lines.isNotEmpty() && lines.last().isEmpty()
        val contentEnd = if (trailingEmpty) lines.size - 1 else lines.size

        val parts = node.path.split('.')
        val step = detectIndentStep(lines)

        // ---- Locate existing ancestor sections level by level ----
        var rangeStart = 0
        var rangeEnd = contentEnd
        var parentIndent = -1
        var insertAt = contentEnd          // Top-level addition: append to the end of the file.
        var createFrom = 0                 // Index of the first component that needs to be created.

        for (i in 0 until parts.size - 1) {
            val wantIndent = if (parentIndent < 0) 0 else parentIndent + step
            val idx = findSectionLine(lines, rangeStart, rangeEnd, parts[i], wantIndent)
            if (idx < 0) {
                // A same-named key exists at this level but is not a section (for example, `a: 5`); adding a child here would create duplicate keys.
                if (findAnyKeyLine(lines, rangeStart, rangeEnd, parts[i], wantIndent) >= 0) return null
                createFrom = i
                break
            }
            parentIndent = indentOf(lines[idx])
            rangeStart = idx + 1
            rangeEnd = blockContentEnd(lines, idx, contentEnd)
            insertAt = rangeEnd
            createFrom = i + 1
        }

        // ---- Assemble the insertion block: missing intermediate sections plus the leaf ----
        val block = ArrayList<String>()
        var indent = if (parentIndent < 0) 0 else parentIndent + step
        for (i in createFrom until parts.size - 1) {
            comments[parts.take(i + 1).joinToString(".")]
                ?.takeIf { it.isNotBlank() }
                ?.split('\n')
                ?.forEach { block += commentLine(indent, it) }
            block += " ".repeat(indent) + parts[i] + ":"
            indent += step
        }

        val leafKey = parts.last()
        // Do not insert a same-named key that already exists at the target level (fallback for an inaccurate isPresent check).
        if (findAnyKeyLine(lines, rangeStart, rangeEnd, leafKey, indent) >= 0) return null

        node.comment.takeIf { it.isNotBlank() }
            ?.split('\n')
            ?.forEach { block += commentLine(indent, it) }
        block += renderValue(indent, leafKey, node.default, step)

        // ---- Concatenate ----
        val out = ArrayList<String>(lines.size + block.size + 2)
        out.addAll(lines.subList(0, insertAt))
        if (separate && insertAt > 0 && lines[insertAt - 1].isNotBlank()) out += ""
        out.addAll(block)
        out.addAll(lines.subList(insertAt, lines.size))
        return out.joinToString(nl)
    }

    // ------------------------------------------------------------------ Line scanning

    /** Number of leading spaces on a line (this class skips files containing tab indentation; see [appendMissingNodes]). */
    private fun indentOf(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    private fun matchesKey(trimmed: String, key: String): Boolean {
        if (!trimmed.startsWith(key)) return false
        val rest = trimmed.substring(key.length)
        return rest.startsWith(":") || rest.startsWith(" :")
    }

    /** Finds a line with the same key and [indent] indentation in [from, to); it does not have to be a section. */
    private fun findAnyKeyLine(lines: List<String>, from: Int, to: Int, key: String, indent: Int): Int {
        for (i in from until minOf(to, lines.size)) {
            val line = lines[i]
            if (line.isBlank()) continue
            val trimmed = line.trimStart(' ')
            if (trimmed.startsWith("#")) continue
            if (indentOf(line) != indent) continue
            if (matchesKey(trimmed, key)) return i
        }
        return -1
    }

    /** Same as [findAnyKeyLine], but requires the line to be a section (nothing or only a comment after the colon). */
    private fun findSectionLine(lines: List<String>, from: Int, to: Int, key: String, indent: Int): Int {
        val i = findAnyKeyLine(lines, from, to, key, indent)
        if (i < 0) return -1
        val trimmed = lines[i].trimStart(' ')
        val after = trimmed.substring(key.length).trimStart(' ').removePrefix(":")
        return if (after.isBlank() || after.trimStart().startsWith("#")) i else -1
    }

    /**
     * Index of the end of section [keyIdx]'s content (the line after its last content line).
     * Skips blank lines while scanning and stops at a line with indentation no deeper than the section, so insertion occurs before trailing blank lines.
     */
    private fun blockContentEnd(lines: List<String>, keyIdx: Int, limit: Int): Int {
        val keyIndent = indentOf(lines[keyIdx])
        var last = keyIdx
        var i = keyIdx + 1
        while (i < limit) {
            val line = lines[i]
            if (line.isNotBlank()) {
                if (indentOf(line) <= keyIndent) break
                last = i
            }
            i++
        }
        return last + 1
    }

    /** Infers the indentation step from the first child key that is deeper than its parent; defaults to 2 if none is found. */
    private fun detectIndentStep(lines: List<String>): Int {
        var prevIndent = -1
        for (line in lines) {
            if (line.isBlank()) continue
            val trimmed = line.trimStart(' ')
            if (trimmed.startsWith("#")) continue
            if (!trimmed.contains(':')) continue
            val indent = indentOf(line)
            if (prevIndent >= 0 && indent > prevIndent) return (indent - prevIndent).coerceAtLeast(1)
            prevIndent = indent
        }
        return 2
    }

    // ------------------------------------------------------------------ YAML text generation

    private fun commentLine(indent: Int, content: String): String {
        val pad = " ".repeat(indent)
        val body = content.trimEnd()
        return if (body.isBlank()) "$pad#" else "$pad# $body"
    }

    private fun renderValue(indent: Int, key: String, value: Any?, step: Int): List<String> {
        val pad = " ".repeat(indent)
        return when (value) {
            is List<*> -> buildList {
                add("$pad$key:")
                for (item in value) add(" ".repeat(indent + step) + "- " + scalar(item))
            }
            is Map<*, *> -> buildList {
                add("$pad$key:")
                for ((k, v) in value) addAll(renderValue(indent + step, k.toString(), v, step))
            }
            else -> listOf("$pad$key: " + scalar(value))
        }
    }

    /** Plain scalars that do not need quotes (avoids writing values such as `en_US` and `localhost` as `"en_US"`). */
    private val PLAIN_SAFE = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-/]*$")

    /** Literals that YAML would parse as another type and must therefore be quoted when used as strings. */
    private val RESERVED = setOf("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~")

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quoteIfNeeded(value)
        else -> quoteIfNeeded(value.toString())
    }

    private fun quoteIfNeeded(s: String): String {
        val plain = PLAIN_SAFE.matches(s) &&
            s.lowercase() !in RESERVED &&
            s.toDoubleOrNull() == null &&
            s.toLongOrNull() == null
        if (plain) return s
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
