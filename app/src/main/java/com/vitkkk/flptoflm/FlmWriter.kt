package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes an FL Studio Mobile 4.10.x project by cloning a real Mobile template
 * and replacing its DirectWave channels and EVN2 note events.
 */
object FlmWriter {
    private const val FLM_PPQ = 96
    private const val EVN2_VERSION = 20

    /**
     * Calibration between FLP piano-roll note length units and the duration
     * value stored by current FL Studio Mobile EVN2 events.
     *
     * Positions do not use this divisor; only note duration does.
     */
    private const val FLP_LENGTH_UNITS_PER_FLM_UNIT = 2.0

    private data class Chunk(val type: String, val payload: ByteArray)
    private data class TimedNote(val tick: Long, val note: FlpNote)

    fun write(project: FlpProject, projectName: String): ByteArray {
        val top = parseTopLevel(FlmTemplate.bytes())

        val head = top.first { it.type == "HEAD" }
        val keyb = top.first { it.type == "KEYB" }
        val meta = top.first { it.type == "META" }
        val tdiv = top.first { it.type == "TDIV" }
        val racks = top.filter { it.type == "RACK" }
        val channels = top.filter { it.type == "CHNL" }

        require(racks.size >= 2 && channels.size >= 2) {
            "O modelo FLM não contém o rack e o canal DirectWave esperados."
        }

        val masterRack = racks.first()
        val generatorRackTemplate = racks[1]
        val masterChannel = channels.first()
        val generatorChannelTemplate = channels[1]

        val outputCount = project.outputChannelCount.coerceAtLeast(1)
        val output = mutableListOf<Chunk>()

        output += patchHead(head, projectName, project.tempo, outputCount)
        output += keyb
        output += meta
        output += tdiv
        output += masterRack

        repeat(outputCount) { index ->
            output += buildGeneratorRack(generatorRackTemplate, index)
        }

        output += masterChannel

        repeat(outputCount) { index ->
            val sourceChannel = project.channels.getOrNull(index)
            val name = sourceChannel?.name
                ?.takeIf { it.isNotBlank() }
                ?: "Canal ${index + 1}"

            // FLP piano-roll notes refer to the channel IID, which is not
            // necessarily equal to the visual zero-based rack index.
            val sourceRackIid = sourceChannel?.iid ?: index
            val notes = collectNotes(project, sourceRackIid)

            output += buildGeneratorChannel(
                template = generatorChannelTemplate,
                index = index,
                name = name,
                notes = notes,
                sourcePpq = project.ppq
            )
        }

        return encodeTopLevel(output)
    }

    private fun patchHead(
        source: Chunk,
        projectName: String,
        tempo: Double,
        outputCount: Int
    ): Chunk {
        val payload = source.payload.copyOf()
        writeFixedText(payload, 8, 256, projectName)
        putDouble(payload, 264, tempo.coerceIn(20.0, 999.0))
        // Master channel + generator channels.
        putInt(payload, 354, outputCount + 1)
        return Chunk(source.type, payload)
    }

    private fun buildGeneratorRack(source: Chunk, index: Int): Chunk {
        require(source.payload.size >= 8)
        val prefix = source.payload.copyOfRange(0, 8)

        val children = parseChunks(source.payload, 8, source.payload.size).map { child ->
            when (child.type) {
                "RHED" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 8) putInt(payload, 4, index + 2)
                    Chunk(child.type, payload)
                }

                "RMOd", "RMOD" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 8) {
                        putInt(payload, 0, 1) // DirectWave
                        putInt(payload, 4, index + 2) // unique module ID
                    }
                    Chunk(child.type, payload)
                }

                else -> child
            }
        }

        return Chunk(source.type, concat(prefix, encodeChunks(children)))
    }

    private fun buildGeneratorChannel(
        template: Chunk,
        index: Int,
        name: String,
        notes: List<TimedNote>,
        sourcePpq: Int
    ): Chunk {
        require(template.payload.size >= 8)
        val prefix = template.payload.copyOfRange(0, 8)

        val children = parseChunks(template.payload, 8, template.payload.size).map { child ->
            when (child.type) {
                "CHHD" -> buildChannelHeader(child, index, name)
                "TRKH" -> buildTrackHeader(child, name, notes, sourcePpq)
                else -> child
            }
        }

        return Chunk(template.type, concat(prefix, encodeChunks(children)))
    }

    private fun buildChannelHeader(source: Chunk, index: Int, name: String): Chunk {
        require(source.payload.size >= 1084) {
            "CHHD do modelo é menor que o esperado."
        }

        val fixed = source.payload.copyOfRange(0, 1084)
        writeFixedText(fixed, 0, 1024, name)
        putDouble(fixed, 1028, (index + 1).toDouble())
        putInt(fixed, 1080, index + 1)

        val encodedName = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(fixed)
        writeInt(out, encodedName.size)
        out.write(encodedName)
        writeInt(out, encodedName.size)
        out.write(encodedName)
        writeInt(out, 0)

        return Chunk(source.type, out.toByteArray())
    }

    private fun buildTrackHeader(
        source: Chunk,
        channelName: String,
        notes: List<TimedNote>,
        sourcePpq: Int
    ): Chunk {
        val children = parseChunks(source.payload, 0, source.payload.size).map { child ->
            when (child.type) {
                "DESc" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 284) {
                        writeFixedText(payload, 28, 256, channelName)
                    }
                    Chunk(child.type, payload)
                }

                "CLIP" -> buildClip(child, notes, sourcePpq)
                else -> child
            }
        }

        return Chunk(source.type, encodeChunks(children))
    }

    private fun buildClip(
        source: Chunk,
        notes: List<TimedNote>,
        sourcePpq: Int
    ): Chunk {
        require(source.payload.size >= 8)
        val prefix = source.payload.copyOfRange(0, 8)
        putInt(prefix, 0, 0)

        val lastTick = notes.maxOfOrNull { it.tick + it.note.length } ?: 0L
        val patternLengthBeats = max(
            4.0,
            ceil(lastTick.toDouble() / sourcePpq.coerceAtLeast(1))
        )

        val children = parseChunks(source.payload, 8, source.payload.size).map { child ->
            when (child.type) {
                "CLHD", "CLHd" -> {
                    val payload = child.payload.copyOf()
                    if (payload.size >= 24) {
                        putDouble(payload, 0, 0.0)
                        putDouble(payload, 8, patternLengthBeats)
                        putDouble(payload, 16, 0.0)
                    }
                    Chunk(child.type, payload)
                }

                "EVN2" -> Chunk("EVN2", encodeEvents(notes, sourcePpq))
                else -> child
            }
        }

        return Chunk(source.type, concat(prefix, encodeChunks(children)))
    }

    private fun encodeEvents(notes: List<TimedNote>, sourcePpq: Int): ByteArray {
        val sorted = notes.sortedWith(
            compareBy<TimedNote> { it.tick }
                .thenBy { it.note.key }
                .thenBy { it.note.slide }
        )

        val out = ByteArrayOutputStream()
        writeShort(out, EVN2_VERSION)

        for (timed in sorted) {
            val absoluteFlmTick = convertTicks(timed.tick, sourcePpq)
            writeInt(out, absoluteFlmTick)

            val duration = timed.note.length.toDouble() /
                (sourcePpq.coerceAtLeast(1) * FLP_LENGTH_UNITS_PER_FLM_UNIT)
            val minimumDuration = 1.0 /
                (FLM_PPQ * FLP_LENGTH_UNITS_PER_FLM_UNIT)

            writeDouble(out, duration.coerceAtLeast(minimumDuration))
            writeShort(out, timed.note.key.coerceIn(0, 127))
            out.write(scale128To255(timed.note.velocity))
            out.write(scale128To255(timed.note.pan))
            writeShort(out, mapFinePitch(timed.note.finePitch))
            out.write(0) // repeat
            out.write(if (timed.note.slide) 1 else 0)
        }

        return out.toByteArray()
    }

    private fun collectNotes(
        project: FlpProject,
        rackChannelIid: Int
    ): List<TimedNote> {
        val patterns = project.patterns.associateBy { it.id }
        val result = mutableListOf<TimedNote>()

        if (project.playlist.isNotEmpty()) {
            for (item in project.playlist) {
                val pattern = patterns[item.patternId] ?: continue

                for (note in pattern.notes) {
                    if (note.rackChannel != rackChannelIid) continue

                    val start = item.position + note.position
                    val itemEnd = item.position + item.length
                    if (item.length > 0L && start >= itemEnd) continue

                    result += TimedNote(start.coerceAtLeast(0L), note)
                }
            }
        } else {
            // Pattern-mode projects can have no arrangement placements.
            for (pattern in project.patterns) {
                for (note in pattern.notes) {
                    if (note.rackChannel == rackChannelIid) {
                        result += TimedNote(note.position.coerceAtLeast(0L), note)
                    }
                }
            }
        }

        return result
    }

    private fun convertTicks(ticks: Long, sourcePpq: Int): Int {
        val scaled = ticks.toDouble() * FLM_PPQ / sourcePpq.coerceAtLeast(1)
        return scaled.roundToInt().coerceAtLeast(0)
    }

    private fun scale128To255(value: Int): Int =
        (value.coerceIn(0, 128) * 255.0 / 128.0)
            .roundToInt()
            .coerceIn(0, 255)

    private fun mapFinePitch(value: Int): Int {
        val offset = value.coerceIn(0, 240) - 120
        return (32767 + offset * 273).coerceIn(0, 65535)
    }

    private fun parseTopLevel(bytes: ByteArray): List<Chunk> {
        require(
            bytes.size >= 4 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "10LF"
        ) {
            "Modelo FLM inválido."
        }

        return parseChunks(bytes, 4, bytes.size)
    }

    private fun parseChunks(
        bytes: ByteArray,
        start: Int,
        end: Int
    ): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var offset = start

        while (offset < end) {
            require(offset + 8 <= end) { "Chunk FLM incompleto." }

            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = getInt(bytes, offset + 4)

            require(
                length >= 0 &&
                    offset + 8L + length <= end.toLong()
            ) {
                "Tamanho inválido no chunk $type."
            }

            val payloadStart = offset + 8
            val payloadEnd = payloadStart + length
            result += Chunk(
                type,
                bytes.copyOfRange(payloadStart, payloadEnd)
            )
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

    private fun writeFixedText(
        target: ByteArray,
        offset: Int,
        size: Int,
        text: String
    ) {
        java.util.Arrays.fill(
            target,
            offset,
            offset + size,
            0.toByte()
        )

        val encoded = text.toByteArray(Charsets.UTF_8)
        val count = minOf(encoded.size, size - 1)
        encoded.copyInto(target, offset, 0, count)
    }

    private fun concat(first: ByteArray, second: ByteArray): ByteArray =
        ByteArray(first.size + second.size).also {
            first.copyInto(it, 0)
            second.copyInto(it, first.size)
        }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer
            .wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer
            .wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
    }

    private fun putDouble(
        bytes: ByteArray,
        offset: Int,
        value: Double
    ) {
        ByteBuffer
            .wrap(bytes, offset, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(value)
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

    private fun writeDouble(
        out: ByteArrayOutputStream,
        value: Double
    ) {
        val bytes = ByteBuffer
            .allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(value)
            .array()

        out.write(bytes)
    }
}
