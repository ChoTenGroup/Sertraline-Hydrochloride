package io.github.zzzyyylllty.sertraline.config.common

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Writes beside the destination so replacement stays on the same filesystem. */
internal fun File.writeConfigAtomically(text: String, backup: Boolean = false) {
    val target = toPath().toAbsolutePath()
    if (backup && Files.isRegularFile(target)) {
        replaceFile(target.resolveSibling(target.fileName.toString() + ".bak"), Files.readAllBytes(target))
    }
    replaceFile(target, text.toByteArray(Charsets.UTF_8))
}

private fun replaceFile(target: Path, bytes: ByteArray) {
    val temp = Files.createTempFile(target.parent, "." + target.fileName.toString(), ".tmp")
    try {
        FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
            val contents = ByteBuffer.wrap(bytes)
            while (contents.hasRemaining()) channel.write(contents)
            channel.force(true)
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}
