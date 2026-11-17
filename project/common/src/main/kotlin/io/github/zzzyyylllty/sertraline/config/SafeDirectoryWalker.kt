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
    if (!root.exists() || !root.isDirectory) {
        return emptyList()
    }

    val result = mutableListOf<File>()
    val visited = mutableSetOf<Path>()

    try {
        val rootPath = root.toPath()
        val options = if (followSymlinks) {
            arrayOf(FileVisitOption.FOLLOW_LINKS)
        } else {
            emptyArray()
        }

        Files.walkFileTree(rootPath, options.toSet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) {
                    val fileObj = file.toFile()
                    if (filter(fileObj)) {
                        result.add(fileObj)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Prevent cycles.
                if (dir in visited) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                visited.add(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                // Skip inaccessible files.
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: Exception) {
        // Fall back to a simple walk if Files.walkFileTree fails.
        fallbackWalkDirectory(root, maxDepth, 0, filter, result, visited.map { it.toString() }.toMutableSet())
    }

    // Files.walkFileTree / File.listFiles return entries in an OS-dependent order (directory
    // iteration order), so every run could produce a different sequence. Item registration order
    // is derived from this list, so an unstable walk makes item ids, command suggestions and GUI
    // paging differ between restarts. Sort by relative path to make load order deterministic.
    result.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePathString(root) })
    return result
}

/**
 * Relative path of this file against [root], falling back to the absolute path when the file is
 * not under [root] (keeps the comparison total even for odd inputs).
 */
private fun File.relativePathString(root: File): String {
    val base = try {
        root.canonicalPath
    } catch (e: Exception) {
        root.absolutePath
    }
    val self = try {
        canonicalPath
    } catch (e: Exception) {
        absolutePath
    }
    return if (self.startsWith(base)) self.removePrefix(base) else self
}

/**
 * Fallback directory walk used when Files.walkFileTree fails.
 */
private fun fallbackWalkDirectory(
    directory: File,
    maxDepth: Int,
    currentDepth: Int,
    filter: (File) -> Boolean,
    result: MutableList<File>,
    visitedPaths: MutableSet<String>
) {
    if (currentDepth >= maxDepth) {
        return
    }

    val canonicalPath = try {
        directory.canonicalPath
    } catch (e: Exception) {
        directory.absolutePath
    }

    if (canonicalPath in visitedPaths) {
        return
    }
    visitedPaths.add(canonicalPath)

    val files = directory.listFiles() ?: return
    for (file in files) {
        if (file.isDirectory) {
            fallbackWalkDirectory(file, maxDepth, currentDepth + 1, filter, result, visitedPaths)
        } else if (file.isFile && filter(file)) {
            result.add(file)
        }
    }
}

/**
 * Safely walks files in a directory (simplified version).
 */
fun safeListFiles(directory: File, maxDepth: Int = 10): List<File> {
    return safeWalkDirectory(directory, maxDepth) { it.isFile }
}

/**
 * Safely walks directories in a directory (simplified version).
 */
fun safeListDirectories(directory: File, maxDepth: Int = 10): List<File> {
    return safeWalkDirectory(directory, maxDepth) { it.isDirectory }
}
