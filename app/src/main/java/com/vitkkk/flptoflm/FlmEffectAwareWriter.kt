package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of the effect-aware FLM conversion pass. */
data class FlmEffectWriteResult(
    val bytes: ByteArray,
    val addedEffects: Int,
    val unsupportedEffects: List<String>,
    val disabledEffectsSkipped: Int,
    val directSettings: Int,
    val adaptedSettings: Int,
    val defaultSettings: Int,
    val settingsNotes: List<String>
)

/**
 * Adds compatible FL Studio Mobile modules after the generated DirectWave and
 * translates the native Fruity plugin state into Mobile PRMS/SMPR values.
 *
 * Master effects are inserted once in the Mobile master rack. Effects from a
 * numbered FL Studio Mixer insert are cloned only into channels routed to that
 * insert. Channels routed straight to Master do not receive duplicated master FX.
 */
object FlmEffectAwareWriter {
    private data class Chunk(val type: String, val payload: ByteArray)

    private data class MappedModules(
        val effects: List<TranslatedMobileEffect>,
        val disabledSkipped: Int,
        val unsupported: Set<String>
    )

    fun write(
        project: FlpProject,
        projectName: String,
        mixer: FlpMixerScan
    ): FlmEffectWriteResult {
        val baseProject = FlmWriter.write(project, projectName)
        val top = parseTopLevel(baseProject)

        var rackOrdinal = 0
        var added = 0
        var disabledSkipped = 0
        var direct = 0
        var adapted = 0
        var defaults = 0
        val unsupported = linkedSetOf<String>()
        val notes = linkedSetOf<String>()

        val patched = top.map { chunk ->
            if (chunk.type != "RACK") return@map chunk

            val currentRack = rackOrdinal++
            val sourceSlots: List<FlpEffectSlot>
            val uniqueRackIndex: Int

            if (currentRack == 0) {
                // FL Studio Master insert (IID 0) becomes the Mobile master rack.
                sourceSlots = mixer.effectsByInsert[0].orEmpty()
                uniqueRackIndex = -1
            } else {
                val channelIndex = currentRack - 1
                val sourceChannelIid = project.channels.getOrNull(channelIndex)?.iid ?: channelIndex
                val mixerInsert = mixer.channelToInsert[sourceChannelIid]

                // Insert 0 is Master and was already handled above. A missing route
                // is also treated as direct-to-master rather than duplicating FX.
                sourceSlots = if (mixerInsert != null && mixerInsert > 0) {
                    mixer.effectsByInsert[mixerInsert].orEmpty()
                } else {
                    emptyList()
                }
                uniqueRackIndex = channelIndex
            }

            val mapped = mapSlots(sourceSlots)
            disabledSkipped += mapped.disabledSkipped
            unsupported += mapped.unsupported

            for (effect in mapped.effects) {
                when (effect.quality) {
                    EffectSettingsQuality.DIRECT -> direct++
                    EffectSettingsQuality.ADAPTED -> adapted++
                    EffectSettingsQuality.DEFAULT -> defaults++
                }
                notes += "${effect.template.mobileName}: ${effect.description}"
            }

            if (mapped.effects.isEmpty()) return@map chunk
            added += mapped.effects.size
            addEffectsToRack(chunk, uniqueRackIndex, mapped.effects)
        }

        return FlmEffectWriteResult(
            bytes = encodeTopLevel(patched),
            addedEffects = added,
            unsupportedEffects = unsupported.toList(),
            disabledEffectsSkipped = disabledSkipped,
            directSettings = direct,
            adaptedSettings = adapted,
            defaultSettings = defaults,
            settingsNotes = notes.toList()
        )
    }

    private fun mapSlots(slots: List<FlpEffectSlot>): MappedModules {
        val effects = mutableListOf<TranslatedMobileEffect>()
        val unsupported = linkedSetOf<String>()
        var disabledSkipped = 0

        for (slot in slots.sortedBy { it.slotIndex }) {
            val pluginName = slot.bestName ?: continue
            if (!slot.enabled) {
                disabledSkipped++
                continue
            }

            val translated = MobileEffectSettingsTranslator.translate(slot)
            if (translated == null) {
                unsupported += pluginName
            } else {
                effects += translated
            }
        }

        return MappedModules(effects, disabledSkipped, unsupported)
    }

    private fun addEffectsToRack(
        rack: Chunk,
        rackIndex: Int,
        effects: List<TranslatedMobileEffect>
    ): Chunk {
        require(rack.payload.size >= 8) { "RACK FLM incompleto." }
        val prefix = rack.payload.copyOfRange(0, 8)
        val children = parseChunks(rack.payload, 8, rack.payload.size).toMutableList()

        // Keep DirectWave and existing rack data intact. Use deterministic high
        // IDs, with a separate range for the master rack.
        val idBase = if (rackIndex < 0) 9_000 else 10_000 + rackIndex * 64
        effects.forEachIndexed { effectIndex, effect ->
            children += Chunk(
                "RMOd",
                effect.payload(idBase + effectIndex)
            )
        }

        return Chunk("RACK", concat(prefix, encodeChunks(children)))
    }

    private fun parseTopLevel(bytes: ByteArray): List<Chunk> {
        require(
            bytes.size >= 4 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "10LF"
        ) { "Projeto FLM inválido." }
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
            require(chunk.type.length == 4)
            out.write(chunk.type.toByteArray(Charsets.US_ASCII))
            writeInt(out, chunk.payload.size)
            out.write(chunk.payload)
        }
        return out.toByteArray()
    }

    private fun concat(first: ByteArray, second: ByteArray): ByteArray =
        ByteArray(first.size + second.size).also {
            first.copyInto(it, 0)
            second.copyInto(it, first.size)
        }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }
}
