package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One absolute project-tempo change, expressed in source-FLP ticks. */
data class FlpTempoChange(
    val tick: Long,
    val bpm: Double
)

/** Result of the independent tempo-automation pass over the FLP event stream. */
data class FlpTempoScan(
    val changes: List<FlpTempoChange>,
    val tempoAutomationChannels: Int,
    val customRangeDetected: Boolean
) {
    val hasChanges: Boolean
        get() = changes.drop(1).any { change ->
            abs(change.bpm - changes.first().bpm) >= 0.001
        }

    companion object {
        val EMPTY = FlpTempoScan(emptyList(), 0, false)
    }
}

private data class RawAutomationPoint(
    val beat: Double,
    val value: Double,
    val tension: Float
)

private data class RawTempoChannel(
    val iid: Int,
    var name: String = "",
    var type: Int = 0,
    var minimumRaw: Int? = null,
    var maximumRaw: Int? = null,
    val points: MutableList<RawAutomationPoint> = mutableListOf()
)

private data class RawChannelClip(
    val position: Long,
    val length: Long,
    val channelIid: Int,
    val startOffsetBeats: Double,
    val muted: Boolean
)

/**
 * Reads Automation Clip events without changing the main note parser.
 *
 * FL Studio stores automation point X coordinates as beat increments. Tempo is
 * linked to the Master target (destination 0x4000, parameter 5). A name/type
 * fallback is retained for newer FLP variants where the link table changes.
 */
object FlpTempoAutomationScanner {
    private const val EVENT_CHANNEL_TYPE = 21
    private const val EVENT_CHANNEL_NEW = 64
    private const val EVENT_CHANNEL_NAME = 192
    private const val EVENT_BASIC_CHANNEL_PARAMS = 219
    private const val EVENT_AUTOMATION_LINKS = 227
    private const val EVENT_PLAYLIST = 233
    private const val EVENT_AUTOMATION_DATA = 234

    private const val CHANNEL_TYPE_AUTOMATION = 5
    private const val MASTER_DESTINATION = 0x4000
    private const val MASTER_TEMPO_PARAMETER = 5

    fun scan(bytes: ByteArray, initialTempo: Double): FlpTempoScan =
        ByteArrayInputStream(bytes).use { scan(it, initialTempo) }

    fun scan(input: InputStream, initialTempo: Double): FlpTempoScan {
        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada durante a leitura de BPM Change."
        }
        val headerLength = readU32(input)
        require(headerLength >= 6L) { "Cabeçalho FLP inválido." }
        skipFully(input, headerLength)

        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado durante a leitura de BPM Change."
        }
        var remaining = readU32(input)

        val channels = linkedMapOf<Int, RawTempoChannel>()
        val tempoTargetIids = linkedSetOf<Int>()
        val clips = mutableListOf<RawChannelClip>()
        var currentChannelIid: Int? = null

        while (remaining > 0L) {
            val eventId = input.read()
            if (eventId < 0) throw EOFException("FLdt terminou antes do tamanho declarado.")
            remaining--

            when {
                eventId < 64 -> {
                    val value = input.read()
                    if (value < 0) throw EOFException("Evento BYTE incompleto.")
                    remaining--
                    if (eventId == EVENT_CHANNEL_TYPE) {
                        currentChannelIid?.let { iid ->
                            channels.getOrPut(iid) { RawTempoChannel(iid) }.type = value
                        }
                    }
                }

                eventId < 128 -> {
                    val value = readU16(input)
                    remaining -= 2L
                    if (eventId == EVENT_CHANNEL_NEW) {
                        currentChannelIid = value
                        channels.getOrPut(value) { RawTempoChannel(value) }
                    }
                }

                eventId < 192 -> {
                    skipFully(input, 4)
                    remaining -= 4L
                }

                else -> {
                    val (payloadLength, varIntLength) = readVarInt(input)
                    remaining -= varIntLength
                    require(payloadLength <= remaining) { "Evento $eventId ultrapassa o FLdt." }
                    val payload = readExact(input, payloadLength.toInt())
                    remaining -= payloadLength

                    when (eventId) {
                        EVENT_CHANNEL_NAME -> currentChannelIid?.let { iid ->
                            channels.getOrPut(iid) { RawTempoChannel(iid) }.name = decodeText(payload)
                        }
                        EVENT_BASIC_CHANNEL_PARAMS -> currentChannelIid?.let { iid ->
                            if (payload.size >= 8) {
                                val channel = channels.getOrPut(iid) { RawTempoChannel(iid) }
                                channel.minimumRaw = i32(payload, 0)
                                channel.maximumRaw = i32(payload, 4)
                            }
                        }
                        EVENT_AUTOMATION_LINKS -> parseAutomationLinks(payload, tempoTargetIids)
                        EVENT_AUTOMATION_DATA -> currentChannelIid?.let { iid ->
                            parseAutomationPoints(
                                payload,
                                channels.getOrPut(iid) { RawTempoChannel(iid) }.points
                            )
                        }
                        EVENT_PLAYLIST -> parsePlaylist(payload, clips)
                    }
                }
            }
            require(remaining >= 0L) { "Estrutura FLP inválida na leitura de BPM Change." }
        }

        val tempoChannels = channels.values.filter { channel ->
            channel.iid in tempoTargetIids ||
                (channel.type == CHANNEL_TYPE_AUTOMATION && isTempoName(channel.name))
        }
        if (tempoChannels.isEmpty()) {
            return FlpTempoScan(listOf(FlpTempoChange(0L, initialTempo)), 0, false)
        }

        val rendered = mutableListOf<FlpTempoChange>()
        var usedCustomRange = false

        for (channel in tempoChannels) {
            if (channel.points.isEmpty()) continue
            val channelClips = clips.filter { !it.muted && it.channelIid == channel.iid }
            val mapper = tempoMapper(channel, initialTempo)
            usedCustomRange = usedCustomRange || mapper.second

            if (channelClips.isEmpty()) {
                for (point in channel.points) {
                    val tick = (point.beat * 96.0).roundToLong().coerceAtLeast(0L)
                    rendered += FlpTempoChange(tick, mapper.first(point.value))
                }
                continue
            }

            for (clip in channelClips) {
                val sourceStart = clip.startOffsetBeats.coerceAtLeast(0.0)
                val sourceLengthBeats = clip.length.toDouble() / 96.0
                val sourceEnd = sourceStart + sourceLengthBeats

                val firstValue = valueAt(channel.points, sourceStart)
                rendered += FlpTempoChange(
                    clip.position.coerceAtLeast(0L),
                    mapper.first(firstValue)
                )

                for (point in channel.points) {
                    if (point.beat <= sourceStart + 1e-9 || point.beat > sourceEnd + 1e-9) continue
                    val relativeBeats = point.beat - sourceStart
                    val tick = clip.position + (relativeBeats * 96.0).roundToLong()
                    if (clip.length > 0L && tick > clip.position + clip.length) continue
                    rendered += FlpTempoChange(tick.coerceAtLeast(0L), mapper.first(point.value))
                }
            }
        }

        val normalized = normalizeChanges(initialTempo, rendered)
        return FlpTempoScan(normalized, tempoChannels.size, usedCustomRange)
    }

    private fun parseAutomationLinks(payload: ByteArray, tempoTargets: MutableSet<Int>) {
        var offset = 0
        while (offset + 20 <= payload.size) {
            val channelIid = u32(payload, offset + 2).toInt()
            val parameter = u16(payload, offset + 8)
            val destination = u16(payload, offset + 10)
            if (destination == MASTER_DESTINATION && parameter == MASTER_TEMPO_PARAMETER) {
                tempoTargets += channelIid
            }
            offset += 20
        }
    }

    private fun parseAutomationPoints(payload: ByteArray, destination: MutableList<RawAutomationPoint>) {
        if (payload.size < 21) return
        val count = u32(payload, 17).coerceAtMost(100_000L).toInt()
        var offset = 21
        var beat = 0.0
        repeat(count) {
            if (offset + 24 > payload.size) return
            val increment = f64(payload, offset)
            val value = f64(payload, offset + 8)
            val tension = f32(payload, offset + 16)
            if (increment.isFinite() && value.isFinite()) {
                beat += increment.coerceAtLeast(0.0)
                destination += RawAutomationPoint(beat, value.coerceIn(0.0, 1.0), tension)
            }
            offset += 24
        }
    }

    private fun parsePlaylist(payload: ByteArray, destination: MutableList<RawChannelClip>) {
        val recordSize = when {
            payload.isNotEmpty() && payload.size % 60 == 0 -> 60
            payload.isNotEmpty() && payload.size % 32 == 0 -> 32
            else -> return
        }
        var offset = 0
        while (offset + recordSize <= payload.size) {
            val patternBase = u16(payload, offset + 4)
            val itemIndex = u16(payload, offset + 6)
            if (itemIndex <= patternBase) {
                destination += RawChannelClip(
                    position = u32(payload, offset),
                    length = u32(payload, offset + 8),
                    channelIid = itemIndex,
                    startOffsetBeats = f32(payload, offset + 24).toDouble().takeIf { it.isFinite() } ?: 0.0,
                    muted = u16(payload, offset + 18) and 0x2000 != 0
                )
            }
            offset += recordSize
        }
    }

    /**
     * Automation Clip Min/Max use the channel's two basic normalized controls.
     * Default tempo clips map 0..1 to 60..180 BPM. When a plausible custom range
     * is present, map it against FL Studio's 10..522 BPM master-tempo span.
     */
    private fun tempoMapper(
        channel: RawTempoChannel,
        initialTempo: Double
    ): Pair<(Double) -> Double, Boolean> {
        val minimum = channel.minimumRaw
        val maximum = channel.maximumRaw
        val plausible = minimum != null && maximum != null &&
            minimum in 0..12_800 && maximum in 0..12_800 && minimum < maximum &&
            minimum < 6_000

        if (plausible) {
            val custom: (Double) -> Double = { value ->
                val normalized = minimum!!.toDouble() / 12_800.0 +
                    value.coerceIn(0.0, 1.0) * (maximum!! - minimum).toDouble() / 12_800.0
                (10.0 + normalized * 512.0).coerceIn(10.0, 522.0)
            }
            val firstValue = channel.points.firstOrNull()?.value ?: 0.0
            val defaultAtFirst = defaultTempo(firstValue)
            val customAtFirst = custom(firstValue)
            val customLooksBetter = abs(customAtFirst - initialTempo) <=
                abs(defaultAtFirst - initialTempo) + 2.0
            if (customLooksBetter) return custom to true
        }

        return ({ value: Double -> defaultTempo(value) }) to false
    }

    private fun defaultTempo(value: Double): Double =
        ((value.coerceIn(0.0, 1.0) + 0.5) * 120.0).coerceIn(10.0, 522.0)

    private fun valueAt(points: List<RawAutomationPoint>, beat: Double): Double {
        if (points.isEmpty()) return 0.0
        if (beat <= points.first().beat) return points.first().value
        for (index in 0 until points.lastIndex) {
            val left = points[index]
            val right = points[index + 1]
            if (beat <= right.beat) {
                val span = right.beat - left.beat
                if (span <= 1e-9) return right.value
                val ratio = ((beat - left.beat) / span).coerceIn(0.0, 1.0)
                return left.value + (right.value - left.value) * ratio
            }
        }
        return points.last().value
    }

    private fun normalizeChanges(
        initialTempo: Double,
        raw: List<FlpTempoChange>
    ): List<FlpTempoChange> {
        val byTick = linkedMapOf<Long, Double>()
        byTick[0L] = initialTempo.coerceIn(10.0, 522.0)
        raw.sortedBy { it.tick }.forEach { change ->
            byTick[change.tick.coerceAtLeast(0L)] = roundTempo(change.bpm)
        }

        val result = mutableListOf<FlpTempoChange>()
        for ((tick, bpm) in byTick.entries.sortedBy { it.key }) {
            val last = result.lastOrNull()
            if (last == null || abs(last.bpm - bpm) >= 0.001) {
                result += FlpTempoChange(tick, bpm)
            }
        }
        return result.ifEmpty { listOf(FlpTempoChange(0L, initialTempo)) }
    }

    private fun roundTempo(value: Double): Double =
        (value * 1000.0).roundToLong() / 1000.0

    private fun isTempoName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return "tempo" in normalized || "bpm" in normalized
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf16 = bytes.size >= 4 && bytes.indices.count { index ->
            index % 2 == 1 && bytes[index] == 0.toByte()
        } > bytes.size / 6
        return (if (utf16) String(bytes, Charsets.UTF_16LE) else String(bytes, Charsets.UTF_8))
            .trimEnd('\u0000')
            .trim()
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("Arquivo terminou antes do esperado.")
            offset += read
        }
        return result
    }

    private fun readU16(input: InputStream): Int {
        val bytes = readExact(input, 2)
        return u16(bytes, 0)
    }

    private fun readU32(input: InputStream): Long = u32(readExact(input, 4), 0)

    private fun readVarInt(input: InputStream): Pair<Long, Long> {
        var result = 0L
        var shift = 0
        var count = 0L
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("VarInt FLP incompleto.")
            count++
            require(count <= 10L) { "VarInt FLP inválido." }
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result to count
            shift += 7
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                if (input.read() < 0) throw EOFException("Arquivo terminou antes do esperado.")
                remaining--
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun i32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun f32(bytes: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun f64(bytes: ByteArray, offset: Int): Double =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double
}
