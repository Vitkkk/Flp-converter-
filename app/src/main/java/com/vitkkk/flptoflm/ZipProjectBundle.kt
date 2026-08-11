package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class ZipMediaEntry(
    val sourceEntryName: String,
    val size: Long
) {
    val normalizedName: String = normalizePath(sourceEntryName)
    val fileName: String = normalizedName.substringAfterLast('/')
}

data class ZipFlpBundle(
    val flpBytes: ByteArray,
    val flpEntryName: String,
    val mediaEntries: List<ZipMediaEntry>
)

data class ResolvedZipAudio(
    val channelIid: Int,
    val sourceSamplePath: String,
    val sourceEntryName: String,
    val outputRelativePath: String
)

object ZipFlpBundleReader {
    private val mediaExtensions = setOf(
        "wav", "mp3", "ogg", "flac", "m4a", "aac", "aif", "aiff"
    )

    fun read(input: InputStream): ZipFlpBundle {
        var flpBytes: ByteArray? = null
        var flpName: String? = null
        val media = mutableListOf<ZipMediaEntry>()

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val normalized = normalizePath(entry.name)
                    val ext = normalized.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    when {
                        ext == "flp" && flpBytes == null -> {
                            flpBytes = readEntry(zip, 96L * 1024L * 1024L)
                            flpName = normalized
                        }
                        ext in mediaExtensions -> {
                            media += ZipMediaEntry(normalized, entry.size)
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val bytes = flpBytes ?: throw IOException(
            "Este ZIP não contém nenhum arquivo .FLP. " +
                "ZIPs exportados pelo FL Studio Mobile com apenas .FLM são modelos de destino, não fontes FLP."
        )
        return ZipFlpBundle(bytes, flpName ?: "project.flp", media)
    }

    fun resolveAudio(scan: FlpAudioScan, entries: List<ZipMediaEntry>): List<ResolvedZipAudio> {
        if (entries.isEmpty() || scan.usedChannels.isEmpty()) return emptyList()

        val resolved = mutableListOf<ResolvedZipAudio>()
        val usedOutputPaths = hashSetOf<String>()
        val outputPathBySourceEntry = mutableMapOf<String, String>()

        for (channel in scan.usedChannels) {
            val source = normalizePath(channel.samplePath)
            val basename = source.substringAfterLast('/')
            val match = entries
                .map { entry -> entry to scoreMatch(source, basename, entry.normalizedName) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first ?: continue

            val sourceKey = match.normalizedName.lowercase(Locale.ROOT)
            val outputPath = outputPathBySourceEntry[sourceKey] ?: run {
                var candidate = when {
                    match.normalizedName.startsWith("My Samples/", ignoreCase = true) -> match.normalizedName
                    else -> "My Samples/${match.fileName}"
                }

                if (!usedOutputPaths.add(candidate.lowercase(Locale.ROOT))) {
                    val stem = match.fileName.substringBeforeLast('.', match.fileName)
                    val ext = match.fileName.substringAfterLast('.', "")
                    var suffix = 2
                    do {
                        candidate = "My Samples/${stem}_$suffix${if (ext.isNotEmpty()) ".$ext" else ""}"
                        suffix++
                    } while (!usedOutputPaths.add(candidate.lowercase(Locale.ROOT)))
                }

                outputPathBySourceEntry[sourceKey] = candidate
                candidate
            }

            resolved += ResolvedZipAudio(
                channelIid = channel.iid,
                sourceSamplePath = channel.samplePath,
                sourceEntryName = match.sourceEntryName,
                outputRelativePath = outputPath
            )
        }
        return resolved
    }

    private fun scoreMatch(source: String, basename: String, entry: String): Int {
        val s = source.lowercase(Locale.ROOT)
        val e = entry.lowercase(Locale.ROOT)
        val b = basename.lowercase(Locale.ROOT)
        return when {
            s == e -> 100
            e.endsWith("/$s") -> 95
            s.endsWith("/$e") -> 90
            e.substringAfterLast('/') == b -> 70
            e.endsWith("/$b") -> 65
            else -> 0
        }
    }

    private fun readEntry(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("O FLP dentro do ZIP é grande demais para esta build.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

internal fun normalizePath(value: String): String =
    value.replace('\\', '/').trim().trimStart('/').replace(Regex("/+"), "/")
