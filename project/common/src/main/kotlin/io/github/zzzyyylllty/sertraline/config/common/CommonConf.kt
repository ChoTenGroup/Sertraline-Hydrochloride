package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * 单个配置节点：路径 / 默认值 / 注释。
 * 注册表驱动配置文件生成与自动更新（缺失节点在加载时自动补写并附带注释）。
 */
class SConfigNode(
    val path: String,
    val default: Any,
    val comment: String = "",
)

/**
 * 单条配置迁移：把配置从 v(N-1) 升到 vN。
 *
 * 迁移脚本直接操作 [Configuration]，可配合下方 `migrate*` 扩展函数完成
 * 结构变更（重命名/移动/删除/改默认值）。版本由 [SConfigSpec.upgrade] 按序驱动。
 */
fun interface ConfigMigration {
    fun migrate(config: Configuration)
}

/** 仅当节点缺失时写入默认值（不覆盖用户已有值）。 */
fun Configuration.migrateSetDefault(path: String, value: Any?) {
    if (!contains(path)) set(path, value)
}

/** 节点重命名：存在时复制旧值到新路径并删除旧路径。 */
fun Configuration.migrateRename(oldPath: String, newPath: String) {
    val v = get(oldPath) ?: return
    set(newPath, v)
    set(oldPath, null)
}

/** 删除节点（null 值触发 nightconfig 移除）。 */
fun Configuration.migrateRemove(path: String) {
    set(path, null)
}

/**
 * 被 TabooLib 的注释往返污染的空行：整行只有 `# null`。
 *
 * 成因（TabooLib，非本插件）：
 * - `YamlCommentLoader.getCommentLines` 把注释块里的空行表示为 **null**
 *   （`CommentType.BLANK_LINE -> lines.add(null)`）；
 * - `ConfigSection.setComments` 用 `comments.joinToString("\n")` 落盘，
 *   而 `joinToString` 会把 null 元素字符串化成字面量 `null`；
 * - 于是配置文件里每个用来分隔的空行，保存后都会变成 `# null`。
 *
 * TabooLib 是运行时注入的依赖，插件无法直接修补，因此在保存后把这类被污染的行还原为空行。
 * 只匹配「整行仅有 # null」，不会误伤用户自己写的注释。
 */
private val MANGLED_NULL_COMMENT = Regex("^[ \\t]*# null[ \\t]*$", RegexOption.MULTILINE)

/** 见 [MANGLED_NULL_COMMENT]。文件内容未变化时不写盘。 */
private fun File.repairMangledCommentLines() {
    if (!isFile) return
    val text = readText()
    val repaired = text.replace(MANGLED_NULL_COMMENT, "")
    if (repaired != text) {
        writeText(repaired)
    }
}

/**
 * 一个配置文件的规格：文件名 + 节点注册表 + 目标 schema 版本 + 迁移表。
 * 通过 [bind] 加载并自动升级，[sync] 完成缺失节点补写。
 */
class SConfigSpec(
    val fileName: String,
    val nodes: List<SConfigNode>,
    val version: Int = 1,
    val migrations: Map<Int, ConfigMigration> = emptyMap(),
) {
    lateinit var config: Configuration
        private set

    val file: File get() = File(getDataFolder(), fileName)

    /** 从磁盘加载（不存在时释放内建资源），随后版本迁移 + 缺失节点补写。返回是否发生了改动。 */
    fun bind(): Boolean {
        if (!file.exists()) {
            releaseResourceFile(fileName, true)
        }
        config = Configuration.loadFromFile(file)
        val upgraded = upgrade()
        val synced = sync()
        // 即使本轮无需迁移/补写，也顺手清理历史版本落盘时写坏的 `# null` 注释行
        file.repairMangledCommentLines()
        return upgraded || synced
    }

    /** 保存配置，并把 TabooLib 落盘时被字符串化的空注释行还原（见 [MANGLED_NULL_COMMENT]）。 */
    private fun saveRepaired() {
        config.saveToFile()
        file.repairMangledCommentLines()
    }

    /** 读文件内 config-version（缺失视为 0：即未纳入版本系统前的任何旧文件）。 */
    private fun readVersion(): Int = config.getInt("config-version", 0)

    /**
     * 版本迁移：文件版本 < [version] 时，按序应用 `migrations[target]`（2..version）。
     * 完成后把 config-version 写回**最后成功应用**的版本并保存，保证迁移幂等可重试。
     */
    fun upgrade(): Boolean {
        val current = readVersion()
        if (current >= version) return false
        var applied = current
        for (target in (current + 1)..version) {
            migrations[target]?.let { m ->
                try {
                    m.migrate(config)
                } catch (e: Exception) {
                    // 停在失败版本；config-version 写回最后成功版本，下次启动重试失败的迁移
                    warningL("Config_Load_Error_Parse", fileName, "migration v$target: ${e.message}")
                    break
                }
            }
            applied = target
        }
        if (applied != current) {
            config.set("config-version", applied)
            saveRepaired()
            return true
        }
        return false
    }

    /**
     * 配置自动更新：把注册表中文件缺失的节点补写到磁盘。
     *
     * 这里走「文本插入」（[ConfigTextUpdater]），而不是 night-config 的 `set` + `saveToFile` 全量往返：
     * 往返会重写整个文件，而 night-config 的注释模型（一个路径一个 String）装不下注释块里的空行，
     * 每个分隔空行都会被写成 `# null`，还会顺带把 config-version 挪到文件末尾、丢掉头部注释。
     * 补写只涉及新增节点，不需要改动任何已有文本，所以文本插入能把这些原样保住。
     */
    fun sync(): Boolean {
        val present = nodes.filter { config.contains(it.path) }.map { it.path }.toSet()
        val missingCount = nodes.size - present.size
        if (missingCount <= 0) return false

        val written = try {
            ConfigTextUpdater.appendMissingNodes(file, nodes, present)
        } catch (e: Exception) {
            severeL("Config_Load_Error_Parse", fileName, e.message ?: "Unknown error")
            0
        }
        if (written <= 0) return false

        if (written < missingCount) {
            warningL(
                "Config_Load_Error_Parse", fileName,
                "$written/$missingCount missing node(s) written; the rest were skipped (unsupported file layout)"
            )
        }

        // 磁盘已更新：重新载入内存副本，后续读路径（SertralineSettings）能看到新节点
        try {
            config.reload()
        } catch (e: Exception) {
            warningL("Config_Load_Error_Parse", fileName, "reload after sync failed: ${e.message}")
        }
        return true
    }
}
