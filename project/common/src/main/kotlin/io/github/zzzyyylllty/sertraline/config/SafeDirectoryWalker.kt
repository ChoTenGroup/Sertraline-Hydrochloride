package io.github.zzzyyylllty.sertraline.config

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * Safely walks a directory with a depth limit and symlink protection.
 *
 * @param root The root directory.
 * @param maxDepth The maximum depth (defaults to 10).
 * @param followSymlinks Whether to follow symbolic links (defaults to false).
 * @param filter The file filter.
 * @return The list of matching files.
 */
fun safeWalkDirectory(
    root: File,
    maxDepth: Int = 10,
    followSymlinks: Boolean = false,
    filter: (File) -> Boolean = { true }
): List<File> {
    val type = try {
        Files.readAttributes(root.toPath(), BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        return emptyList()
    }
    if (!type.isDirectory) throw NotDirectoryException(root.path)
    return walkDirectory(root, maxDepth, followSymlinks, directories = false, filter)
}

private fun walkDirectory(
    root: File,
    maxDepth: Int,
    followSymlinks: Boolean,
    directories: Boolean,
    filter: (File) -> Boolean
): List<File> {
    val base = root.toPath().toAbsolutePath().normalize()
    val result = mutableListOf<File>()
    val options = if (followSymlinks) setOf(FileVisitOption.FOLLOW_LINKS) else emptySet()
    Files.walkFileTree(base, options, maxDepth, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (directories && dir != base) add(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (if (directories) attrs.isDirectory else attrs.isRegularFile) add(file)
            return FileVisitResult.CONTINUE
        }

        private fun add(path: Path) {
            val file = path.toFile()
            if (filter(file)) result += file
        }
    })
    return result.sortedBy { base.relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
}

fun safeListFiles(directory: File, maxDepth: Int = 10): List<File> =
    safeWalkDirectory(directory, maxDepth)

fun safeListDirectories(directory: File, maxDepth: Int = 10): List<File> =
    if (directory.isDirectory) walkDirectory(directory, maxDepth, false, directories = true) { true } else emptyList()
