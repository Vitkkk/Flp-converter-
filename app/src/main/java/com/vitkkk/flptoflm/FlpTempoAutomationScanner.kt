package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class FlpTempoChange(val tick: Long, val bpm: Double)

data class FlpTempoScan(
    val changes: List<FlpTempoChange>,
    val tempoAutomationChannels: Int,
    val customRangeDetected: Boolean
) {
    val hasChanges: Boolean
        get() = changes.size > 1 && changes.drop(1).any {
            abs(it.bpm - changes.first().bpm) >= 0.001
        }

    companion object {
        val EMPTY = FlpTempoScan(emptyList(), 0, false)
    }
}

private data class RawAutomationPoint(val beat: Double, val value: Double, val tension: Float)

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

/** Independent pass that resolves Master Tempo Automation Clips into FLP ticks. */
object FlpTempoAutomationScanner {
    private const val EVENT_CHANNEL_TYPE = 21
    private const val EVENT_CHANNEL_NEW = 64
    private const val EVENT_CHANNEL_NAME = 192
    private const val EVENT_BASIC_CHANNEL_PARAMS = 219
    private const val EVENT_AUTOMATION_LINKS = 227
    private const val EVENT_PLAYLIST = 233
    private const val EVENT_AUTOMATION_DATA = 234

    private const val AUTOMATION_CHANNEL = 5
    private const val MASTER_DESTINATION = 0x4000
    private const val MASTER_TEMPO_PARAMETER = 5

    fun scan(bytes: ByteArray, initialTempo: Double): FlpTempoScan =
        ByteArrayInputStream(bytes).use { scan(it, initialTempo) }

    fun scan(input: InputStream, initialTempo: Double): FlpTempoScan {
        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada durante a leitura de BPM Change."
        }
        val headerLength = readU32(input).toInt()
        require(headerLength >= 6) { "Cabeçalho FLP inválido." }
        val header = readExact(input, headerLength)
        val sourcePpq = u16(header, 4).coerceAtLeast(1)

        require(String(readExact(input, 4), Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado durante a leitura de BPM Change."
        }
        var remaining = readU32(input)
        val channels = linkedMapOf<Int, RawTempoChannel>()
        val tempoTargets = linkedSetOf<Int>()
        val clips = mutableListOf<RawChannelClip>()
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
                    if (id == EVENT_CHANNEL_TYPE) currentChannel?.let {
                        channels.getOrPut(it) { RawTempoChannel(it) }.type = value
                    }
                }
                id < 128 -> {
                    val value = readU16(input)
                    remaining -= 2
                    if (id == EVENT_CHANNEL_NEW) {
                        currentChannel = value
                        channels.getOrPut(value) { RawTempoChannel(value) }
                    }
                }
                id < 192 -> {
                    skipFully(input, 4)
                    remaining -= 4
                }
                else -> {
                    val (length, varBytes) = readVarInt(input)
                    remaining -= varBytes
                    require(length <= remaining && length <= Int.MAX_VALUE) { "Evento $id inválido." }
                    val payload = readExact(input, length.toInt())
                    remaining -= length
                    when (id) {
                        EVENT_CHANNEL_NAME -> currentChannel?.let {
                            channels.getOrPut(it) { RawTempoChannel(it) }.name = decodeText(payload)
                        }
                        EVENT_BASIC_CHANNEL_PARAMS -> currentChannel?.let {
                            if (payload.size >= 8) {
                                channels.getOrPut(it) { RawTempoChannel(it) }.apply {
                                    minimumRaw = i32(payload, 0)
                                    maximumRaw = i32(payload, 4)
                                }
                            }
                        }
                        EVENT_AUTOMATION_LINKS -> parseLinks(payload, tempoTargets)
                        EVENT_AUTOMATION_DATA -> currentChannel?.let {
                            parsePoints(payload, channels.getOrPut(it) { RawTempoChannel(it) }.points)
                        }
                        EVENT_PLAYLIST -> parsePlaylist(payload, clips)
                    }
                }
            }
            require(remaining >= 0) { "Estrutura FLP inválida na leitura de BPM Change." }
        }

        val tempoChannels = channels.values.filter {
            it.iid in tempoTargets || (it.type == AUTOMATION_CHANNEL && isTempoName(it.name))
        }
        if (tempoChannels.isEmpty()) {
            return FlpTempoScan(listOf(FlpTempoChange(0, initialTempo)), 0, false)
        }

        val rawChanges = mutableListOf<FlpTempoChange>()
        var customRange = false
        for (channel in tempoChannels) {
            if (channel.points.isEmpty()) continue
            val (mapTempo, custom) = tempoMapper(channel, initialTempo)
            customRange = customRange || custom
            val placements = clips.filter { !it.muted && it.channelIid == channel.iid }

            if (placements.isEmpty()) {
                channel.points.forEach {
                    rawChanges += FlpTempoChange(
                        (it.beat * sourcePpq).roundToLong().coerceAtLeast(0),
                        mapTempo(it.value)
                    )
                }
                continue
            }

            for (clip in placements) {
                val sourceStart = clip.startOffsetBeats.coerceAtLeast(0.0)
                val sourceEnd = sourceStart + clip.length.toDouble() / sourcePpq
                rawChanges += FlpTempoChange(clip.position, mapTempo(valueAt(channel.points, sourceStart)))

                channel.points.forEach { point ->
                    if (point.beat <= sourceStart + 1e-9 || point.beat > sourceEnd + 1e-9) return@forEach
                    val tick = clip.position + ((point.beat - sourceStart) * sourcePpq).roundToLong()
                    if (clip.length <= 0 || tick <= clip.position + clip.length) {
                        rawChanges += FlpTempoChange(tick.coerceAtLeast(0), mapTempo(point.value))
                    }
                }
            }
        }

        return FlpTempoScan(
            normalizeChanges(initialTempo, rawChanges),
            tempoChannels.size,
            customRange
        )
    }

    private fun parseLinks(payload: ByteArray, targets: MutableSet<Int>) {
        var offset = 0
        while (offset + 20 <= payload.size) {
            val channel = u32(payload, offset + 2).toInt()
            val parameter = u16(payload, offset + 8)
            val destination = u16(payload, offset + 10)
            if (destination == MASTER_DESTINATION && parameter == MASTER_TEMPO_PARAMETER) targets += channel
            offset += 20
        }
    }

    private fun parsePoints(payload: ByteArray, output: MutableList<RawAutomationPoint>) {
        if (payload.size < 21) return
        val count = u32(payload, 17).coerceAtMost(100_000).toInt()
        var offset = 21
        var beat = 0.0
        repeat(count) {
            if (offset + 24 > payload.size) return
            val increment = f64(payload, offset)
            val value = f64(payload, offset + 8)
            val tension = f32(payload, offset + 16)
            if (increment.isFinite() && value.isFinite()) {
                beat += increment.coerceAtLeast(0.0)
                output += RawAutomationPoint(beat, value.coerceIn(0.0, 1.0), tension)
            }
            offset += 24
        }
    }

    private fun parsePlaylist(payload: ByteArray, output: MutableList<RawChannelClip>) {
        val size = when {
            payload.isNotEmpty() && payload.size % 60 == 0 -> 60
            payload.isNotEmpty() && payload.size % 32 == 0 -> 32
            else -> return
        }
        var offset = 0
        while (offset + size <= payload.size) {
            val patternBase = u16(payload, offset + 4)
            val item = u16(payload, offset + 6)
            if (item <= patternBase) {
                output += RawChannelClip(
                    position = u32(payload, offset),
                    length = u32(payload, offset + 8),
                    channelIid = item,
                    startOffsetBeats = f32(payload, offset + 24).toDouble().takeIf(Double::isFinite) ?: 0.0,
                    muted = u16(payload, offset + 18) and 0x2000 != 0
                )
            }
            offset += size
        }
    }

    private fun tempoMapper(
        channel: RawTempoChannel,
        initialTempo: Double
    ): Pair<(Double) -> Double, Boolean> {
        val min = channel.minimumRaw
        val max = channel.maximumRaw
        val plausible = min != null && max != null && min in 0..12_800 &&
            max in 0..12_800 && min < max && min < 6_000

        if (plausible) {
            val custom: (Double) -> Double = { value ->
                val normalized = min!!.toDouble() / 12_800.0 +
                    value.coerceIn(0.0, 1.0) * (max!! - min).toDouble() / 12_800.0
                (10.0 + normalized * 512.0).coerceIn(10.0, 522.0)
            }
            val first = channel.points.firstOrNull()?.value ?: 0.0
            if (abs(custom(first) - initialTempo) <= abs(defaultTempo(first) - initialTempo) + 2.0) {
                return custom to true
            }
        }
        return ({ value: Double -> defaultTempo(value) }) to false
    }

    private fun defaultTempo(value: Double): Double =
        ((value.coerceIn(0.0, 1.0) + 0.5) * 120.0).coerceIn(10.0, 522.0)

    private fun valueAt(points: List<RawAutomationPoint>, beat: Double): Double {
        if (beat <= points.first().beat) return points.first().value
        for (index in 0 until points.lastIndex) {
            val left = points[index]
            val right = points[index + 1]
            if (beat <= right.beat) {
                val width = right.beat - left.beat
                if (width <= 1e-9) return right.value
                val fraction = ((beat - left.beat) / width).coerceIn(0.0, 1.0)
                return left.value + (right.value - left.value) * fraction
            }
        }
        return points.last().value
    }

    private fun normalizeChanges(initialTempo: Double, raw: List<FlpTempoChange>): List<FlpTempoChange> {
        val byTick = linkedMapOf<Long, Double>()
        byTick[0] = initialTempo.coerceIn(10.0, 522.0)
        raw.sortedBy { it.tick }.forEach { byTick[it.tick.coerceAtLeast(0)] = roundTempo(it.bpm) }
        val result = mutableListOf<FlpTempoChange>()
        byTick.entries.sortedBy { it.key }.forEach { (tick, bpm) ->
            if (result.lastOrNull()?.let { abs(it.bpm - bpm) < 0.001 } != true) {
                result += FlpTempoChange(tick, bpm)
            }
        }
        return result
    }

    private fun roundTempo(value: Double): Double = (value * 1000).roundToLong() / 1000.0

    private fun isTempoName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return "tempo" in normalized || "bpm" in normalized
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf16 = bytes.size >= 4 && bytes.indices.count {
            it % 2 == 1 && bytes[it] == 0.toByte()
        } > bytes.size / 6
        return (if (utf16) String(bytes, Charsets.UTF_16LE) else String(bytes, Charsets.UTF_8))
            .trimEnd('\u0000').trim()
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
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun i32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun f32(bytes: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun f64(bytes: ByteArray, offset: Int): Double =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double
}
