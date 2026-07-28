package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Corrections for Mobile parameter layouts calibrated after the first 0.5 pass.
 * Effects not listed here continue through the broad translator unchanged.
 */
internal object MobileEffectSettingsTranslatorV2 {
    private data class EqBand(
        val gainDb: Double,
        val frequency: Float,
        val width: Float,
        val type: Int
    )

    fun translate(slot: FlpEffectSlot): TranslatedMobileEffect? {
        val base = MobileEffectSettingsTranslator.translate(slot) ?: return null
        val data = slot.pluginData ?: return base
        val name = slot.bestName?.let(::normalize) ?: return base

        return try {
            when (name) {
                "fruity reeverb 2" -> base.copy(
                    parameterUpdates = reeverb2(data),
                    quality = EffectSettingsQuality.DIRECT,
                    description = "Reeverb 2: filtros, sala, diffusion, decay, damping, níveis, predelay, modulação e estéreo"
                )
                "fruity reeverb" -> base.copy(
                    parameterUpdates = reeverb(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Reeverb: controles equivalentes preservados no Reverb Mobile"
                )
                "fruity compressor" -> base.copy(
                    parameterUpdates = compressor(data),
                    quality = EffectSettingsQuality.DIRECT,
                    description = "Compressor: threshold, ratio, attack, release, gain, knee e modo"
                )
                "fruity parametric eq 2" -> correctedEq(base, parametricEq2(data), "Parametric EQ 2")
                "fruity parametric eq" -> correctedEq(base, parametricEq(data), "Parametric EQ")
                "fruity delay 3" -> base.copy(
                    parameterUpdates = delay3(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Delay 3: time, feedback, stereo offset e proporção wet/dry adaptados ao Tape Delay"
                )
                else -> base
            }
        } catch (_: Throwable) {
            base.copy(
                quality = EffectSettingsQuality.DEFAULT,
                description = "bloco de parâmetros não reconhecido; preset Mobile padrão mantido"
            )
        }
    }

    private fun reeverb2(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 16)
        val updates = linkedMapOf<Int, Float>()
        updates[1] = logRange(p[2] * 100.0, 500.0, 22_050.0)
        updates[2] = logRange(p[1].toDouble(), 20.0, 3_000.0)
        updates[3] = norm(p[4], 100.0)
        updates[4] = norm(p[5], 100.0)
        updates[5] = norm(p[6], 100.0)
        updates[6] = logRange(p[7] * 100.0, 500.0, 22_050.0)
        updates[7] = norm(p[11], 128.0)
        updates[8] = norm(p[12], 128.0)
        updates[9] = norm(p[13], 128.0)
        updates[10] = if (data.size >= 66 && data[65].toInt() != 0) 1f else 0f
        updates[11] = norm(p[3], 19_200.0)
        updates[12] = if (data.size >= 65 && data[64].toInt() != 0) 1f else 0f
        updates[13] = norm(p[15], 100.0)
        updates[14] = norm(p[14], 100.0)
        updates[15] = norm(p[8], 100.0)
        updates[16] = if (abs(p[9]) <= 100) {
            norm(p[9], 100.0)
        } else {
            logRange(p[9].toDouble(), 50.0, 5_000.0)
        }
        updates[17] = signedCenter(p[10], 128.0)
        updates[18] = 0.5f
        return updates
    }

    private fun reeverb(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 11)
        return linkedMapOf(
            1 to norm(p[7], 65_536.0),
            2 to norm(p[8], 65_536.0),
            3 to norm(p[10], 65_536.0),
            5 to norm(p[4], 65_536.0),
            6 to norm(p[3], 65_536.0),
            7 to norm(p[5], 65_536.0),
            8 to 0.5f,
            9 to logRange(linearMap(p[1], 0.0, 65_536.0, 20.0, 3_000.0), 20.0, 3_000.0),
            10 to logRange(linearMap(p[2], 0.0, 65_536.0, 500.0, 22_050.0), 500.0, 22_050.0),
            13 to norm(p[10], 65_536.0),
            14 to norm(p[9], 65_536.0),
            15 to 0.5f
        )
    }

    private fun compressor(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 7)
        val thresholdDb = p[1] / 10.0
        val ratio = max(1.0, p[2] / 10.0)
        val gainDb = p[3] / 10.0
        val attackSeconds = max(0.0001, p[4] / 10_000.0)
        val releaseSeconds = max(0.001, p[5] / 1_000.0)
        val type = p[6]
        val knee = when (type and 3) {
            0 -> 0f
            1 -> 0.4f
            2 -> 0.65f
            else -> 1f
        }

        return linkedMapOf(
            1 to linearRange(thresholdDb, -60.0, 0.0),
            2 to logRange(ratio, 1.0, 20.0),
            3 to logRange(attackSeconds, 0.0001, 1.0),
            5 to logRange(releaseSeconds, 0.01, 2.0),
            6 to linearRange(gainDb, -24.0, 24.0),
            8 to knee,
            9 to if ((type ushr 2) != 0) 1f else 0f
        )
    }

    private fun parametricEq2(data: ByteArray): List<EqBand> {
        val p = ints(data, 37)
        return (0 until 7).map { index ->
            EqBand(
                gainDb = p[1 + index] / 100.0,
                frequency = norm(p[8 + index], 65_536.0),
                width = norm(p[15 + index], 65_536.0),
                type = p[22 + index]
            )
        }
    }

    private fun parametricEq(data: ByteArray): List<EqBand> {
        val p = ints(data, 29)
        return (0 until 7).map { index ->
            EqBand(
                gainDb = p[index] / 100.0,
                frequency = norm(p[7 + index], 65_536.0),
                width = norm(p[14 + index], 65_536.0),
                type = p[21 + index]
            )
        }
    }

    private fun correctedEq(
        base: TranslatedMobileEffect,
        bands: List<EqBand>,
        sourceName: String
    ): TranslatedMobileEffect {
        val active = bands.filter { it.type != 0 }
        val chosen = when {
            active.isEmpty() -> bands.filterIndexed { index, _ -> index in intArrayOf(0, 2, 4, 6) }
            active.size <= 4 -> active
            else -> active.sortedByDescending { band ->
                abs(band.gainDb) + when (band.type) {
                    1, 3 -> 30.0
                    5, 7 -> 12.0
                    4 -> 8.0
                    else -> 0.0
                }
            }.take(4)
        }.sortedBy { it.frequency }

        val updates = linkedMapOf<Int, Float>()
        chosen.take(4).forEachIndexed { mobileBand, sourceBand ->
            val baseIndex = 1 + mobileBand * 4
            updates[baseIndex] = (0.5 + sourceBand.gainDb / 36.0).toFloat().coerceIn(0f, 1f)
            updates[baseIndex + 1] = sourceBand.frequency
            updates[baseIndex + 2] = sourceBand.width
            updates[baseIndex + 3] = 0.51f
            updates[17 + mobileBand] = when (sourceBand.type) {
                5 -> 0.5f
                7 -> 1.0f
                else -> 0.0f
            }
        }
        for (mobileBand in chosen.size.coerceAtMost(4) until 4) {
            val baseIndex = 1 + mobileBand * 4
            updates[baseIndex] = 0.5f
            updates[baseIndex + 3] = 0.51f
            updates[17 + mobileBand] = 0f
        }

        val exactShapes = chosen.all { it.type in setOf(0, 5, 6, 7) }
        val reduced = active.size > 4
        return base.copy(
            parameterUpdates = updates,
            quality = if (!reduced && exactShapes) {
                EffectSettingsQuality.DIRECT
            } else {
                EffectSettingsQuality.ADAPTED
            },
            description = when {
                reduced -> "$sourceName: sete bandas reduzidas às quatro de maior impacto"
                !exactShapes -> "$sourceName: bandas preservadas; pass/notch aproximados por peaking"
                else -> "$sourceName: gain, frequência, largura, canal e tipo das bandas preservados"
            }
        )
    }

    private fun delay3(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 27)
        val wet = norm(p[24], 128.0).toDouble()
        val dry = norm(p[26], 128.0).toDouble()
        val mix = if (wet + dry > 0.0) wet / (wet + dry) else wet
        return linkedMapOf(
            1 to norm(p[5], 512.0),
            2 to norm(p[15], 128.0),
            3 to signedCenter(p[6], 1_024.0),
            4 to mix.toFloat().coerceIn(0f, 1f)
        )
    }

    private fun ints(data: ByteArray, count: Int): IntArray {
        require(data.size >= count * 4) {
            "Bloco possui ${data.size} bytes; eram necessários ${count * 4}."
        }
        return IntArray(count) { index ->
            ByteBuffer.wrap(data, index * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }

    private fun norm(value: Number, maximum: Double): Float =
        (value.toDouble() / maximum).toFloat().coerceIn(0f, 1f)

    private fun signedCenter(value: Number, magnitude: Double): Float =
        (0.5 + value.toDouble() / (2.0 * magnitude)).toFloat().coerceIn(0f, 1f)

    private fun linearRange(value: Double, minimum: Double, maximum: Double): Float =
        ((value - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f)

    private fun logRange(value: Double, minimum: Double, maximum: Double): Float {
        val safe = value.coerceIn(minimum, maximum)
        return ((ln(safe) - ln(minimum)) / (ln(maximum) - ln(minimum)))
            .toFloat().coerceIn(0f, 1f)
    }

    private fun linearMap(
        value: Int,
        sourceMinimum: Double,
        sourceMaximum: Double,
        targetMinimum: Double,
        targetMaximum: Double
    ): Double {
        val normalized = (value.toDouble() - sourceMinimum) / (sourceMaximum - sourceMinimum)
        return targetMinimum + normalized.coerceIn(0.0, 1.0) * (targetMaximum - targetMinimum)
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
