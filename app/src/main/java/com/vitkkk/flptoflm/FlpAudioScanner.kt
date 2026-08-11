package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/** One FL Studio channel that is used directly as an Audio Clip in the Playlist. */
data class FlpAudioChannel(
    val iid: Int,
    val name: String,
    val type: Int,
    val samplePath: String
)

/** Absolute Playlist placement of an Audio Clip channel. */
data class FlpAudioPlacement(
    val channelIid: Int,
    val position: Long,
    val length: Long,
    val trackReverseIndex: Int,
    val startOffsetTicks: Double,
    val endOffsetTicks: Double,
    val muted: Boolean
)

data class FlpAudioScan(
    val channels: List<FlpAudioChannel>,
    val placements: List<FlpAudioPlacement>
) {
    val activePlacements: List<FlpAudioPlacement>
        get() = placements.filterNot { it.muted }

    val usedChannelIids: Set<Int>
        get() = activePlacements.mapTo(linkedSetOf()) { it.channelIid }

    val usedChannels: List<FlpAudioChannel>
        get() = channels.filter { it.iid in usedChannelIids }

    companion object {
        val EMPTY = FlpAudioScan(emptyList(), emptyList())
    }
}

private data class MutableAudioChannel(
    val iid: Int,
    var name: String = "",
    var type: Int = 0,
    var samplePath: String = ""
)

/**
 * Independent FLP pass for Audio Clips.
 *
 * The normal note parser intentionally ignores sample paths and Playlist channel
 * clips. Keeping this separate means the existing note/slide conversion stays
 * untouched while ZIP projects can additionally restore audio tracks.
 */
object FlpAudioScanner {
    private const val EVENT_CHANNEL_TYPE = 21
    private const val EVENT_CHANNEL_NEW = 64
    private const val EVENT_CHANNEL_NAME = 192
    private const val EVENT_SAMPLE_PATH = 196
    private const val EVENT_PLAYLIST = 233

    private val supportedExtensions = setOf(
        "wav", "mp3", "ogg", "flac", "m4a", "aac", "aif", "aiff"
    )

    fun scan(bytes: ByteArray): FlpAudioScan =
        ByteArrayInputStream(bytes).use(::scan)

    fun scan(input: InputStream): FlpAudioScan {
        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada durante a leitura de áudio."
        }
        val headerLength = readU32(input)
        require(headerLength >= 6L) { "Cabeçalho FLP inválido." }
        skipFully(input, headerLength)

        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado durante a leitura de áudio."
        }
        var remaining = readU32(input)

        val channels = linkedMapOf<Int, MutableAudioChannel>()
        val placements = mutableListOf<FlpAudioPlacement>()
        var currentChannel: Int? = null

        while (remaining > 0L) {
            val id = input.read()
            if (id < 0) throw EOFException("FLdt terminou antes do tamanho declarado.")
            remaining--

            when {
                id < 64 -> {
                    val value = input.read()
                    if (value < 0) throw EOFException("Evento BYTE incompleto.")
                    remaining--
                    if (id == EVENT_CHANNEL_TYPE) {
                        currentChannel?.let { iid ->
                            channels.getOrPut(iid) { MutableAudioChannel(iid) }.type = value
                        }
                    }
                }

                id < 128 -> {
                    val value = readU16(input)
                    remaining -= 2
                    if (id == EVENT_CHANNEL_NEW) {
                        currentChannel = value
                        channels.getOrPut(value) { MutableAudioChannel(value) }
                    }
                }

                id < 192 -> {
                    skipFully(input, 4)
                    remaining -= 4
                }

                else -> {
                    val (length, sizeBytes) = readVarInt(input)
                    remaining -= sizeBytes
                    require(length <= remaining && length <= Int.MAX_VALUE) {
                        "Evento $id inválido durante leitura de áudio."
                    }
                    val payload = readExact(input, length.toInt())
                    remaining -= length

                    when (id) {
                        EVENT_CHANNEL_NAME -> currentChannel?.let { iid ->
                            channels.getOrPut(iid) { MutableAudioChannel(iid) }.name = decodeText(payload)
                        }

                        EVENT_SAMPLE_PATH -> currentChannel?.let { iid ->
                            channels.getOrPut(iid) { MutableAudioChannel(iid) }.samplePath = decodeText(payload)
                        }

                        EVENT_PLAYLIST -> parsePlaylist(payload, placements)
                    }
                }
            }
            require(remaining >= 0L) { "Estrutura FLP inválida na leitura de áudio." }
        }

        val usable = channels.values
            .filter { channel ->
                channel.samplePath.substringAfterLast('.', "").lowercase() in supportedExtensions
            }
            .map { channel ->
                FlpAudioChannel(
                    iid = channel.iid,
                    name = channel.name.ifBlank {
                        channel.samplePath.substringAfterLast('/').substringAfterLast('\\')
                            .substringBeforeLast('.', "Audio")
                    },
                    type = channel.type,
                    samplePath = channel.samplePath
                )
            }

        val usableIds = usable.mapTo(hashSetOf()) { it.iid }
        return FlpAudioScan(
            channels = usable,
            placements = placements.filter { it.channelIid in usableIds }
        )
    }

    private fun parsePlaylist(payload: ByteArray, output: MutableList<FlpAudioPlacement>) {
        val recordSize = when {
            payload.isNotEmpty() && payload.size % 60 == 0 -> 60
            payload.isNotEmpty() && payload.size % 32 == 0 -> 32
            else -> return
        }

        var offset = 0
        while (offset + recordSize <= payload.size) {
            val patternBase = u16(payload, offset + 4)
            val itemIndex = u16(payload, offset + 6)

            // Pattern clips are encoded above patternBase. Channel clips (Audio
            // Clip / Automation Clip) use the channel IID directly.
            if (itemIndex <= patternBase) {
                output += FlpAudioPlacement(
                    channelIid = itemIndex,
                    position = u32(payload, offset),
                    length = u32(payload, offset + 8),
                    trackReverseIndex = u16(payload, offset + 12),
                    startOffsetTicks = f32(payload, offset + 24).toDouble()
                        .takeIf(Double::isFinite) ?: 0.0,
                    endOffsetTicks = f32(payload, offset + 28).toDouble()
                        .takeIf(Double::isFinite) ?: 0.0,
                    muted = u16(payload, offset + 18) and 0x2000 != 0
                )
            }
            offset += recordSize
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf16Bom = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()
        val looksUtf16 = !utf16Bom && bytes.size >= 4 && bytes.indices.count {
            it % 2 == 1 && bytes[it] == 0.toByte()
        } > bytes.size / 6
        return when {
            utf16Bom -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            looksUtf16 -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charset.forName("windows-1252"))
        }.trimEnd('\u0000').trim()
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("Arquivo terminou antes do esperado.")
            offset += read
        }
        return bytes
    }

    private fun readU16(input: InputStream): Int = u16(readExact(input, 2), 0)
    private fun readU32(input: InputStream): Long = u32(readExact(input, 4), 0)

    private fun readVarInt(input: InputStream): Pair<Long, Long> {
        var result = 0L
        var shift = 0
        var count = 0L
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("VarInt FLP incompleto.")
            count++
            require(count <= 10) { "VarInt FLP inválido." }
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result to count
            shift += 7
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped
            else {
                if (input.read() < 0) throw EOFException("Arquivo terminou antes do esperado.")
                left--
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun f32(bytes: ByteArray, offset: Int): Float =
        Float.fromBits(u32(bytes, offset).toInt())
}
