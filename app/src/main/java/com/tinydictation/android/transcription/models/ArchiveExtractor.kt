package com.tinydictation.android.transcription.models

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ArchiveExtractor {

    fun extractTarBz2(tarBz2File: File, targetDir: File) {
        targetDir.mkdirs()

        FileInputStream(tarBz2File).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tais ->
                        var entry: TarArchiveEntry? = tais.nextTarEntry
                        while (entry != null) {
                            require(!entry.isSymbolicLink && !entry.isLink &&
                                (entry.isDirectory || entry.isFile)) {
                                "Unsupported model archive entry type"
                            }
                            val outputFile = entryFile(targetDir, entry.name, entry.isDirectory)

                            if (outputFile != null) {
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

        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isUnixSymlink) { "Unsupported model archive entry type" }
                val outputFile = entryFile(targetDir, entry.name, entry.isDirectory) ?: continue

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun entryFile(targetDir: File, name: String, isDirectory: Boolean): File? {
        val normalizedName = name.replace('\\', '/')
        val segments = normalizedName.split('/')
        require(!File(normalizedName).isAbsolute && !normalizedName.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalizedName) &&
            segments.none { it == ".." }) {
            "Invalid model archive entry name"
        }
        val relativePath = normalizedName.substringAfter('/', normalizedName)
        if (relativePath.isEmpty()) return null

        val targetPath = targetDir.canonicalPath
        val outputFile = File(targetDir, relativePath).canonicalFile
        require(outputFile.path.startsWith(targetPath + File.separator) ||
            (isDirectory && outputFile.path == targetPath)) {
            "Model archive entry is outside the target directory"
        }
        return outputFile
    }
}
