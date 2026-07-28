package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.max

/** A note exactly as stored in a Pattern Notes event inside the FLP. */
data class FlpNote(
    val position: Long,
    val length: Long,
    val rackChannel: Int,
    val key: Int,
    val velocity: Int,
    val pan: Int,
    val finePitch: Int,
    val release: Int,
    val group: Int,
    val slide: Boolean
)

data class FlpChannel(
    val index: Int,
    val iid: Int,
    val name: String
)

data class FlpPattern(
    val id: Int,
    val name: String,
    val length: Long?,
    val notes: List<FlpNote>
)

data class FlpPlaylistItem(
    val position: Long,
    val length: Long,
    val patternId: Int,
    val trackReverseIndex: Int,
    val startOffset: Float,
    val endOffset: Float
)

data class FlpProject(
    val format: Int,
    val headerChannelCount: Int,
    val ppq: Int,
    val sourceSize: Long,
    val tempo: Double,
    val flVersion: String?,
    val channels: List<FlpChannel>,
    val patterns: List<FlpPattern>,
    val playlist: List<FlpPlaylistItem>
) {
    val noteCount: Int get() = patterns.sumOf { it.notes.size }
    val slideNoteCount: Int get() = patterns.sumOf { pattern -> pattern.notes.count { it.slide } }
    val usedRackChannelCount: Int
        get() = patterns.asSequence()
            .flatMap { it.notes.asSequence() }
            .maxOfOrNull { it.rackChannel + 1 } ?: 0

    /** Number of empty DirectWave channels the FLM writer needs to create. */
    val outputChannelCount: Int
        get() = max(headerChannelCount, max(channels.size, usedRackChannelCount))
}

private data class MutableChannel(
    val iid: Int,
    val index: Int,
    var name: String = ""
)

private data class MutablePattern(
    val id: Int,
    var name: String = "",
    var length: Long? = null,
    val notes: MutableList<FlpNote> = mutableListOf()
)

/**
 * Streaming FLP parser for the information required by the converter.
 *
 * It reads the FLdt event stream and keeps only channel names, patterns, piano-roll
 * notes, slide flags, playlist pattern placements, tempo and FL Studio version.
 * Plugin states, samples, mixer data and effects are deliberately skipped.
 */
object FlpParser {
    private const val EVENT_CHANNEL_NEW = 64
    private const val EVENT_PATTERN_NEW = 65
    private const val EVENT_TEMPO = 156
    private const val EVENT_PATTERN_LENGTH = 164
    private const val EVENT_CHANNEL_NAME = 192
    private const val EVENT_PATTERN_NAME = 193
    private const val EVENT_FL_VERSION = 199
    private const val EVENT_PATTERN_NOTES = 224 // DATA (208) + 16
    private const val EVENT_PLAYLIST = 233 // DATA (208) + 25

    fun parse(bytes: ByteArray): FlpProject =
        ByteArrayInputStream(bytes).use { parse(it, bytes.size.toLong()) }

    fun parse(input: InputStream, sourceSize: Long = -1L): FlpProject {
        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada."
        }

        val headerLength = readLeUnsignedInt(input)
        require(headerLength in 6L..1_048_576L) { "Cabeçalho FLP inválido." }

        val format = readLeUnsignedShort(input)
        val headerChannelCount = readLeUnsignedShort(input)
        val ppq = readLeUnsignedShort(input)
        require(headerChannelCount in 1..65_535) { "Número de canais inválido." }
        require(ppq > 0) { "PPQ inválido." }
        skipFully(input, headerLength - 6L)

        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado."
        }
        var remaining = readLeUnsignedInt(input)

        val channels = linkedMapOf<Int, MutableChannel>()
        val patterns = linkedMapOf<Int, MutablePattern>()
        val playlist = mutableListOf<FlpPlaylistItem>()
        var currentChannelIid: Int? = null
        var currentPatternId: Int? = null
        var tempo = 130.0
        var flVersion: String? = null

        while (remaining > 0L) {
            val eventId = input.read()
            if (eventId < 0) throw EOFException("O FLdt terminou antes do tamanho declarado.")
            remaining--

            when {
                eventId < 64 -> {
                    val value = input.read()
                    if (value < 0) throw EOFException("Evento BYTE incompleto.")
                    remaining--
                }

                eventId < 128 -> {
                    val value = readLeUnsignedShort(input)
                    remaining -= 2L
                    when (eventId) {
                        EVENT_CHANNEL_NEW -> {
                            currentChannelIid = value
                            val channel = channels.getOrPut(value) {
                                MutableChannel(value, channels.size)
                            }
                            if (channel.name.isBlank()) channel.name = "Canal ${channel.index + 1}"
                        }

                        EVENT_PATTERN_NEW -> {
                            currentPatternId = value
                            patterns.getOrPut(value) { MutablePattern(value, "Pattern $value") }
                        }
                    }
                }

                eventId < 192 -> {
                    val value = readLeUnsignedInt(input)
                    remaining -= 4L
                    when (eventId) {
                        EVENT_TEMPO -> if (value > 0L) tempo = value / 1000.0
                        EVENT_PATTERN_LENGTH -> currentPatternId?.let { id ->
                            patterns.getOrPut(id) { MutablePattern(id, "Pattern $id") }.length = value
                        }
                    }
                }

                else -> {
                    val (payloadLength, varIntBytes) = readVarInt(input)
                    remaining -= varIntBytes.toLong()
                    require(payloadLength <= remaining) {
                        "Evento $eventId ultrapassa o tamanho do FLdt."
                    }

                    when (eventId) {
                        EVENT_CHANNEL_NAME -> {
                            val text = readTextPayload(input, payloadLength)
                            currentChannelIid?.let { iid ->
                                channels.getOrPut(iid) { MutableChannel(iid, channels.size) }.name =
                                    text.ifBlank { "Canal ${channels.size}" }
                            }
                        }

                        EVENT_PATTERN_NAME -> {
                            val text = readTextPayload(input, payloadLength)
                            currentPatternId?.let { id ->
                                patterns.getOrPut(id) { MutablePattern(id, "Pattern $id") }.name =
                                    text.ifBlank { "Pattern $id" }
                            }
                        }

                        EVENT_FL_VERSION -> flVersion = readTextPayload(input, payloadLength)

                        EVENT_PATTERN_NOTES -> {
                            val id = currentPatternId
                            if (id == null) {
                                skipFully(input, payloadLength)
                            } else {
                                parseNotes(
                                    input,
                                    payloadLength,
                                    patterns.getOrPut(id) { MutablePattern(id, "Pattern $id") }.notes
                                )
                            }
                        }

                        EVENT_PLAYLIST -> parsePlaylist(input, payloadLength, playlist)
                        else -> skipFully(input, payloadLength)
                    }
                    remaining -= payloadLength
                }
            }

            require(remaining >= 0L) { "Estrutura de eventos FLP inválida." }
        }

        val immutableChannels = channels.values.map { channel ->
            FlpChannel(
                index = channel.index,
                iid = channel.iid,
                name = channel.name.ifBlank { "Canal ${channel.index + 1}" }
            )
        }
        val immutablePatterns = patterns.values.map { pattern ->
            FlpPattern(
                id = pattern.id,
                name = pattern.name.ifBlank { "Pattern ${pattern.id}" },
                length = pattern.length,
                notes = pattern.notes.toList()
            )
        }

        return FlpProject(
            format = format,
            headerChannelCount = headerChannelCount,
            ppq = ppq,
            sourceSize = sourceSize,
            tempo = tempo,
            flVersion = flVersion,
            channels = immutableChannels,
            patterns = immutablePatterns,
            playlist = playlist.toList()
        )
    }

    private fun parseNotes(input: InputStream, payloadLength: Long, destination: MutableList<FlpNote>) {
        val completeRecords = payloadLength / 24L
        repeatLong(completeRecords) {
            val record = readExact(input, 24)
            val flags = u16(record, 4)
            destination += FlpNote(
                position = u32(record, 0),
                length = u32(record, 8),
                rackChannel = u16(record, 6),
                key = u16(record, 12),
                group = u16(record, 14),
                finePitch = u8(record, 16),
                release = u8(record, 18),
                pan = u8(record, 20),
                velocity = u8(record, 21),
                slide = flags and (1 shl 3) != 0
            )
        }
        skipFully(input, payloadLength % 24L)
    }

    private fun parsePlaylist(
        input: InputStream,
        payloadLength: Long,
        destination: MutableList<FlpPlaylistItem>
    ) {
        val recordSize = when {
            payloadLength > 0L && payloadLength % 60L == 0L -> 60
            payloadLength > 0L && payloadLength % 32L == 0L -> 32
            else -> {
                skipFully(input, payloadLength)
                return
            }
        }

        repeatLong(payloadLength / recordSize) {
            val record = readExact(input, recordSize)
            val patternBase = u16(record, 4)
            val itemIndex = u16(record, 6)
            if (itemIndex > patternBase) {
                destination += FlpPlaylistItem(
                    position = u32(record, 0),
                    length = u32(record, 8),
                    patternId = itemIndex - patternBase,
                    trackReverseIndex = u16(record, 12),
                    startOffset = f32(record, 24),
                    endOffset = f32(record, 28)
                )
            }
        }
    }

    private fun readTextPayload(input: InputStream, length: Long): String {
        require(length <= 4L * 1024L * 1024L) { "Campo de texto FLP grande demais." }
        val bytes = readExact(input, length.toInt())
        if (bytes.isEmpty()) return ""

        val hasUtf16Bom = bytes.size >= 2 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()
        val looksUtf16 = !hasUtf16Bom && bytes.size >= 4 &&
            bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() } > bytes.size / 6

        val decoded = when {
            hasUtf16Bom -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            looksUtf16 -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charset.forName("windows-1252"))
        }
        return decoded.trimEnd('\u0000').trim()
    }

    private fun readVarInt(input: InputStream): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var count = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Tamanho variável de evento incompleto.")
            count++
            require(count <= 10) { "VarInt FLP inválido." }
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to count
            shift += 7
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("O arquivo terminou antes do esperado.")
            offset += read
        }
        return result
    }

    private fun readLeUnsignedShort(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw EOFException("Cabeçalho FLP incompleto.")
        return b0 or (b1 shl 8)
    }

    private fun readLeUnsignedInt(input: InputStream): Long {
        val bytes = readExact(input, 4)
        return u32(bytes, 0)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException("O arquivo terminou antes do esperado.")
                remaining--
            }
        }
    }

    private fun repeatLong(count: Long, action: () -> Unit) {
        var index = 0L
        while (index < count) {
            action()
            index++
        }
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private fun f32(bytes: ByteArray, offset: Int): Float =
        Float.fromBits(u32(bytes, offset).toInt())
}

/**
 * Temporary diagnostic writer. It is intentionally not presented as a valid FLM.
 * The compatible writer will clone the version-specific structure of a real blank
 * FLM and replace its DirectWave channels and EVN2 note records.
 */
object ExperimentalFlmWriter {
    fun write(project: FlpProject): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('1'.code.toByte(), '0'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        out.write("\nFLP_TO_FLM_DIAGNOSTIC=2\n".toByteArray())
        out.write("TEMPO=${project.tempo}\n".toByteArray())
        out.write("PPQ=${project.ppq}\n".toByteArray())
        out.write("CHANNELS=${project.outputChannelCount}\n".toByteArray())
        out.write("PATTERNS=${project.patterns.size}\n".toByteArray())
        out.write("PLAYLIST_ITEMS=${project.playlist.size}\n".toByteArray())
        out.write("NOTES=${project.noteCount}\n".toByteArray())
        out.write("SLIDE_NOTES=${project.slideNoteCount}\n".toByteArray())
        return out.toByteArray()
    }
}
