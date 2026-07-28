package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Parameter-aware translators for effects whose desktop and Mobile layouts are
 * not byte-compatible. Every delicate effect is converted by meaning and then
 * passed through conservative audio-safety limits so malformed/unknown states
 * cannot create runaway feedback, extreme gain or resonant EQ spikes.
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
        val data = slot.pluginData ?: return sanitize(base)
        val name = slot.bestName?.let(::normalize) ?: return sanitize(base)

        val translated = try {
            when (name) {
                "fruity reeverb 2" -> base.copy(
                    parameterUpdates = reeverb2(data),
                    quality = EffectSettingsQuality.DIRECT,
                    description = "Reeverb 2: parâmetros semânticos preservados; modos não documentados mantidos no padrão Mobile"
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
                "fruity delay 2" -> base.copy(
                    parameterUpdates = delay2(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Delay 2: time, feedback, offset estéreo e mix adaptados ao Tape Delay"
                )
                "fruity delay 3" -> base.copy(
                    parameterUpdates = delay3(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Delay 3: time, feedback, stereo offset e proporção wet/dry adaptados ao Tape Delay"
                )
                "fruity limiter" -> base.copy(
                    parameterUpdates = limiter(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Limiter: gain, ceiling/threshold e release preservados; output gain Mobile mantido neutro"
                )
                "fruity multiband compressor" -> base.copy(
                    parameterUpdates = multiband(data),
                    quality = EffectSettingsQuality.ADAPTED,
                    description = "Multiband Compressor: bandas low/mid/high e estados corrigidos por layout"
                )
                else -> base
            }
        } catch (_: Throwable) {
            base.copy(
                parameterUpdates = emptyMap(),
                quality = EffectSettingsQuality.DEFAULT,
                description = "bloco de parâmetros não reconhecido; preset Mobile seguro mantido"
            )
        }

        return sanitize(translated)
    }

    /**
     * Reeverb 2 has 15 documented desktop controls. Bytes 64/65 are trailing
     * state, not proven effect switches; the old converter copied them into two
     * Mobile toggles and could accidentally enable a hold/freeze-like state.
     */
    private fun reeverb2(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 16)
        return linkedMapOf(
            1 to logRange(p[2] * 100.0, 500.0, 22_050.0),
            2 to logRange(p[1].toDouble(), 20.0, 3_000.0),
            3 to normalizedControl(p[4], 100.0).coerceAtMost(0.90f),
            4 to normalizedControl(p[5], 100.0).coerceAtMost(0.90f),
            5 to normalizedControl(p[6], 100.0).coerceAtMost(0.88f),
            6 to logRange(p[7] * 100.0, 500.0, 22_050.0),
            7 to normalizedControl(p[11], 128.0).coerceAtLeast(0.25f),
            8 to normalizedControl(p[12], 128.0).coerceAtMost(0.75f),
            9 to normalizedControl(p[13], 128.0).coerceAtMost(0.72f),
            11 to normalizedControl(p[3], 19_200.0).coerceAtMost(0.80f),
            13 to normalizedControl(p[15], 100.0).coerceAtMost(0.70f),
            14 to normalizedControl(p[14], 100.0).coerceAtMost(0.85f),
            15 to normalizedControl(p[8], 100.0).coerceAtMost(0.85f),
            16 to crossoverControl(p[9]),
            17 to signedControl(p[10], 64.0)
        )
    }

    private fun reeverb(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 11)
        return linkedMapOf(
            1 to normalizedControl(p[7], 65_536.0),
            2 to normalizedControl(p[8], 65_536.0),
            3 to normalizedControl(p[10], 65_536.0).coerceAtMost(0.90f),
            5 to normalizedControl(p[4], 65_536.0).coerceAtMost(0.88f),
            6 to normalizedControl(p[3], 65_536.0),
            7 to normalizedControl(p[5], 65_536.0).coerceAtLeast(0.25f),
            8 to 0.5f,
            9 to logRange(linearMap(p[1], 0.0, 65_536.0, 20.0, 3_000.0), 20.0, 3_000.0),
            10 to logRange(linearMap(p[2], 0.0, 65_536.0, 500.0, 22_050.0), 500.0, 22_050.0),
            13 to normalizedControl(p[10], 65_536.0).coerceAtMost(0.72f),
            14 to normalizedControl(p[9], 65_536.0).coerceAtMost(0.85f),
            15 to 0.5f
        )
    }

    private fun compressor(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 7)
        val plausibleEngineeringState =
            p[1] in -1_200..100 &&
                p[2] in 0..2_000 &&
                p[3] in -480..480 &&
                p[4] in 0..100_000 &&
                p[5] in 0..20_000

        if (!plausibleEngineeringState) {
            return linkedMapOf(
                1 to 0.5f,
                2 to 0.25f,
                3 to 0.35f,
                5 to 0.45f,
                6 to 0.5f,
                8 to 0.4f,
                9 to 0f
            )
        }

        val thresholdDb = p[1] / 10.0
        val ratio = max(1.0, p[2] / 10.0)
        val gainDb = (p[3] / 10.0).coerceIn(-12.0, 12.0)
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
            2 to logRange(ratio, 1.0, 20.0).coerceAtMost(0.92f),
            3 to logRange(attackSeconds, 0.0001, 1.0),
            5 to logRange(releaseSeconds, 0.01, 2.0).coerceAtMost(0.95f),
            6 to linearRange(gainDb, -24.0, 24.0).coerceIn(0.25f, 0.75f),
            8 to knee,
            9 to if ((type ushr 2) != 0) 1f else 0f
        )
    }

    private fun parametricEq2(data: ByteArray): List<EqBand> {
        val p = ints(data, 37)
        return (0 until 7).map { index ->
            eqBand(
                gainRaw = p[1 + index],
                frequencyRaw = p[8 + index],
                widthRaw = p[15 + index],
                typeRaw = p[22 + index]
            )
        }
    }

    private fun parametricEq(data: ByteArray): List<EqBand> {
        val p = ints(data, 29)
        return (0 until 7).map { index ->
            eqBand(
                gainRaw = p[index],
                frequencyRaw = p[7 + index],
                widthRaw = p[14 + index],
                typeRaw = p[21 + index]
            )
        }
    }

    private fun eqBand(
        gainRaw: Int,
        frequencyRaw: Int,
        widthRaw: Int,
        typeRaw: Int
    ): EqBand {
        val valid = frequencyRaw in 0..65_536 && widthRaw in 0..65_536 && typeRaw in 0..7
        return if (valid) {
            EqBand(
                gainDb = (gainRaw / 100.0).coerceIn(-12.0, 12.0),
                frequency = norm(frequencyRaw, 65_536.0),
                width = norm(widthRaw, 65_536.0).coerceIn(0.08f, 0.92f),
                type = typeRaw
            )
        } else {
            EqBand(0.0, 0.5f, 0.5f, 0)
        }
    }

    private fun correctedEq(
        base: TranslatedMobileEffect,
        bands: List<EqBand>,
        sourceName: String
    ): TranslatedMobileEffect {
        val active = bands.filter { it.type in 1..7 }
        if (active.isEmpty()) {
            return base.copy(
                parameterUpdates = flatEq(),
                quality = EffectSettingsQuality.ADAPTED,
                description = "$sourceName: nenhuma banda ativa confiável; EQ Mobile mantido neutro"
            )
        }

        val chosen = if (active.size <= 4) {
            active
        } else {
            active.sortedByDescending { band ->
                abs(band.gainDb) + when (band.type) {
                    1, 3 -> 30.0
                    5, 7 -> 12.0
                    4 -> 8.0
                    else -> 0.0
                }
            }.take(4)
        }.sortedBy { it.frequency }

        val updates = flatEq().toMutableMap()
        chosen.forEachIndexed { mobileBand, sourceBand ->
            val baseIndex = 1 + mobileBand * 4
            updates[baseIndex] = (0.5 + sourceBand.gainDb / 36.0)
                .toFloat()
                .coerceIn(0.17f, 0.83f)
            updates[baseIndex + 1] = sourceBand.frequency.coerceIn(0.01f, 0.99f)
            updates[baseIndex + 2] = sourceBand.width
            updates[baseIndex + 3] = 0.51f
            updates[17 + mobileBand] = when (sourceBand.type) {
                5 -> 0.5f
                7 -> 1.0f
                else -> 0.0f
            }
        }

        val exactShapes = chosen.all { it.type in setOf(5, 6, 7) }
        val reduced = active.size > 4
        return base.copy(
            parameterUpdates = updates,
            quality = if (!reduced && exactShapes) {
                EffectSettingsQuality.DIRECT
            } else {
                EffectSettingsQuality.ADAPTED
            },
            description = when {
                reduced -> "$sourceName: sete bandas reduzidas às quatro de maior impacto, com ganho/Q seguros"
                !exactShapes -> "$sourceName: pass/notch aproximados com curva segura no EQ Mobile"
                else -> "$sourceName: gain, frequência, largura e tipo das bandas preservados"
            }
        )
    }

    private fun flatEq(): MutableMap<Int, Float> {
        val updates = linkedMapOf<Int, Float>()
        for (mobileBand in 0 until 4) {
            val baseIndex = 1 + mobileBand * 4
            updates[baseIndex] = 0.5f
            updates[baseIndex + 2] = 0.5f
            updates[baseIndex + 3] = 0.51f
            updates[17 + mobileBand] = 0f
        }
        return updates
    }

    private fun delay2(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 8)
        val dry = normalizedControl(p[2], 128.0)
        return linkedMapOf(
            1 to normalizedControl(p[4], 48.0),
            2 to normalizedControl(p[3], 128.0).coerceAtMost(0.88f),
            3 to signedControl(p[5], 1_024.0).coerceIn(0.15f, 0.85f),
            4 to (1f - dry).coerceIn(0.05f, 0.75f)
        )
    }

    private fun delay3(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 27)
        val wet = normalizedControl(p[24], 128.0).toDouble()
        val dry = normalizedControl(p[26], 128.0).toDouble()
        val mix = if (wet + dry > 0.0001) wet / (wet + dry) else 0.5
        return linkedMapOf(
            1 to normalizedControl(p[5], 512.0),
            2 to normalizedControl(p[15], 128.0).coerceAtMost(0.88f),
            3 to signedControl(p[6], 1_024.0).coerceIn(0.15f, 0.85f),
            4 to mix.toFloat().coerceIn(0.05f, 0.75f)
        )
    }

    /**
     * Fruity Limiter stores normalized knob positions in integer slots. The old
     * code treated those integers as tenths of a dB and copied ceiling into the
     * Mobile output-gain control, producing +24 dB input and +24 dB output.
     */
    private fun limiter(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 19)
        return linkedMapOf(
            1 to normalizedControl(p[1], 65_536.0).coerceIn(0.25f, 0.75f),
            2 to normalizedControl(p[3], 65_536.0),
            3 to normalizedControl(p[6], 65_536.0).coerceAtMost(0.92f),
            4 to 0.5f
        )
    }

    private fun multiband(data: ByteArray): Map<Int, Float> {
        val p = ints(data, 30)
        val updates = linkedMapOf<Int, Float>()
        updates[1] = safeGain(p[1] / 10.0)
        updates[2] = norm(p[22], 65_536.0)
        updates[3] = norm(p[13], 65_536.0)

        // Mobile order is low, mid, high. The previous implementation used
        // crossover indices as the mid/low state flags and could enable bands
        // incorrectly. Each tuple is state, gain, threshold, ratio, attack,
        // release and knee from the documented desktop layout.
        val sourceBands = arrayOf(
            intArrayOf(21, 23, 24, 25, 26, 27, 28),
            intArrayOf(12, 15, 16, 17, 18, 19, 20),
            intArrayOf(4, 6, 7, 8, 9, 10, 11)
        )
        val mobileBases = intArrayOf(6, 14, 22)

        sourceBands.forEachIndexed { band, s ->
            val m = mobileBases[band]
            val gain = safeGain(p[s[1]] / 10.0)
            updates[m] = gain
            updates[m + 1] = linearRange(p[s[2]] / 10.0, -60.0, 0.0)
            updates[m + 2] = logRange(max(1.0, p[s[3]] / 10.0), 1.0, 20.0).coerceAtMost(0.92f)
            updates[m + 3] = logRange(max(0.0001, p[s[4]] / 10_000.0), 0.0001, 0.2)
            updates[m + 4] = logRange(max(0.001, p[s[5]] / 1_000.0), 0.01, 2.0).coerceAtMost(0.95f)
            updates[m + 5] = gain
            updates[m + 6] = if (p[s[0]] != 0) 1f else 0f
        }
        return updates
    }

    private fun sanitize(effect: TranslatedMobileEffect): TranslatedMobileEffect {
        if (effect.parameterUpdates.isEmpty()) return effect
        val updates = effect.parameterUpdates.toMutableMap()
        var changed = false

        fun cap(index: Int, minimum: Float, maximum: Float) {
            val current = updates[index] ?: return
            val safe = current.coerceIn(minimum, maximum)
            if (safe != current) {
                updates[index] = safe
                changed = true
            }
        }

        when (effect.template.mobileName) {
            "Reverb 2" -> {
                cap(5, 0f, 0.88f)
                cap(7, 0.25f, 1f)
                cap(8, 0f, 0.75f)
                cap(9, 0f, 0.72f)
                cap(11, 0f, 0.80f)
                cap(13, 0f, 0.70f)
            }
            "Reverb" -> {
                cap(3, 0f, 0.90f)
                cap(5, 0f, 0.88f)
                cap(7, 0.25f, 1f)
                cap(13, 0f, 0.72f)
            }
            "Tape Delay", "Trance Delay" -> {
                cap(2, 0f, 0.88f)
                cap(3, 0.15f, 0.85f)
                cap(4, 0.05f, 0.75f)
            }
            "Limiter" -> {
                cap(1, 0.25f, 0.75f)
                cap(3, 0f, 0.92f)
                cap(4, 0.5f, 0.5f)
            }
            "Compressor" -> {
                cap(2, 0f, 0.92f)
                cap(5, 0f, 0.95f)
                cap(6, 0.25f, 0.75f)
            }
            "Multiband Compressor" -> {
                cap(1, 0.25f, 0.75f)
                for (base in intArrayOf(6, 14, 22)) {
                    cap(base, 0.25f, 0.75f)
                    cap(base + 2, 0f, 0.92f)
                    cap(base + 4, 0f, 0.95f)
                    cap(base + 5, 0.25f, 0.75f)
                }
            }
            "Filter" -> cap(2, 0f, 0.85f)
            "Flanger" -> {
                cap(7, 0.08f, 0.90f)
                cap(10, 0.05f, 0.75f)
            }
            "Phaser" -> {
                cap(3, 0.08f, 0.88f)
                cap(5, 0.05f, 0.75f)
            }
            "Distortion" -> {
                cap(1, 0f, 0.85f)
                cap(3, 0f, 0.80f)
                cap(6, 0f, 0.85f)
            }
            "Waveshaper" -> cap(5, 0.25f, 0.75f)
            "Leveller" -> cap(1, 0.25f, 0.75f)
            "Graphic EQ" -> for (index in 1..8) cap(index, 0.17f, 0.83f)
        }

        return if (changed) {
            effect.copy(
                parameterUpdates = updates,
                description = effect.description + "; limites anti-clipping/feedback aplicados"
            )
        } else {
            effect
        }
    }

    private fun ints(data: ByteArray, count: Int): IntArray {
        require(data.size >= count * 4) {
            "Bloco possui ${data.size} bytes; eram necessários ${count * 4}."
        }
        return IntArray(count) { index ->
            ByteBuffer.wrap(data, index * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }

    private fun normalizedControl(value: Int, preferredMaximum: Double): Float {
        if (value < 0) return 0f
        val magnitude = value.toDouble()
        val maximum = when {
            magnitude <= preferredMaximum * 1.05 -> preferredMaximum
            magnitude <= 1_024.0 * 1.05 -> 1_024.0
            magnitude <= 65_536.0 * 1.05 -> 65_536.0
            else -> return 0.5f
        }
        return (magnitude / maximum).toFloat().coerceIn(0f, 1f)
    }

    private fun signedControl(value: Int, preferredMagnitude: Double): Float {
        val magnitude = abs(value.toLong()).toDouble()
        val scale = when {
            magnitude <= preferredMagnitude * 1.05 -> preferredMagnitude
            magnitude <= 1_024.0 * 1.05 -> 1_024.0
            magnitude <= 65_536.0 * 1.05 -> 65_536.0
            else -> return 0.5f
        }
        return (0.5 + value.toDouble() / (2.0 * scale)).toFloat().coerceIn(0f, 1f)
    }

    private fun crossoverControl(value: Int): Float = if (abs(value) <= 100) {
        normalizedControl(value, 100.0)
    } else {
        logRange(value.toDouble(), 50.0, 5_000.0)
    }

    private fun safeGain(gainDb: Double): Float =
        linearRange(gainDb.coerceIn(-12.0, 12.0), -24.0, 24.0).coerceIn(0.25f, 0.75f)

    private fun norm(value: Number, maximum: Double): Float =
        (value.toDouble() / maximum).toFloat().coerceIn(0f, 1f)

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
