package io.github.zzzyyylllty.sertraline.config.common

import java.io.File

/**
 * 以「文本插入」方式补写缺失的配置节点，已有内容一行都不重写。
 *
 * 背景：TabooLib 的配置写盘是 night-config 的全量往返（`YamlWriter` = `toNodeTree` + `snakeyaml.serialize`），
 * 而 night-config 的注释模型是「一个路径一个 String」，装不下注释块里的空行；
 * TabooLib 只能用 `comments.joinToString("\n")` 桥接，null 元素被字符串化成字面量 `null`，
 * 于是每个作为分隔的空行保存后都会变成 `# null`。
 *
 * 而配置自动更新的语义只是补写**缺失**节点——完全不需要改写已有文本。所以这里直接改文本：
 * - 已有行（值、注释、行内注释、缩进风格、键顺序）原样保留；
 * - 只把缺失的节点（含其注释）按推断出的缩进插入到正确的父节末尾；
 * - 父节不存在时，按层级新建缺失的中间节。
 *
 * 安全策略（宁可少写也不错写）：
 * - 制表符缩进的文件直接跳过（缩进推断不可靠，写错会产生重复键 → 配置非法）；
 * - 同名键在同一层级已存在、但它不是节（如 `a: 5`）、或要写的叶子已存在 → 跳过该节点；
 * - 无法定位父节时跳过该节点，绝不猜测位置。
 */
object ConfigTextUpdater {

    /**
     * @param presentPaths 已存在于配置中的点分路径（调用方用已加载的 config 判定，语义与 `config.contains` 一致）
     * @return 实际写入的节点数；0 表示未做任何改动（无需写入，或全部跳过）
     */
    fun appendMissingNodes(file: File, nodes: List<SConfigNode>, presentPaths: Set<String>): Int {
        if (!file.isFile) return 0

        val missing = nodes.filter { it.path !in presentPaths }
        if (missing.isEmpty()) return 0

        val original = file.readText()
        if (original.isBlank()) return 0
        // 制表符缩进：不猜，直接跳过
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

    // ------------------------------------------------------------------ 单节点插入

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

        // ---- 逐级定位已存在的祖先节 ----
        var rangeStart = 0
        var rangeEnd = contentEnd
        var parentIndent = -1
        var insertAt = contentEnd          // 顶层新增：追加到文件末尾
        var createFrom = 0                 // 从第几个组件起需要新建

        for (i in 0 until parts.size - 1) {
            val wantIndent = if (parentIndent < 0) 0 else parentIndent + step
            val idx = findSectionLine(lines, rangeStart, rangeEnd, parts[i], wantIndent)
            if (idx < 0) {
                // 同级已有同名键，但它不是节（例如 `a: 5`）→ 在这里建子节点会产生重复键
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

        // ---- 组装插入块：缺失的中间节 + 叶子 ----
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
        // 目标层级已有同名键 → 不重复插入（isPresent 判定偏差时的兜底）
        if (findAnyKeyLine(lines, rangeStart, rangeEnd, leafKey, indent) >= 0) return null

        node.comment.takeIf { it.isNotBlank() }
            ?.split('\n')
            ?.forEach { block += commentLine(indent, it) }
        block += renderValue(indent, leafKey, node.default, step)

        // ---- 拼接 ----
        val out = ArrayList<String>(lines.size + block.size + 2)
        out.addAll(lines.subList(0, insertAt))
        if (separate && insertAt > 0 && lines[insertAt - 1].isNotBlank()) out += ""
        out.addAll(block)
        out.addAll(lines.subList(insertAt, lines.size))
        return out.joinToString(nl)
    }

    // ------------------------------------------------------------------ 行扫描

    /** 行首空格数（文件含制表符缩进时本类整体不参与，见 [appendMissingNodes]）。 */
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

    /** 在 [from, to) 内找到缩进为 [indent] 的同名键行（不要求是节）。 */
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

    /** 同 [findAnyKeyLine]，但要求该行是「节」（冒号后为空或仅注释）。 */
    private fun findSectionLine(lines: List<String>, from: Int, to: Int, key: String, indent: Int): Int {
        val i = findAnyKeyLine(lines, from, to, key, indent)
        if (i < 0) return -1
        val trimmed = lines[i].trimStart(' ')
        val after = trimmed.substring(key.length).trimStart(' ').removePrefix(":")
        return if (after.isBlank() || after.trimStart().startsWith("#")) i else -1
    }

    /**
     * 节 [keyIdx] 的「内容末尾」下标（= 最后一个内容行的下一行）。
     * 扫描时跳过空行、在遇到缩进不深于本节的行时停止，因此插入点会落在尾部空行之前。
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

    /** 推断文件的缩进步长：取第一处「子键比父键更深」的差值，取不到就用 2。 */
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

    // ------------------------------------------------------------------ YAML 文本生成

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

    /** 可以不加引号的普通标量（避免把 `en_US`、`localhost` 这类写成 `"en_US"`）。 */
    private val PLAIN_SAFE = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-/]*$")

    /** YAML 里会被解析成别的类型的字面量，作为字符串必须加引号。 */
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
