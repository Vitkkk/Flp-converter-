package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class FlmAudioWriteResult(
    val bytes: ByteArray,
    val audioChannels: Int,
    val audioClips: Int,
    val usedAssets: List<ResolvedZipAudio>,
    val missingSamplePaths: List<String>
)

/**
 * Replaces the placeholder DirectWave generated for an FLP Audio Clip channel
 * with a real FL Studio Mobile Audio channel. The channel/track template comes
 * from audio.zip and is patched with the source FLP timeline plus ZIP media path.
 */
object FlmAudioWriter {
    private data class Chunk(val type: String, val payload: ByteArray)

    fun write(
        baseFlm: ByteArray,
        project: FlpProject,
        audioScan: FlpAudioScan,
        resolvedAssets: List<ResolvedZipAudio>
    ): FlmAudioWriteResult {
        if (audioScan.usedChannels.isEmpty()) {
            return FlmAudioWriteResult(baseFlm, 0, 0, emptyList(), emptyList())
        }

        val assets = resolvedAssets.associateBy { it.channelIid }
        val placements = audioScan.activePlacements.groupBy { it.channelIid }
        val top = parseTopLevel(baseFlm).toMutableList()
        val rackPositions = top.indices.filter { top[it].type == "RACK" }
        val channelPositions = top.indices.filter { top[it].type == "CHNL" }

        val audioRackTemplate = parseStandaloneChunk(FlmAudioTemplate.rackChunk(), "RACK")
        val audioChannelTemplate = parseStandaloneChunk(FlmAudioTemplate.channelChunk(), "CHNL")

        var channelsWritten = 0
        var clipsWritten = 0
        val used = mutableListOf<ResolvedZipAudio>()
        val missing = linkedSetOf<String>()

        for (audioChannel in audioScan.usedChannels) {
            val projectChannelIndex = project.channels.indexOfFirst { it.iid == audioChannel.iid }
            if (projectChannelIndex < 0) {
                missing += audioChannel.samplePath
                continue
            }

            // RACK/CHNL ordinal zero is Master. FlmWriter creates one placeholder
            // generator for every FLP channel, so Audio Clip channel N is N + 1.
            val rackSlot = rackPositions.getOrNull(projectChannelIndex + 1)
            val channelSlot = channelPositions.getOrNull(projectChannelIndex + 1)
            val asset = assets[audioChannel.iid]
            val channelPlacements = placements[audioChannel.iid].orEmpty()
                .filter { it.length > 0L }
                .sortedBy { it.position }

            if (rackSlot == null || channelSlot == null || asset == null || channelPlacements.isEmpty()) {
                missing += audioChannel.samplePath
                continue
            }

            top[rackSlot] = audioRackTemplate
            top[channelSlot] = buildAudioChannel(
                template = audioChannelTemplate,
                channelIndex = projectChannelIndex,
                channelName = audioChannel.name,
                placements = channelPlacements,
                relativePath = asset.outputRelativePath,
                sourcePpq = project.ppq
            )

            channelsWritten++
            clipsWritten += channelPlacements.size
            used += asset
        }

        return FlmAudioWriteResult(
            bytes = encodeTopLevel(top),
            audioChannels = channelsWritten,
            audioClips = clipsWritten,
            usedAssets = used.distinctBy { it.sourceEntryName },
            missingSamplePaths = missing.toList()
        )
    }

    private fun buildAudioChannel(
        template: Chunk,
        channelIndex: Int,
        channelName: String,
        placements: List<FlpAudioPlacement>,
        relativePath: String,
        sourcePpq: Int
    ): Chunk {
        require(template.payload.size >= 8) { "CHNL de áudio incompleto." }
        val prefix = template.payload.copyOfRange(0, 8)
        val children = parseChunks(template.payload, 8, template.payload.size).map { child ->
            when (child.type) {
                "CHHD" -> buildChannelHeader(child, channelIndex, channelName)
                "TRKH" -> buildAudioTrack(child, channelName, placements, relativePath, sourcePpq)
                else -> child
            }
        }
        return Chunk("CHNL", concat(prefix, encodeChunks(children)))
    }

    private fun buildChannelHeader(source: Chunk, index: Int, name: String): Chunk {
        require(source.payload.size >= 1084) { "CHHD de áudio menor que o esperado." }
        val fixed = source.payload.copyOfRange(0, 1084)
        writeFixedText(fixed, 0, 1024, name)
        putDouble(fixed, 1028, (index + 1).toDouble())
        putInt(fixed, 1080, index + 1)

        val encoded = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(fixed)
        writeInt(out, encoded.size)
        out.write(encoded)
        writeInt(out, encoded.size)
        out.write(encoded)
        writeInt(out, 0)
        return Chunk("CHHD", out.toByteArray())
    }

    private fun buildAudioTrack(
        source: Chunk,
        name: String,
        placements: List<FlpAudioPlacement>,
        relativePath: String,
        sourcePpq: Int
    ): Chunk {
        val children = parseChunks(source.payload, 0, source.payload.size)
        val clipTemplate = children.firstOrNull { it.type == "CLIP" }
            ?: throw IllegalArgumentException("Modelo de áudio não contém CLIP.")
        val output = mutableListOf<Chunk>()

        for (child in children) {
            when (child.type) {
                "DESc" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 284) writeFixedText(payload, 28, 256, name)
                    output += Chunk(child.type, payload)
                }
                "CLIP" -> Unit
                else -> output += child
            }
        }

        placements.forEachIndexed { index, placement ->
            output += buildAudioClip(
                clipTemplate,
                placement,
                relativePath,
                sourcePpq,
                index
            )
        }
        return Chunk("TRKH", encodeChunks(output))
    }

    private fun buildAudioClip(
        source: Chunk,
        placement: FlpAudioPlacement,
        relativePath: String,
        sourcePpq: Int,
        ordinal: Int
    ): Chunk {
        require(source.payload.size >= 8) { "CLIP de áudio incompleto." }
        val ppq = sourcePpq.coerceAtLeast(1).toDouble()
        val prefix = source.payload.copyOfRange(0, 8)

        // Mobile stores the absolute Playlist position in 1/256 beat units.
        val position256 = (placement.position.toDouble() / ppq * 256.0)
            .roundToInt().coerceAtLeast(0)
        putInt(prefix, 0, position256 + ordinal.coerceAtMost(0))

        val children = parseChunks(source.payload, 8, source.payload.size).map { child ->
            when (child.type) {
                "CLHd", "CLHD" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 24) {
                        putDouble(payload, 0, placement.startOffsetTicks.coerceAtLeast(0.0) / ppq)
                        putDouble(payload, 8, (placement.length.toDouble() / ppq).coerceAtLeast(1.0 / 256.0))
                        putDouble(payload, 16, 0.0)
                    }
                    Chunk(child.type, payload)
                }
                "CLSm" -> patchClipSampler(child, relativePath)
                else -> child
            }
        }
        return Chunk("CLIP", concat(prefix, encodeChunks(children)))
    }

    private fun patchClipSampler(source: Chunk, relativePathRaw: String): Chunk {
        require(source.payload.size >= 4) { "CLSm incompleto." }
        val relativePath = normalizePath(relativePathRaw)
        val prefix = source.payload.copyOfRange(0, 4)
        val sampleName = relativePath.substringAfterLast('/').substringBeforeLast('.')
        val children = parseChunks(source.payload, 4, source.payload.size).map { child ->
            when (child.type) {
                "MAIN" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 1036) writeFixedText(payload, 12, 1024, sampleName)
                    Chunk(child.type, payload)
                }
                "PTH1" -> Chunk("PTH1", encodePth1(relativePath))
                "PRST" -> Chunk("PRST", encodePresetPaths(relativePath))
                else -> child
            }
        }
        return Chunk("CLSm", concat(prefix, encodeChunks(children)))
    }

    private fun encodePth1(relativePath: String): ByteArray {
        val path = relativePath.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeShort(out, path.size + 4)
        out.write(byteArrayOf('@'.code.toByte(), 'L'.code.toByte(), '@'.code.toByte(), 0))
        out.write(path)
        return out.toByteArray()
    }

    private fun encodePresetPaths(relativePath: String): ByteArray {
        val absolute = "/storage/emulated/0/Android/data/com.imageline.FLM/files/$relativePath"
            .toByteArray(Charsets.UTF_8)
        val relative = relativePath.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeInt(out, absolute.size)
        out.write(absolute)
        writeInt(out, relative.size)
        out.write(relative)
        return out.toByteArray()
    }

    private fun parseStandaloneChunk(bytes: ByteArray, expected: String): Chunk {
        val chunks = parseChunks(bytes, 0, bytes.size)
        require(chunks.size == 1 && chunks.first().type == expected) {
            "Modelo $expected inválido."
        }
        return chunks.first()
    }

    private fun parseTopLevel(bytes: ByteArray): List<Chunk> {
        require(bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "10LF") {
            "Projeto FLM inválido."
        }
        return parseChunks(bytes, 4, bytes.size)
    }

    private fun parseChunks(bytes: ByteArray, start: Int, end: Int): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var offset = start
        while (offset < end) {
            require(offset + 8 <= end) { "Chunk FLM incompleto." }
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = getInt(bytes, offset + 4)
            require(length >= 0 && offset + 8L + length <= end.toLong()) {
                "Tamanho inválido no chunk $type."
            }
            val payloadStart = offset + 8
            val payloadEnd = payloadStart + length
            result += Chunk(type, bytes.copyOfRange(payloadStart, payloadEnd))
            offset = payloadEnd
        }
        require(offset == end) { "Estrutura FLM desalinhada." }
        return result
    }

    private fun encodeTopLevel(chunks: List<Chunk>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("10LF".toByteArray(Charsets.US_ASCII))
        out.write(encodeChunks(chunks))
        return out.toByteArray()
    }

    private fun encodeChunks(chunks: List<Chunk>): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            out.write(chunk.type.toByteArray(Charsets.US_ASCII))
            writeInt(out, chunk.payload.size)
            out.write(chunk.payload)
        }
        return out.toByteArray()
    }

    private fun writeFixedText(target: ByteArray, offset: Int, size: Int, text: String) {
        java.util.Arrays.fill(target, offset, offset + size, 0.toByte())
        val encoded = text.toByteArray(Charsets.UTF_8)
        encoded.copyInto(target, offset, 0, minOf(encoded.size, size - 1))
    }

    private fun concat(first: ByteArray, second: ByteArray): ByteArray =
        ByteArray(first.size + second.size).also {
            first.copyInto(it, 0)
            second.copyInto(it, first.size)
        }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun putDouble(bytes: ByteArray, offset: Int, value: Double) {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }
}
