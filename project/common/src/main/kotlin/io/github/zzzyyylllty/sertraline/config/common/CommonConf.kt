package io.github.zzzyyylllty.sertraline.config.common

import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * A single configuration node: path, default value, and comment.
 * Generates and automatically updates configuration files from a registry, adding missing nodes and their comments during loading.
 */
class SConfigNode(
    val path: String,
    val default: Any,
    val comment: String = "",
)

/**
 * A single configuration migration, upgrading a configuration from v(N-1) to vN.
 *
 * Migration scripts operate directly on [Configuration] and can use the `migrate*` extension functions below
 * to make structural changes (rename, move, delete, or change a default value). [SConfigSpec.upgrade] applies migrations in order.
 */
fun interface ConfigMigration {
    fun migrate(config: Configuration)
}

/** Writes the default value only when the node is missing; does not overwrite existing user values. */
fun Configuration.migrateSetDefault(path: String, value: Any?) {
    if (!contains(path)) set(path, value)
}

/** Renames a node by copying its value to the new path and deleting the old path when it exists. */
fun Configuration.migrateRename(oldPath: String, newPath: String) {
    val v = get(oldPath) ?: return
    set(newPath, v)
    set(oldPath, null)
}

/** Deletes a node (a null value causes nightconfig to remove it). */
fun Configuration.migrateRemove(path: String) {
    set(path, null)
}

/**
 * Blank lines corrupted by TabooLib's comment round-trip, where the entire line contains only `# null`.
 *
 * Cause (in TabooLib, not this plugin):
 * - `YamlCommentLoader.getCommentLines` represents blank lines in comment blocks as **null**.
 *   （`CommentType.BLANK_LINE -> lines.add(null)`）；
 * - `ConfigSection.setComments` writes comments using `comments.joinToString("\n")`,
 *   and `joinToString` converts null elements into the literal string `null`.
 * - As a result, every blank separator line in the configuration becomes `# null` when saved.
 *
 * TabooLib is injected at runtime and cannot be patched directly by the plugin, so restore these corrupted lines to blank lines after saving.
 * Match only lines containing exactly `# null` to avoid affecting user-written comments.
 */
private val MANGLED_NULL_COMMENT = Regex("^[ \\t]*# null[ \\t]*$", RegexOption.MULTILINE)

/** See [MANGLED_NULL_COMMENT]. Does not write the file when its contents have not changed. */
private fun File.repairMangledCommentLines() {
    if (!isFile) return
    val text = readText()
    val repaired = text.replace(MANGLED_NULL_COMMENT, "")
    if (repaired != text) {
        writeConfigAtomically(repaired, backup = true)
    }
}

/**
 * Specification for one configuration file: filename, node registry, target schema version, and migration table.
 * [bind] loads and upgrades the file; [sync] adds missing nodes.
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

    /** Loads from disk (releasing the bundled resource if absent), then migrates the version and adds missing nodes. Returns whether anything changed. */
    fun bind(): Boolean = bind(recoverBackup = true)

    private fun bind(recoverBackup: Boolean): Boolean {
        if (!file.exists()) {
            releaseResourceFile(fileName, true)
        }
        config = try {
            ConfigReloadGuard.parse(file)
        } catch (failure: Exception) {
            val backup = File(file.parentFile, file.name + ".bak")
            if (!recoverBackup || !backup.isFile) throw failure
            val recovered = ConfigReloadGuard.parse(backup)
            file.writeConfigAtomically(backup.readText())
            recovered.file = file
            warningL("Config_Reload_Recovered", fileName)
            recovered
        }
        val upgraded = upgrade()
        val synced = sync()
        // Clean up `# null` comment lines corrupted by older releases even when this pass needs no migration or node additions.
        file.repairMangledCommentLines()
        return upgraded || synced
    }

    internal fun prepare(): SConfigSpec =
        SConfigSpec(fileName, nodes, version, migrations).also { it.bind(recoverBackup = false) }

    /** Saves the configuration and restores blank comment lines that TabooLib serialized as strings (see [MANGLED_NULL_COMMENT]). */
    private fun saveRepaired() {
        val repaired = config.saveToString().replace(MANGLED_NULL_COMMENT, "")
        file.writeConfigAtomically(repaired, backup = true)
    }

    /** Reads config-version from the file (missing means 0, for files from before versioning was introduced). */
    private fun readVersion(): Int = config.getInt("config-version", 0)

    /**
     * Migrates the file when its version is less than [version], applying `migrations[target]` in order (2..version).
     * Writes and saves the **last successfully applied** version afterward, making migrations idempotent and retryable.
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
                    // Stop at the failed migration; config-version remains at the last successful version so startup can retry it.
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
     * Automatically updates configuration files by writing registry nodes that are missing from disk.
     *
     * Use text insertion ([ConfigTextUpdater]) instead of a full night-config `set` + `saveToFile` round-trip:
     * a round-trip rewrites the whole file, and night-config's comment model (one String per path) cannot represent blank lines in comment blocks.
     * Each blank separator would become `# null`, config-version would move to the end of the file, and header comments would be lost.
     * Only missing nodes need to be added, so text insertion preserves all existing text as-is.
     */
    fun sync(): Boolean {
        val present = nodes.filter { config.contains(it.path) }.map { it.path }.toSet()
        val missingCount = nodes.size - present.size
        if (missingCount <= 0) return false

        val written = try {
            ConfigTextUpdater.appendMissingNodes(file, nodes, present)
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not synchronize $fileName", e)
        }
        if (written <= 0) return false

        if (written < missingCount) {
            warningL(
                "Config_Load_Error_Parse", fileName,
                "$written/$missingCount missing node(s) written; the rest were skipped (unsupported file layout)"
            )
        }

        // The disk file has changed; reload the in-memory copy so subsequent reads through SertralineSettings see the new nodes.
        check(ConfigReloadGuard.tryReload(config, fileName)) { "Could not reload $fileName after sync" }
        return true
    }
}
