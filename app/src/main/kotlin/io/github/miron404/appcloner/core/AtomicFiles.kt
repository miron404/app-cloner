package io.github.miron404.appcloner.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Replaces this file's contents so that, whatever happens part way, it holds either the old bytes
 * or the new ones.
 *
 * Renaming a temporary file over the target is only half of that. The rename can reach the disk
 * before the data does, and a power cut in between leaves a file with the right name and nothing in
 * it — on f2fs and ext4 alike. Syncing the temporary file before the rename closes that gap, and
 * syncing the directory after it makes the rename itself durable before anything that depends on it
 * happens next.
 *
 * There is deliberately no copying fallback when the rename fails: a copy is exactly the
 * half-written state this exists to rule out, so the old contents are kept and the failure is
 * reported instead.
 */
fun File.writeBytesAtomically(bytes: ByteArray) {
    val directory = absoluteFile.parentFile
    val temporary = File(directory, "$name.tmp")
    FileOutputStream(temporary).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!temporary.renameTo(this)) {
        temporary.delete()
        throw IOException("Could not replace $name")
    }
    directory.syncDirectory()
}

/**
 * Makes the renames and deletions already done in this directory durable.
 *
 * Best effort: not every file system lets a directory be opened for this, and where it cannot be
 * done there is nothing better on offer — the data inside each file has already been synced.
 */
fun File.syncDirectory() {
    runCatching {
        FileChannel.open(toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
