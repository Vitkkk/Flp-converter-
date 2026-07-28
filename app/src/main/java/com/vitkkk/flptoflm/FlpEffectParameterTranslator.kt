package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max

internal data class EffectParameterTranslation(
    val payload: ByteArray,
    val adapted: Boolean,
    val detail: String? = null
)

/**
 * Translates native FL Studio effect state into the closest FL Studio Mobile
 * PRMS values. Only semantically equivalent controls are changed; parameters
 * that have no safe equivalent retain the Mobile module's default value.
 */
internal object FlpEffectParameterTranslator {
    private data class PrmsBlock(
        val dataOffset: Int,
        val values: FloatArray
    )

    fun translate(
        template: MobileEffectTemplate,
        slot: FlpEffectSlot,
        uniqueModuleId: Int
    ): EffectParameterTranslation {
        val basePayload = template.collapsedPayload(uniqueModuleId)
        val pluginData = slot.pluginData
            ?: return EffectParameterTranslation(basePayload, false, "estado do plugin ausente")
        val pluginName = slot.bestName
            ?: return EffectParameterTranslation(basePayload, false, "nome do plugin ausente")

        return try {
            val block = readPrms(basePayload)
            val values = block.values.copyOf()
            val sourceInts = readInts(pluginData)
            val slotMix = normalizeSlotMix(slot.mix)

            val adapted = when (normalize(pluginName)) {
                "fruity reeverb 2" -> mapReeverb2(sourceInts, pluginData, values, slotMix)
                "fruity reeverb" -> mapReeverb(sourceInts, values, slotMix)
                "fruity balance" -> mapBalance(sourceInts, values)
                "fruity 7 band eq" -> mapGraphicEq(sourceInts, values)
                "fruity parametric eq 2" -> mapParametricEq2(sourceInts, values)
                "fruity parametric eq" -> mapParametricEq(sourceInts, values)
                "fruity compressor" -> mapCompressor(sourceInts, values)
                "fruity limiter" -> mapLimiter(sourceInts, values)
                "fruity delay 2" -> mapDelay2(sourceInts, values, slotMix)
                "fruity delay 3" -> mapDelay3(sourceInts, values, slotMix)
                "fruity chorus" -> mapChorus(sourceInts, values, slotMix)
                "fruity flanger" -> mapFlanger(sourceInts, values, slotMix)
                "fruity phaser" -> mapPhaser(sourceInts, values, slotMix)
                "fruity fast dist" -> mapFastDist(sourceInts, values, slotMix)
                "fruity blood overdrive" -> mapBloodOverdrive(sourceInts, values, slotMix)
                "fruity filter" -> mapFilter(sourceInts, values)
                "fruity free filter" -> mapFreeFilter(sourceInts, values)
                "fruity stereo enhancer" -> mapStereoEnhancer(sourceInts, values)
                "fruity soft clipper" -> mapSoftClipper(sourceInts, values, slotMix)
                else -> false
            }

            if (!adapted) {
                EffectParameterTranslation(basePayload, false, "mapeamento de parâmetros indisponível")
            } else {
                val output = basePayload.copyOf()
                writePrms(output, block.dataOffset, values)
                EffectParameterTranslation(output, true)
            }
        } catch (error: Throwable) {
            EffectParameterTranslation(
                basePayload,
                false,
                error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun mapReeverb2(
        source: IntArray,
        raw: ByteArray,
        target: FloatArray,
        slotMix: Double
    ): Boolean {
        if (source.size < 16 || target.size < 19) return false

        // Desktop: version, lowcut, highcut, predelay, room, diffusion,
        // decay, damping, bass, cross, stereo, dry, ER, wet, mod speed, mod.
        target[1] = logNorm(source[2].toDouble() * 100.0, 500.0, 22_050.0)
        target[2] = logNorm(source[1].toDouble(), 20.0, 3_000.0)
        target[3] = unit(source[4], 100.0)
        target[4] = unit(source[5], 100.0)
        target[5] = unit(source[6], 100.0)
        target[6] = logNorm(source[7].toDouble() * 100.0, 500.0, 22_050.0)
        target[7] = unit(source[11], 128.0)
        target[8] = unit(source[12], 128.0)
        target[9] = clamp01(unitDouble(source[13], 128.0) * slotMix).toFloat()

        target[10] = if (raw.size >= 66 && raw[65].toInt() != 0) 1f else 0f
        target[11] = unit(source[3], 19_200.0)
        target[12] = if (raw.size >= 65 && raw[64].toInt() != 0) 1f else 0f
        target[13] = unit(source[15], 100.0)
        target[14] = unit(source[14], 100.0)
        target[15] = unit(source[8], 100.0)
        target[16] = if (abs(source[9]) <= 100) {
            unit(source[9], 100.0)
        } else {
            logNorm(source[9].toDouble(), 50.0, 5_000.0)
        }
        target[17] = bipolar(source[10], 128.0)
        return true
    }

    private fun mapReeverb(
        source: IntArray,
        target: FloatArray,
        slotMix: Double
    ): Boolean {
        if (source.size < 11 || target.size < 16) return false

        // Mobile Reverb order: Decay, Damp, Mix, ER, Size, Predelay,
        // Diffusion, Width, Low Cut, High Cut, Mod Speed, Mod, Wet, Dry, Pan.
        target[1] = unitFrom65536(source[7])
        target[2] = unitFrom65536(source[8])
        target[3] = clamp01(unitDouble(source[10], 65_536.0) * slotMix).toFloat()
        target[5] = unitFrom65536(source[4])
        target[6] = unitFrom65536(source[3])
        target[7] = unitFrom65536(source[5])
        target[8] = 0.5f
        target[9] = logNorm(
            linearRange(source[1], 0.0, 65_536.0, 20.0, 3_000.0),
            20.0,
            3_000.0
        )
        target[10] = logNorm(
            linearRange(source[2], 0.0, 65_536.0, 500.0, 22_050.0),
            500.0,
            22_050.0
        )
        target[13] = clamp01(unitDouble(source[10], 65_536.0) * slotMix).toFloat()
        target[14] = unitFrom65536(source[9])
        target[15] = 0.5f
        return true
    }

    private fun mapBalance(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 2 || target.size < 3) return false
        val linear = (source[1].toLong() and 0xffff_ffffL).toDouble() / 256.0
        target[1] = if (linear <= 0.0) {
            0f
        } else {
            clamp01(0.7379573 + log2(linear) * 0.08).toFloat()
        }
        target[2] = bipolar(source[0], 128.0)
        return true
    }

    private fun mapGraphicEq(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 8 || target.size < 9) return false
        val bands = DoubleArray(7) { index -> source[index + 1] / 100.0 }
        for (mobileBand in 0 until 8) {
            val position = mobileBand * 6.0 / 7.0
            val left = position.toInt().coerceIn(0, 6)
            val right = (left + 1).coerceAtMost(6)
            val fraction = position - left
            val db = bands[left] * (1.0 - fraction) + bands[right] * fraction
            target[mobileBand + 1] = dbGain(db, 18.0)
        }
        return true
    }

    private fun mapParametricEq2(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 37 || target.size < 21) return false
        return mapFourEqBands(source, target, 1, 8, 15, 22)
    }

    private fun mapParametricEq(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 29 || target.size < 21) return false
        return mapFourEqBands(source, target, 0, 7, 14, 21)
    }

    private fun mapFourEqBands(
        source: IntArray,
        target: FloatArray,
        gainStart: Int,
        frequencyStart: Int,
        widthStart: Int,
        typeStart: Int
    ): Boolean {
        val selected = intArrayOf(0, 2, 4, 6)
        for (mobileBand in selected.indices) {
            val desktopBand = selected[mobileBand]
            val outputBase = 1 + mobileBand * 4
            val gainDb = source[gainStart + desktopBand] / 100.0
            target[outputBase] = dbGain(gainDb, 18.0)
            target[outputBase + 1] = unitFrom65536(source[frequencyStart + desktopBand])
            target[outputBase + 2] = unitFrom65536(source[widthStart + desktopBand])
            target[outputBase + 3] = 0.51f
            target[17 + mobileBand] = when (source[typeStart + desktopBand]) {
                5 -> 0.5f
                7 -> 1.0f
                else -> 0.0f
            }
        }
        return true
    }

    private fun mapCompressor(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 6 || target.size < 10) return false
        val start = if (source.size >= 7 && source[0] in 0..16) 1 else 0
        if (source.size < start + 6) return false

        val thresholdDb = source[start] / 10.0
        val ratio = max(1.0, source[start + 1] / 10.0)
        val gainDb = source[start + 2] / 10.0
        val attackSeconds = max(0.0001, source[start + 3] / 10_000.0)
        val releaseSeconds = max(0.001, source[start + 4] / 1_000.0)
        val type = source[start + 5]

        target[1] = linearNorm(thresholdDb, -60.0, 0.0)
        target[2] = logNorm(ratio, 1.0, 20.0)
        target[3] = logNorm(attackSeconds, 0.0001, 1.0)
        target[5] = logNorm(releaseSeconds, 0.01, 2.0)
        target[6] = dbGain(gainDb, 24.0)
        target[8] = when (type and 3) {
            0 -> 0f
            1 -> 0.4f
            2 -> 0.65f
            else -> 1f
        }
        return true
    }

    private fun mapLimiter(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 9 || target.size < 5) return false
        target[1] = parameterUnit(source[1])
        target[2] = parameterUnit(source[3])
        target[3] = parameterUnit(source[6])
        target[4] = 1f
        return true
    }

    private fun mapDelay2(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 8 || target.size < 5) return false
        target[1] = parameterUnit(source[4])
        target[2] = parameterUnit(source[3])
        target[3] = bipolar(source[5], 1_024.0)
        val dry = parameterUnit(source[2]).toDouble()
        target[4] = clamp01((1.0 - dry) * slotMix).toFloat()
        return true
    }

    private fun mapDelay3(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 27 || target.size < 5) return false
        target[1] = parameterUnit(source[5])
        target[2] = parameterUnit(source[15])
        target[3] = bipolar(source[6], 65_536.0)
        val wet = parameterUnit(source[24]).toDouble()
        val dry = parameterUnit(source[26]).toDouble()
        val wetRatio = if (wet + dry > 0.0) wet / (wet + dry) else wet
        target[4] = clamp01(wetRatio * slotMix).toFloat()
        return true
    }

    private fun mapChorus(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 13 || target.size < 5) return false
        val averageRate = (source[4].toDouble() + source[5] + source[6]) / 3.0
        target[1] = parameterUnit(averageRate.toInt())
        target[2] = parameterUnit(source[2])
        target[3] = parameterUnit(source[3])
        target[4] = clamp01((if (source[12] != 0) 1.0 else 0.6) * slotMix).toFloat()
        return true
    }

    private fun mapFlanger(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 13 || target.size < 12) return false
        target[1] = parameterUnit(source[3])
        target[2] = parameterUnit(source[6])
        target[3] = parameterUnit(source[2])
        target[4] = parameterUnit(source[7])
        target[5] = parameterUnit(source[1])
        target[6] = parameterUnit(source[4])
        val dry = parameterUnit(source[10]).toDouble()
        val wet = parameterUnit(source[11]).toDouble()
        val wetRatio = if (dry + wet > 0.0) wet / (dry + wet) else wet
        target[7] = clamp01(wetRatio * slotMix).toFloat()
        target[8] = boolFloat(source[9] != 0)
        target[9] = boolFloat(source[8] != 0)
        target[10] = 0f
        return true
    }

    private fun mapPhaser(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 10 || target.size < 10) return false
        target[1] = parameterUnit(source[1])
        target[3] = parameterUnit(abs(source[3] - source[2]))
        target[4] = parameterUnit(source[7])
        target[5] = parameterUnit(source[4])
        target[6] = parameterUnit(source[5])
        target[7] = clamp01(parameterUnit(source[8]).toDouble() * slotMix).toFloat()
        return true
    }

    private fun mapFastDist(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 5 || target.size < 7) return false
        target[1] = parameterUnit(source[0])
        target[2] = parameterUnit(source[1])
        target[3] = clamp01(parameterUnit(source[3]).toDouble() * slotMix).toFloat()
        target[4] = parameterUnit(source[4])
        target[5] = if (source[2] == 0) 0f else 1f
        return true
    }

    private fun mapBloodOverdrive(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 7 || target.size < 7) return false
        val start = if (source.size >= 9) 1 else 0
        target[1] = parameterUnit(source[start + 2])
        target[2] = parameterUnit(source[start + 1])
        target[3] = slotMix.toFloat()
        target[4] = parameterUnit(source[start + 5])
        target[5] = if (source[start + 3] != 0) 1f else 0f
        return true
    }

    private fun mapFilter(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 7 || target.size < 5) return false
        target[1] = parameterUnit(source[1])
        target[2] = parameterUnit(source[2])
        target[3] = when {
            source[5] > source[3] && source[5] >= source[4] -> 1f
            source[4] > source[3] -> 0.5f
            else -> 0f
        }
        target[4] = if (source[6] != 0) 1f else 0f
        return true
    }

    private fun mapFreeFilter(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 5 || target.size < 5) return false
        target[1] = parameterUnit(source[2])
        target[2] = parameterUnit(source[3])
        target[3] = when (source[1]) {
            1, 3 -> 1f
            2 -> 0.5f
            else -> 0f
        }
        return true
    }

    private fun mapStereoEnhancer(source: IntArray, target: FloatArray): Boolean {
        if (source.size < 6 || target.size < 7) return false
        target[1] = clamp01(abs(source[2]).toDouble() / 128.0).toFloat()
        target[2] = bipolar(source[3], 128.0)
        target[3] = 0.5f
        target[4] = bipolar(source[3], 1_024.0)
        target[5] = bipolar(source[0], 128.0)
        target[6] = if (source[4] == 0) 0f else 1f
        return true
    }

    private fun mapSoftClipper(source: IntArray, target: FloatArray, slotMix: Double): Boolean {
        if (source.size < 2 || target.size < 21) return false
        target[3] = parameterUnit(source[0])
        target[4] = parameterUnit(source[1])
        target[8] = slotMix.toFloat()
        return true
    }

    private fun readPrms(modulePayload: ByteArray): PrmsBlock {
        require(modulePayload.size >= 8) { "Payload RMOd incompleto." }
        var offset = 8
        while (offset < modulePayload.size) {
            require(offset + 8 <= modulePayload.size) { "Subchunk de efeito incompleto." }
            val type = String(modulePayload, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(modulePayload, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            require(length >= 0 && offset + 8L + length <= modulePayload.size.toLong()) {
                "Tamanho inválido no subchunk $type."
            }
            if (type == "PRMS") {
                require(length % 4 == 0) { "PRMS não está alinhado em float32." }
                val dataOffset = offset + 8
                val values = FloatArray(length / 4)
                val buffer = ByteBuffer.wrap(modulePayload, dataOffset, length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (index in values.indices) values[index] = buffer.float
                return PrmsBlock(dataOffset, values)
            }
            offset += 8 + length
        }
        error("Chunk PRMS não encontrado no módulo Mobile.")
    }

    private fun writePrms(target: ByteArray, offset: Int, values: FloatArray) {
        val buffer = ByteBuffer.wrap(target, offset, values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) {
            buffer.putFloat(if (value.isFinite()) value.coerceIn(0f, 1f) else 0f)
        }
    }

    private fun readInts(bytes: ByteArray): IntArray {
        val count = bytes.size / 4
        val output = IntArray(count)
        val buffer = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) output[index] = buffer.int
        return output
    }

    private fun normalizeSlotMix(raw: Int?): Double {
        if (raw == null) return 1.0
        if (raw <= 0) return 0.0
        return when {
            raw <= 1 -> raw.toDouble()
            raw <= 128 -> raw / 128.0
            raw <= 1_000 -> raw / 1_000.0
            raw <= 12_800 -> raw / 12_800.0
            else -> raw.coerceAtMost(65_536) / 65_536.0
        }.coerceIn(0.0, 1.0)
    }

    private fun parameterUnit(value: Int): Float {
        if (value <= 0) return 0f
        val positive = value.toLong() and 0xffff_ffffL
        return when {
            positive <= 100L -> positive / 100.0
            positive <= 128L -> positive / 128.0
            positive <= 1_024L -> positive / 1_024.0
            positive <= 12_800L -> positive / 12_800.0
            else -> positive.coerceAtMost(65_536L) / 65_536.0
        }.toFloat().coerceIn(0f, 1f)
    }

    private fun unit(value: Int, maximum: Double): Float =
        clamp01((value.toLong() and 0xffff_ffffL).toDouble() / maximum).toFloat()

    private fun unitDouble(value: Int, maximum: Double): Double =
        clamp01((value.toLong() and 0xffff_ffffL).toDouble() / maximum)

    private fun unitFrom65536(value: Int): Float = unit(value, 65_536.0)

    private fun bipolar(value: Int, magnitude: Double): Float =
        clamp01(
            0.5 + value.coerceIn(-magnitude.toInt(), magnitude.toInt()) / (magnitude * 2.0)
        ).toFloat()

    private fun dbGain(db: Double, rangeDb: Double): Float =
        clamp01(0.5 + db / (rangeDb * 2.0)).toFloat()

    private fun linearNorm(value: Double, minimum: Double, maximum: Double): Float =
        clamp01((value - minimum) / (maximum - minimum)).toFloat()

    private fun logNorm(value: Double, minimum: Double, maximum: Double): Float {
        val safe = value.coerceIn(minimum, maximum)
        return clamp01(ln(safe / minimum) / ln(maximum / minimum)).toFloat()
    }

    private fun linearRange(
        value: Int,
        sourceMinimum: Double,
        sourceMaximum: Double,
        targetMinimum: Double,
        targetMaximum: Double
    ): Double {
        val normalized = ((value.toLong() and 0xffff_ffffL).toDouble() - sourceMinimum) /
            (sourceMaximum - sourceMinimum)
        return targetMinimum + normalized.coerceIn(0.0, 1.0) * (targetMaximum - targetMinimum)
    }

    private fun boolFloat(value: Boolean): Float = if (value) 1f else 0f

    private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
