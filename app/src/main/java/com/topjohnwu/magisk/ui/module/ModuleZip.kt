package com.topjohnwu.magisk.ui.module

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val MAX_UNPACKED = 120L * 1024L * 1024L

fun normalizeModuleZip(source: File, dest: File) {
    ZipFile(source).use { zip ->
        val entries = zip.entries().toList().filter { !it.isDirectory }
        if (entries.isEmpty()) throw IOException("empty zip")
        val names = entries.map { it.name.replace('\\', '/') }
        val hasRootProp = names.any { it == "module.prop" }
        val prefix = if (hasRootProp) {
            ""
        } else {
            val tops = names.map { it.substringBefore('/') }.distinct()
            if (tops.size == 1 && names.any { it == "${tops[0]}/module.prop" }) "${tops[0]}/"
            else throw IOException("module.prop missing")
        }
        val hasInstaller = names.any { it.removePrefix(prefix) == "META-INF/com/google/android/update-binary" }
        if (!hasInstaller) throw IOException("update-binary missing")
        if (prefix.isEmpty()) {
            if (source.absolutePath != dest.absolutePath)
                source.copyTo(dest, overwrite = true)
            return
        }
        var unpacked = 0L
        ZipOutputStream(BufferedOutputStream(dest.outputStream())).use { out ->
            for (entry in entries) {
                val name = entry.name.replace('\\', '/')
                if (!name.startsWith(prefix)) continue
                val rel = name.removePrefix(prefix)
                if (rel.isEmpty() || rel.startsWith("/") || rel.split('/').any { it == ".." }) continue
                val next = ZipEntry(rel)
                out.putNextEntry(next)
                zip.getInputStream(entry).use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        unpacked += n
                        if (unpacked > MAX_UNPACKED) throw IOException("zip too large")
                        out.write(buf, 0, n)
                    }
                }
                out.closeEntry()
            }
        }
    }
    ZipFile(dest).use { zip ->
        if (zip.getEntry("module.prop") == null ||
            zip.getEntry("META-INF/com/google/android/update-binary") == null
        ) throw IOException("normalized zip is not a module")
    }
}
