package com.yaoyihan.nikonconnect

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/** Disposable previews only. Originals and user downloads never live in this directory. */
internal class ThumbnailDiskCache(private val root: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    private val epoch = AtomicLong()
    val generation: Long get() = epoch.get()
    fun invalidate() { epoch.incrementAndGet() }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, "$hash.jpg")
    }

    @Synchronized fun read(key: String): ByteArray? = runCatching {
        file(key).takeIf { it.isFile }?.let { it.setLastModified(System.currentTimeMillis()); it.readBytes() }
    }.getOrNull()

    @Synchronized fun write(key: String, bytes: ByteArray, expectedGeneration: Long) {
        if (expectedGeneration != generation || bytes.isEmpty() || bytes.size > maxBytes) return
        root.mkdirs()
        val target = file(key)
        val temp = File(root, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            target.setLastModified(System.currentTimeMillis())
            var total = sizeBytes()
            root.listFiles().orEmpty().filter { it.isFile && it.extension == "jpg" }
                .sortedBy { it.lastModified() }.forEach {
                    if (total > maxBytes) { val size = it.length(); if (it.delete()) total -= size }
                }
        } finally { temp.delete() }
    }

    @Synchronized fun sizeBytes(): Long = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    @Synchronized fun clear() {
        // invalidate() is also called before the asynchronous clear, preventing in-flight writers.
        invalidate()
        root.listFiles().orEmpty().filter { it.isFile }.forEach { check(it.delete()) { "Unable to clear thumbnail cache" } }
    }
}
