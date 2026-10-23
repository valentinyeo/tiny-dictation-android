package com.tinydictation.android.transcription.models

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ArchiveExtractor {

    fun extractTarBz2(tarBz2File: File, targetDir: File) {
        targetDir.mkdirs()

        FileInputStream(tarBz2File).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tais ->
                        var entry: TarArchiveEntry? = tais.nextTarEntry
                        while (entry != null) {
                            val relativePath = if (entry.name.contains("/")) {
                                entry.name.substringAfter("/")
                            } else {
                                entry.name
                            }

                            if (relativePath.isNotEmpty()) {
                                val outputFile = File(targetDir, relativePath)

                                if (entry.isDirectory) {
                                    outputFile.mkdirs()
                                } else {
                                    outputFile.parentFile?.mkdirs()
                                    FileOutputStream(outputFile).use { fos ->
                                        tais.copyTo(fos)
                                    }
                                }
                            }
                            entry = tais.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    fun extractZip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)

                val relativePath = if (entry.name.contains("/")) {
                    entry.name.substringAfter("/")
                } else {
                    entry.name
                }

                if (relativePath.isEmpty()) {
                    entry = zis.nextEntry
                    continue
                }

                val outputFile = File(targetDir, relativePath)

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
