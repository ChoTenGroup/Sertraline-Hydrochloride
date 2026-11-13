package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.logger.severeSSync
import java.io.File
import java.util.zip.ZipFile

/**
 * Warns when the cached TabooLib modules and the reflex analyser disagree on the
 * ClassStructure signature. Upstream can republish an unchanged version string, and the
 * local caches then keep serving the old classes, so startup dies with NoSuchMethodError.
 */
object TabooLibCacheCheck {
    private const val LINKED = "()Ljava/util/LinkedList;"
    private const val FACTORY = "expansion/CustomTypeFactory.class"
    private const val STRUCTURE = "reflex/ClassStructure.class"

    /** Call early in onLoad(); the modules are installed during plugin construction. */
    fun check() {
        val root = serverRoot() ?: return

        // The loader rewrites its relocated jars on use, so the newest file is the active one.
        val relocated = File(root, "cache/taboolib").listFiles()
            .orEmpty().filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" } }
        val analyser = relocated.filter { it.name.startsWith("analyser-") }.newestOrNull() ?: return
        val analyserLinked = hasDescriptor(analyser, STRUCTURE, LINKED) ?: return

        // A stale module jar next to the active analyser is what breaks the class visitor.
        val module = File(root, "libraries/io/izzel/taboolib").listFiles()
            .orEmpty().filter { it.isDirectory && it.name.startsWith("database-ptc-object") }
            .flatMap { dir -> dir.walkTopDown().maxDepth(2).filter { it.isFile && it.extension == "jar" } }
            .newestOrNull()
            ?: relocated.filter { it.name.startsWith("database-ptc-object") }.newestOrNull()
            ?: return
        val moduleLinked = hasDescriptor(module, FACTORY, LINKED) ?: return
        if (moduleLinked == analyserLinked) return

        severeSSync(
            "Cached TabooLib modules do not match the bundled reflex analyser (ClassStructure " +
                "signature differs), so startup logs NoSuchMethodError and parts of TabooLib stay broken. " +
                "Stop the server, delete the 'libraries/io/izzel/taboolib' and 'cache/taboolib' folders " +
                "beside the server jar, then start it again to re-download them."
        )
        severeSSync("  module:   ${module.absolutePath}")
        severeSSync("  analyser: ${analyser.absolutePath}")
    }

    private fun List<File>.newestOrNull(): File? = maxByOrNull { it.lastModified() }

    /** dataFolder is still null during onLoad(), so fall back to the server's own directory. */
    private fun serverRoot(): File? {
        val root = Sertraline.dataFolder?.parentFile?.parentFile
            ?: runCatching { org.bukkit.Bukkit.getWorldContainer() }.getOrNull()
            ?: runCatching { System.getProperty("user.dir")?.let(::File) }.getOrNull()
            ?: return null
        // getWorldContainer() reports '.', which would print unusable relative paths.
        return runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
    }

    /** True when the class inside [jar] declares the LinkedList variant, null when absent. */
    private fun hasDescriptor(jar: File, entrySuffix: String, descriptor: String): Boolean? = try {
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(entrySuffix) }
            if (entry == null) null
            else zip.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1).contains(descriptor)
        }
    } catch (_: Throwable) {
        null
    }
}
