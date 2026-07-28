package com.vitkkk.flptoflm

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/** A mixer effect slot extracted directly from the FLdt event stream. */
data class FlpEffectSlot(
    val insertIid: Int,
    val slotIndex: Int,
    val internalName: String?,
    val displayName: String?,
    val enabled: Boolean,
    val mix: Int?,
    val pluginData: ByteArray?
) {
    val bestName: String?
        get() = internalName?.takeIf { it.isNotBlank() }
            ?: displayName?.takeIf { it.isNotBlank() }
}

data class FlpMixerScan(
    val channelToInsert: Map<Int, Int>,
    val effectsByInsert: Map<Int, List<FlpEffectSlot>>
) {
    val allEffects: List<FlpEffectSlot>
        get() = effectsByInsert.values.flatten()

    val compatibleEffects: List<FlpEffectSlot>
        get() = allEffects.filter { slot ->
            slot.bestName?.let(MobileEffectCatalog::findDesktopEquivalent) != null
        }

    val unsupportedEffects: List<FlpEffectSlot>
        get() = allEffects.filter { slot ->
            slot.bestName != null &&
                MobileEffectCatalog.findDesktopEquivalent(slot.bestName!!) == null
        }

    fun effectsForChannel(channelIid: Int): List<FlpEffectSlot> {
        val insert = channelToInsert[channelIid] ?: 0
        return effectsByInsert[insert].orEmpty()
    }

    companion object {
        val EMPTY = FlpMixerScan(emptyMap(), emptyMap())
    }
}

private data class MutableSlot(
    val insertIid: Int,
    val slotIndex: Int,
    var internalName: String? = null,
    var displayName: String? = null,
    var enabled: Boolean = true,
    var mix: Int? = null,
    var pluginData: ByteArray? = null
)

/**
 * Reads only the routing and mixer events required for effect conversion.
 *
 * FLP event IDs used here follow the public PyFLP event model:
 * - Channel New = 64
 * - Channel RoutedTo = 22
 * - Slot Index = 98
 * - Plugin InternalName = 201
 * - Plugin Name = 203
 * - Plugin Data = 213
 * - Mixer Params = 225
 * - Insert Flags = 236
 */
object FlpMixerScanner {
    private const val EVENT_CHANNEL_NEW = 64
    private const val EVENT_CHANNEL_ROUTED_TO = 22
    private const val EVENT_SLOT_INDEX = 98
    private const val EVENT_PLUGIN_INTERNAL_NAME = 201
    private const val EVENT_PLUGIN_NAME = 203
    private const val EVENT_PLUGIN_DATA = 213
    private const val EVENT_MIXER_PARAMS = 225
    private const val EVENT_INSERT_FLAGS = 236

    private const val PARAM_SLOT_ENABLED = 0
    private const val PARAM_SLOT_MIX = 1

    fun scan(input: InputStream): FlpMixerScan {
        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada."
        }

        val headerLength = readLeUnsignedInt(input)
        require(headerLength >= 6L) { "Cabeçalho FLP inválido." }
        skipFully(input, headerLength)

        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado."
        }
        var remaining = readLeUnsignedInt(input)

        val channelToInsert = linkedMapOf<Int, Int>()
        val slots = linkedMapOf<Pair<Int, Int>, MutableSlot>()
        val slotParams = mutableMapOf<Pair<Int, Int>, MutableMap<Int, Int>>()

        var currentChannelIid: Int? = null
        var mixerStarted = false
        var insertOrdinal = 0
        var currentInsertIid: Int? = null
        var currentSlotIndex: Int? = null

        while (remaining > 0L) {
            val eventId = input.read()
            if (eventId < 0) throw EOFException("O FLdt terminou antes do tamanho declarado.")
            remaining--

            when {
                eventId < 64 -> {
                    val raw = input.read()
                    if (raw < 0) throw EOFException("Evento BYTE incompleto.")
                    remaining--

                    if (!mixerStarted && eventId == EVENT_CHANNEL_ROUTED_TO) {
                        currentChannelIid?.let { channelIid ->
                            // The event is signed in FLP, although normal insert IDs are non-negative.
                            val routed = raw.toByte().toInt()
                            if (routed >= 0) channelToInsert[channelIid] = routed
                        }
                    }
                }

                eventId < 128 -> {
                    val value = readLeUnsignedShort(input)
                    remaining -= 2L

                    when (eventId) {
                        EVENT_CHANNEL_NEW -> if (!mixerStarted) {
                            currentChannelIid = value
                        }

                        EVENT_SLOT_INDEX -> if (mixerStarted) {
                            currentSlotIndex = value
                            val insert = currentInsertIid
                            if (insert != null) {
                                slots.getOrPut(insert to value) { MutableSlot(insert, value) }
                            }
                        }
                    }
                }

                eventId < 192 -> {
                    skipFully(input, 4)
                    remaining -= 4L
                }

                else -> {
                    val (length, varIntBytes) = readVarInt(input)
                    remaining -= varIntBytes.toLong()
                    require(length <= remaining) { "Evento $eventId ultrapassa o FLdt." }

                    when (eventId) {
                        EVENT_INSERT_FLAGS -> {
                            // FLP stores: current insert (-1), master (0), then insert 1...
                            mixerStarted = true
                            currentInsertIid = insertOrdinal - 1
                            insertOrdinal++
                            currentSlotIndex = null
                            skipFully(input, length)
                        }

                        EVENT_MIXER_PARAMS -> parseMixerParams(
                            readPayload(input, length),
                            slotParams
                        )

                        EVENT_PLUGIN_INTERNAL_NAME -> {
                            val text = decodeText(readPayload(input, length))
                            currentMutableSlot(currentInsertIid, currentSlotIndex, slots)
                                ?.internalName = text
                        }

                        EVENT_PLUGIN_NAME -> {
                            val text = decodeText(readPayload(input, length))
                            currentMutableSlot(currentInsertIid, currentSlotIndex, slots)
                                ?.displayName = text
                        }

                        EVENT_PLUGIN_DATA -> {
                            val data = readPayload(input, length)
                            currentMutableSlot(currentInsertIid, currentSlotIndex, slots)
                                ?.pluginData = data
                        }

                        else -> skipFully(input, length)
                    }
                    remaining -= length
                }
            }

            require(remaining >= 0L) { "Estrutura FLP inválida." }
        }

        for ((key, values) in slotParams) {
            val slot = slots[key] ?: continue
            values[PARAM_SLOT_ENABLED]?.let { slot.enabled = it != 0 }
            values[PARAM_SLOT_MIX]?.let { slot.mix = it }
        }

        val effectsByInsert = slots.values
            .filter { it.internalName?.isNotBlank() == true || it.displayName?.isNotBlank() == true }
            .groupBy { it.insertIid }
            .mapValues { (_, list) ->
                list.sortedBy { it.slotIndex }.map { slot ->
                    FlpEffectSlot(
                        insertIid = slot.insertIid,
                        slotIndex = slot.slotIndex,
                        internalName = slot.internalName,
                        displayName = slot.displayName,
                        enabled = slot.enabled,
                        mix = slot.mix,
                        pluginData = slot.pluginData
                    )
                }
            }

        return FlpMixerScan(channelToInsert, effectsByInsert)
    }

    private fun currentMutableSlot(
        insertIid: Int?,
        slotIndex: Int?,
        slots: MutableMap<Pair<Int, Int>, MutableSlot>
    ): MutableSlot? {
        if (insertIid == null || slotIndex == null) return null
        return slots.getOrPut(insertIid to slotIndex) {
            MutableSlot(insertIid, slotIndex)
        }
    }

    private fun parseMixerParams(
        payload: ByteArray,
        destination: MutableMap<Pair<Int, Int>, MutableMap<Int, Int>>
    ) {
        var offset = 0
        while (offset + 12 <= payload.size) {
            val id = payload[offset + 4].toInt() and 0xff
            val channelData = u16(payload, offset + 6)
            val internalInsertIndex = (channelData ushr 6) and 0x7f
            val slotIndex = channelData and 0x3f
            val insertIid = internalInsertIndex - 1
            val msg = i32(payload, offset + 8)

            if (id == PARAM_SLOT_ENABLED || id == PARAM_SLOT_MIX) {
                destination.getOrPut(insertIid to slotIndex) { mutableMapOf() }[id] = msg
            }
            offset += 12
        }
    }

    private fun readPayload(input: InputStream, length: Long): ByteArray {
        require(length <= 64L * 1024L * 1024L) { "Evento FLP grande demais." }
        return readExact(input, length.toInt())
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf16Bom = bytes.size >= 2 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()
        val looksUtf16 = !utf16Bom && bytes.size >= 4 &&
            bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() } > bytes.size / 6

        val decoded = when {
            utf16Bom -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            looksUtf16 -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charset.forName("windows-1252"))
        }
        return decoded.trimEnd('\u0000').trim()
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(output, offset, count - offset)
            if (read < 0) throw EOFException("Arquivo FLP incompleto.")
            offset += read
        }
        return output
    }

    private fun readLeUnsignedShort(input: InputStream): Int {
        val bytes = readExact(input, 2)
        return u16(bytes, 0)
    }

    private fun readLeUnsignedInt(input: InputStream): Long {
        val bytes = readExact(input, 4)
        return (i32(bytes, 0).toLong() and 0xffffffffL)
    }

    private fun readVarInt(input: InputStream): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var count = 0
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
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException("Arquivo FLP incompleto.")
                remaining--
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun i32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
