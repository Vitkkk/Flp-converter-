package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** How faithfully a desktop effect state could be represented on Mobile. */
enum class EffectSettingsQuality {
    DIRECT,
    ADAPTED,
    DEFAULT
}

data class TranslatedMobileEffect(
    val template: MobileEffectTemplate,
    val parameterUpdates: Map<Int, Float>,
    val slotMix: Float?,
    val quality: EffectSettingsQuality,
    val description: String
) {
    fun payload(uniqueModuleId: Int): ByteArray {
        var output = template.payload(uniqueModuleId)
        if (parameterUpdates.isNotEmpty()) {
            output = patchFloatChunk(output, "PRMS", parameterUpdates)
        }
        if (slotMix != null) {
            output = patchFloatChunk(output, "SMPR", mapOf(1 to slotMix.coerceIn(0f, 1f)))
        }
        return FlmModuleUiState.setCollapsed(output, collapsed = true)
    }

    private fun patchFloatChunk(
        modulePayload: ByteArray,
        chunkName: String,
        updates: Map<Int, Float>
    ): ByteArray {
        val output = modulePayload.copyOf()
        var offset = 8 // module type + unique module ID

        while (offset < output.size) {
            require(offset + 8 <= output.size) { "Subchunk FLM incompleto." }
            val type = String(output, offset, 4, Charsets.US_ASCII)
            val length = int32(output, offset + 4)
            require(length >= 0 && offset + 8L + length <= output.size.toLong()) {
                "Tamanho inválido no subchunk $type."
            }

            if (type == chunkName) {
                require(length % 4 == 0) { "$chunkName não contém floats alinhados." }
                val count = length / 4
                for ((index, value) in updates) {
                    if (index !in 0 until count) continue
                    ByteBuffer.wrap(output, offset + 8 + index * 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(value.coerceIn(0f, 1f))
                }
                return output
            }
            offset += 8 + length
        }

        error("Subchunk $chunkName não encontrado no módulo ${template.mobileName}.")
    }
}

/**
 * Converts native Fruity plugin state blocks to the normalized controls used by
 * FL Studio Mobile 4.10.x modules.
 *
 * Direct conversions preserve controls that have the same meaning. Adapted
 * conversions preserve the audible intent when the Mobile module has fewer or
 * differently ranged controls.
 */
object MobileEffectSettingsTranslator {
    fun translate(slot: FlpEffectSlot): TranslatedMobileEffect? {
        val pluginName = slot.bestName ?: return null
        val template = MobileEffectCatalog.findDesktopEquivalent(pluginName) ?: return null
        val normalizedName = normalize(pluginName)
        val data = slot.pluginData
        val slotMix = normalizeSlotMix(slot.mix)

        val translated = if (data == null || data.isEmpty()) {
            Translation(emptyMap(), EffectSettingsQuality.DEFAULT, "sem bloco de parâmetros no FLP")
        } else {
            try {
                when (normalizedName) {
                    "fruity reeverb 2" -> translateReeverb2(data)
                    "fruity reeverb" -> translateReeverb(data)
                    "fruity compressor" -> translateCompressor(data)
                    "fruity parametric eq 2" -> translateParametricEq2(data)
                    "fruity parametric eq" -> translateParametricEq(data)
                    "fruity 7 band eq" -> translateSevenBandEq(data)
                    "fruity delay 2" -> translateDelay2(data)
                    "fruity delay 3" -> translateDelay3(data)
                    "fruity chorus" -> translateChorus(data)
                    "fruity flanger" -> translateFlanger(data)
                    "fruity phaser" -> translatePhaser(data)
                    "fruity filter" -> translateFilter(data)
                    "fruity free filter" -> translateFreeFilter(data)
                    "fruity balance" -> translateBalance(data)
                    "fruity stereo enhancer" -> translateStereoEnhancer(data)
                    "fruity fast dist" -> translateFastDist(data)
                    "fruity blood overdrive" -> translateBloodOverdrive(data)
                    "fruity soft clipper" -> translateSoftClipper(data)
                    "fruity limiter" -> translateLimiter(data)
                    "fruity multiband compressor" -> translateMultiband(data)
                    else -> Translation(
                        emptyMap(),
                        EffectSettingsQuality.DEFAULT,
                        "módulo compatível, tradutor de parâmetros ainda não definido"
                    )
                }
            } catch (_: Throwable) {
                Translation(
                    emptyMap(),
                    EffectSettingsQuality.DEFAULT,
                    "bloco de parâmetros não reconhecido nesta versão do FL Studio"
                )
            }
        }

        return TranslatedMobileEffect(
            template = template,
            parameterUpdates = translated.updates,
            slotMix = slotMix,
            quality = translated.quality,
            description = translated.description
        )
    }

    private data class Translation(
        val updates: Map<Int, Float>,
        val quality: EffectSettingsQuality,
        val description: String
    )

    private fun translateReeverb2(data: ByteArray): Translation {
        val p = ints(data, 16)
        return Translation(
            mapOf(
                1 to norm(p[2], 1000.0),      // high cut
                2 to norm(p[1], 1000.0),      // low cut
                3 to norm(p[4], 100.0),       // room size
                4 to norm(p[6], 1000.0),      // decay
                5 to norm(p[5], 100.0),       // diffusion
                6 to norm(p[7], 1000.0),      // damping
                7 to norm(p[11], 128.0),      // dry
                8 to norm(p[12], 128.0),      // early reflections
                9 to norm(p[13], 128.0),      // wet
                11 to norm(p[3], 19_200.0),   // pre-delay, approx. 0..500 ms
                13 to norm(p[15], 100.0),     // modulation amount
                14 to norm(p[8], 100.0),      // bass multiplier
                15 to norm(p[10], 100.0),     // stereo separation
                16 to norm(p[14], 10_000.0),  // modulation speed
                17 to norm(p[9], 100.0)       // crossover
            ),
            EffectSettingsQuality.DIRECT,
            "Reeverb 2: filtros, sala, decay, damping, dry/ER/wet, modulação e estéreo"
        )
    }

    private fun translateReeverb(data: ByteArray): Translation {
        val p = ints(data, 11)
        return Translation(
            mapOf(
                1 to norm(p[7], 65_536.0),
                2 to norm(p[8], 65_536.0),
                3 to norm(p[10], 65_536.0),
                5 to norm(p[5], 65_536.0),
                6 to norm(p[3], 65_536.0),
                7 to norm(p[4], 65_536.0),
                8 to norm(p[5], 65_536.0),
                10 to norm(p[1], 65_536.0),
                11 to norm(p[2], 65_536.0),
                14 to norm(p[10], 65_536.0),
                15 to norm(p[9], 65_536.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Reeverb: controles equivalentes adaptados ao Reverb Mobile"
        )
    }

    private fun translateCompressor(data: ByteArray): Translation {
        val p = ints(data, 7)
        val thresholdDb = p[1] / 10.0
        val ratio = max(1.0, p[2] / 10.0)
        val gainDb = p[3] / 10.0
        val attackSeconds = max(0.00001, p[4] / 10_000.0)
        val releaseSeconds = max(0.001, p[5] / 1_000.0)
        val tcr = (p[6] ushr 2) != 0

        return Translation(
            mapOf(
                1 to linearRange(thresholdDb, -60.0, 0.0),
                2 to linearRange(ratio, 1.0, 20.0),
                3 to logRange(attackSeconds, 0.0001, 0.2),
                4 to logRange(releaseSeconds, 0.01, 2.0),
                5 to linearRange(gainDb, -24.0, 24.0),
                7 to 0f,
                9 to if (tcr) 1f else 0f
            ),
            EffectSettingsQuality.DIRECT,
            "Compressor: threshold, ratio, attack, release, ganho e modo RMS/TCR"
        )
    }

    private data class EqBand(
        val gainDb: Double,
        val freq: Float,
        val width: Float,
        val type: Int,
        val originalIndex: Int
    )

    private fun translateParametricEq2(data: ByteArray): Translation {
        val p = ints(data, 37)
        val bands = (0 until 7).map { i ->
            EqBand(
                gainDb = p[1 + i] / 100.0,
                freq = norm(p[8 + i], 65_536.0),
                width = norm(p[15 + i], 65_536.0),
                type = p[22 + i],
                originalIndex = i
            )
        }
        return buildFourBandEq(bands, "Parametric EQ 2")
    }

    private fun translateParametricEq(data: ByteArray): Translation {
        val p = ints(data, 29)
        val bands = (0 until 7).map { i ->
            EqBand(
                gainDb = p[i] / 100.0,
                freq = norm(p[7 + i], 65_536.0),
                width = norm(p[14 + i], 65_536.0),
                type = p[21 + i],
                originalIndex = i
            )
        }
        return buildFourBandEq(bands, "Parametric EQ")
    }

    private fun buildFourBandEq(bands: List<EqBand>, sourceName: String): Translation {
        val active = bands.filter { it.type != 0 }
        val chosen = when {
            active.size <= 4 -> active
            else -> active.sortedByDescending { band ->
                abs(band.gainDb) + when (band.type) {
                    1, 3 -> 30.0 // low/high-pass are structurally important
                    5, 7 -> 12.0 // shelves
                    4 -> 8.0     // notch
                    else -> 0.0
                }
            }.take(4)
        }.sortedBy { it.freq }

        val updates = linkedMapOf<Int, Float>()
        chosen.forEachIndexed { mobileBand, sourceBand ->
            val base = 1 + mobileBand * 4
            updates[base] = (0.5 + sourceBand.gainDb / 36.0).toFloat().coerceIn(0f, 1f)
            updates[base + 1] = sourceBand.freq
            updates[base + 2] = sourceBand.width
            updates[base + 3] = mobileEqKind(sourceBand.type)
        }
        for (i in chosen.size until 4) {
            val base = 1 + i * 4
            updates[base] = 0.5f
        }

        return Translation(
            updates,
            if (active.size <= 4) EffectSettingsQuality.DIRECT else EffectSettingsQuality.ADAPTED,
            if (active.size <= 4) {
                "$sourceName: bandas ativas preservadas"
            } else {
                "$sourceName: 7 bandas reduzidas às 4 de maior impacto"
            }
        )
    }

    private fun translateSevenBandEq(data: ByteArray): Translation {
        val p = ints(data, 8)
        val updates = linkedMapOf<Int, Float>()
        for (i in 0 until 7) {
            updates[1 + i] = (0.5 + p[1 + i] / 3600.0).toFloat().coerceIn(0f, 1f)
        }
        updates[8] = 0.5f
        return Translation(
            updates,
            EffectSettingsQuality.ADAPTED,
            "7 Band EQ: sete ganhos preservados; oitava banda neutra"
        )
    }

    private fun translateDelay2(data: ByteArray): Translation {
        val p = ints(data, 8)
        val wet = (1.0 - p[2] / 128.0).coerceIn(0.0, 1.0)
        return Translation(
            mapOf(
                1 to norm(p[4], 48.0),
                2 to norm(p[3], 128.0),
                3 to (0.5 + p[5] / 1024.0).toFloat().coerceIn(0f, 1f),
                4 to wet.toFloat()
            ),
            EffectSettingsQuality.ADAPTED,
            "Delay 2: tempo, feedback, offset estéreo e mix adaptados ao Tape Delay"
        )
    }

    private fun translateDelay3(data: ByteArray): Translation {
        val p = ints(data, 26)
        val wetRaw = if (p.size > 23) p[23] else p[1]
        return Translation(
            mapOf(
                1 to norm(p[5], 512.0),
                2 to norm(p[16], 128.0),
                3 to (0.5 + p[6] / 2048.0).toFloat().coerceIn(0f, 1f),
                4 to norm(wetRaw, 128.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Delay 3: parâmetros principais reduzidos ao Tape Delay Mobile"
        )
    }

    private fun translateChorus(data: ByteArray): Translation {
        val p = ints(data, 13)
        val averageRate = (p[4] + p[5] + p[6]) / 3.0
        return Translation(
            mapOf(
                1 to norm(averageRate, 5_000.0),
                2 to norm(p[2], 5_000.0),
                3 to norm(p[3], 5_000.0),
                4 to if (p[12] != 0) 1f else 0.6f
            ),
            EffectSettingsQuality.ADAPTED,
            "Chorus: taxa média dos três LFOs, depth, stereo e wet-only"
        )
    }

    private fun translateFlanger(data: ByteArray): Translation {
        val p = ints(data, 13)
        val rateInput = (p[3] / 5_000.0).coerceIn(0.0, 1.0)
        val rateHz = rateInput.pow(7.0) * 5.0
        val depth = norm(p[2], 5_000.0)
        val delay = ((p[1] / 5_000.0).coerceIn(0.0, 1.0).pow(2.58495)).toFloat()
        val mix = if (p[10] + p[11] == 0) 0.5 else p[11].toDouble() / (p[10] + p[11])
        return Translation(
            mapOf(
                1 to norm(rateHz, 5.0),
                3 to norm(rateHz, 5.0),
                4 to norm(p[6], 1_024.0),
                5 to depth,
                6 to delay,
                7 to (0.5 + p[7] / 200.0).toFloat().coerceIn(0f, 1f),
                8 to if (p[8] != 0) 1f else 0f,
                9 to norm(p[4], 1_024.0),
                10 to mix.toFloat().coerceIn(0f, 1f),
                11 to if (p[9] != 0) 1f else 0f
            ),
            EffectSettingsQuality.DIRECT,
            "Flanger: rate, depth, delay, feedback, fase, mix e inversões"
        )
    }

    private fun translatePhaser(data: ByteArray): Translation {
        val p = ints(data, 10)
        return Translation(
            mapOf(
                1 to norm(p[1], 5_000.0),
                2 to norm(abs(p[3] - p[2]), 5_000.0),
                3 to (0.5 + p[7] / 2_000.0).toFloat().coerceIn(0f, 1f),
                4 to norm(p[4], 5_000.0),
                5 to norm(p[8], 1_024.0),
                9 to norm(p[5], 1_024.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Phaser: sweep, depth, feedback, frequência, mix e estéreo"
        )
    }

    private fun translateFilter(data: ByteArray): Translation {
        val p = ints(data, 7)
        val kind = when {
            p[5] >= p[3] && p[5] >= p[4] -> 1f
            p[4] >= p[3] -> 0.5f
            else -> 0f
        }
        return Translation(
            mapOf(
                1 to norm(p[1], 1_024.0),
                2 to norm(p[2], 1_024.0),
                3 to kind,
                4 to if (p[6] != 0) 1f else 0.5f
            ),
            EffectSettingsQuality.DIRECT,
            "Filter: cutoff, resonance, modo e inclinação"
        )
    }

    private fun translateFreeFilter(data: ByteArray): Translation {
        val p = ints(data, 5)
        val kind = when ((p[1] / 700).coerceIn(0, 6)) {
            0 -> 0f
            1 -> 0.33f
            2 -> 1f
            3 -> 0.66f
            4 -> 0.15f
            5 -> 0.5f
            else -> 0.85f
        }
        return Translation(
            mapOf(
                1 to norm(p[2], 1_024.0),
                2 to norm(p[3], 1_024.0),
                3 to kind,
                4 to 0.5f
            ),
            EffectSettingsQuality.ADAPTED,
            "Free Filter: frequência, Q e tipo; ganho de shelf/peak aproximado"
        )
    }

    private fun translateBalance(data: ByteArray): Translation {
        val p = ints(data, 2)
        val amplitude = max(0.0001, p[1] / 256.0)
        val gainDb = 20.0 * log10(amplitude)
        return Translation(
            mapOf(
                1 to (0.7379573 + gainDb / 48.0).toFloat().coerceIn(0f, 1f),
                2 to signedCenter(p[0], 128.0)
            ),
            EffectSettingsQuality.DIRECT,
            "Balance: volume e pan"
        )
    }

    private fun translateStereoEnhancer(data: ByteArray): Translation {
        val p = ints(data, 6)
        return Translation(
            mapOf(
                1 to signedCenter(p[2], 128.0),
                2 to signedCenter(p[3], 1_024.0),
                3 to if (p[4] != 0) 0.8f else 0.5f,
                4 to norm(abs(p[3]), 1_024.0),
                5 to signedCenter(p[0], 128.0),
                6 to norm(p[5], 2.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Stereo Enhancer: separação, fase, posição, atraso L/R, pan e inversão"
        )
    }

    private fun translateFastDist(data: ByteArray): Translation {
        val p = ints(data, 5)
        return Translation(
            mapOf(
                1 to norm(p[0], 128.0),
                3 to norm(p[4], 128.0),
                5 to norm(p[2], 1.0),
                6 to norm(p[1], 128.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Fast Dist: pre/drive, curva, threshold/bias e post gain"
        )
    }

    private fun translateBloodOverdrive(data: ByteArray): Translation {
        val offset = if (data.size >= 36) 1 else 0
        val p = ints(data, offset + 8)
        return Translation(
            mapOf(
                1 to norm(p[offset + 3], 1_024.0),
                3 to norm(p[offset + 6], 1_024.0),
                5 to 0.5f,
                6 to norm(p[offset + 2], 1_024.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Blood Overdrive: preamp, color e post gain adaptados ao Distortion"
        )
    }

    private fun translateSoftClipper(data: ByteArray): Translation {
        val p = ints(data, 2)
        return Translation(
            mapOf(
                3 to (1f - norm(p[0], 1_024.0)),
                5 to norm(p[1], 1_024.0),
                8 to 1f,
                9 to 1f
            ),
            EffectSettingsQuality.ADAPTED,
            "Soft Clipper: threshold e post gain adaptados ao Waveshaper"
        )
    }

    private fun translateLimiter(data: ByteArray): Translation {
        val p = ints(data, 16)
        val inputGainDb = p[1] / 10.0
        val ceilingDb = p[3] / 10.0
        val releaseSeconds = max(0.001, p[6] / 1_000.0)
        return Translation(
            mapOf(
                1 to linearRange(inputGainDb, -24.0, 24.0),
                2 to linearRange(ceilingDb, -24.0, 6.0),
                3 to logRange(releaseSeconds, 0.01, 2.0),
                4 to linearRange(ceilingDb, -60.0, 0.0)
            ),
            EffectSettingsQuality.ADAPTED,
            "Limiter: input gain, ceiling/output, release e threshold"
        )
    }

    private fun translateMultiband(data: ByteArray): Translation {
        val p = ints(data, 30)
        val updates = linkedMapOf<Int, Float>()
        updates[1] = linearRange(p[1] / 10.0, -24.0, 24.0)
        updates[2] = norm(p[22], 65_536.0) // low crossover
        updates[3] = norm(p[13], 65_536.0) // high crossover

        // Mobile layout: 3 bands with input, threshold, ratio, attack,
        // release, post gain and state. Source order is high, mid, low.
        val sourceBases = intArrayOf(22, 13, 4)
        val mobileBases = intArrayOf(6, 14, 22)
        for (band in 0..2) {
            val s = sourceBases[band]
            val m = mobileBases[band]
            if (s + 8 >= p.size) continue
            updates[m] = linearRange(p[s + 2] / 10.0, -24.0, 24.0)
            updates[m + 1] = linearRange(p[s + 3] / 10.0, -60.0, 0.0)
            updates[m + 2] = linearRange(max(1.0, p[s + 4] / 10.0), 1.0, 20.0)
            updates[m + 3] = logRange(max(0.0001, p[s + 5] / 10_000.0), 0.0001, 0.2)
            updates[m + 4] = logRange(max(0.001, p[s + 6] / 1_000.0), 0.01, 2.0)
            updates[m + 5] = linearRange(p[s + 2] / 10.0, -24.0, 24.0)
            updates[m + 6] = if (p[s] != 0) 1f else 0f
        }
        return Translation(
            updates,
            EffectSettingsQuality.ADAPTED,
            "Multiband Compressor: crossovers e dinâmica das três bandas"
        )
    }

    private fun mobileEqKind(type: Int): Float = when (type) {
        1 -> 0.00f // low-pass
        2 -> 0.17f // band-pass
        3 -> 0.34f // high-pass
        4 -> 0.42f // notch
        5 -> 0.67f // low shelf
        6 -> 0.51f // peak
        7 -> 0.83f // high shelf
        else -> 0.51f
    }

    private fun ints(data: ByteArray, count: Int): IntArray {
        require(data.size >= count * 4) {
            "Bloco possui ${data.size} bytes; eram necessários ${count * 4}."
        }
        return IntArray(count) { index -> int32(data, index * 4) }
    }

    private fun normalizeSlotMix(raw: Int?): Float? {
        if (raw == null) return null
        // FL stores the slot mix in a signed -6400..6400 range. Mobile has no
        // inverted-wet half, so preserve the wet amount and discard polarity.
        return (abs(raw).toDouble() / 6_400.0).toFloat().coerceIn(0f, 1f)
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

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}

private fun int32(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
