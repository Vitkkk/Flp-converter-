package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of the first effect-aware FLM conversion pass. */
data class FlmEffectWriteResult(
    val bytes: ByteArray,
    val addedEffects: Int,
    val unsupportedEffects: List<String>,
    val disabledEffectsSkipped: Int
)

/**
 * Adds compatible FL Studio Mobile modules after each generated DirectWave.
 *
 * The underlying note/channel project is still produced by [FlmWriter]. This
 * post-processing pass only changes generator RACK chunks, preserving all note
 * and timeline data already validated in the 0.3.x builds.
 */
object FlmEffectAwareWriter {
    private data class Chunk(val type: String, val payload: ByteArray)

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
        val unsupported = linkedSetOf<String>()

        val patched = top.map { chunk ->
            if (chunk.type != "RACK") return@map chunk

            // First RACK is the master. Following RACKs match generated channels.
            if (rackOrdinal++ == 0) return@map chunk
            val channelIndex = rackOrdinal - 2
            val sourceChannelIid = project.channels.getOrNull(channelIndex)?.iid ?: channelIndex
            val sourceSlots = mixer.effectsForChannel(sourceChannelIid)

            val modules = mutableListOf<MobileEffectTemplate>()
            for (slot in sourceSlots.sortedBy { it.slotIndex }) {
                val pluginName = slot.bestName ?: continue
                if (!slot.enabled) {
                    disabledSkipped++
                    continue
                }

                val template = MobileEffectCatalog.findDesktopEquivalent(pluginName)
                if (template == null) {
                    unsupported += pluginName
                } else {
                    modules += template
                }
            }

            if (modules.isEmpty()) return@map chunk
            added += modules.size
            addEffectsToRack(chunk, channelIndex, modules)
        }

        return FlmEffectWriteResult(
            bytes = encodeTopLevel(patched),
            addedEffects = added,
            unsupportedEffects = unsupported.toList(),
            disabledEffectsSkipped = disabledSkipped
        )
    }

    private fun addEffectsToRack(
        rack: Chunk,
        channelIndex: Int,
        effects: List<MobileEffectTemplate>
    ): Chunk {
        require(rack.payload.size >= 8) { "RACK FLM incompleto." }
        val prefix = rack.payload.copyOfRange(0, 8)
        val children = parseChunks(rack.payload, 8, rack.payload.size).toMutableList()

        // Keep DirectWave and all existing rack data intact. Effect IDs only need
        // to be unique inside the project, so use a high deterministic range.
        effects.forEachIndexed { effectIndex, template ->
            val uniqueId = 10_000 + channelIndex * 64 + effectIndex
            children += Chunk("RMOd", template.collapsedPayload(uniqueId))
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
