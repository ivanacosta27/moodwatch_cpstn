package com.example.moodwatch

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object Assets {
    fun ensureUnzipped(context: Context, assetZipPath: String, outDir: File) {
        if (outDir.exists() && outDir.list()?.isNotEmpty() == true) return
        outDir.mkdirs()
        context.assets.open(assetZipPath).use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(outDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}
