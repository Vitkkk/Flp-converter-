package com.vitkkk.flptoflm

/**
 * Final safety pass for effects whose Desktop and Mobile parameter curves are
 * different enough to create runaway feedback, endless reverb, hard clipping
 * or excessive cumulative attenuation.
 *
 * This runs after the normal semantic translator. It preserves the translated
 * setting whenever it is inside an audible/safe range and only clamps dangerous
 * controls. Other Mobile effects pass through unchanged.
 */
internal object MobileEffectAudioSafety {
    fun translate(slot: FlpEffectSlot): TranslatedMobileEffect? {
        val translated = MobileEffectSettingsTranslatorV2.translate(slot) ?: return null
        val updates = translated.parameterUpdates.toMutableMap()
        var changed = false

        fun set(index: Int, value: Float) {
            val safe = value.coerceIn(0f, 1f)
            if (updates[index] != safe) {
                updates[index] = safe
                changed = true
            }
        }

        fun cap(index: Int, minimum: Float, maximum: Float) {
            val current = updates[index] ?: return
            val safe = current.coerceIn(minimum, maximum)
            if (safe != current) {
                updates[index] = safe
                changed = true
            }
        }

        fun centerExtreme(index: Int, minimum: Float, maximum: Float) {
            val current = updates[index] ?: return
            if (current < minimum || current > maximum) {
                updates[index] = 0.5f
                changed = true
            }
        }

        var safeSlotMix = translated.slotMix
        when (translated.template.mobileName) {
            "Equalizer" -> {
                // Serial EQs must not multiply broad boosts/cuts until the voice
                // becomes either clipped or almost inaudible. About +/-4 dB.
                for (gain in intArrayOf(1, 5, 9, 13)) cap(gain, 0.39f, 0.61f)
                for (width in intArrayOf(3, 7, 11, 15)) cap(width, 0.22f, 0.75f)
            }

            "Graphic EQ" -> {
                for (band in 1..8) cap(band, 0.39f, 0.61f)
            }

            "Limiter" -> {
                // 0.5 is unity gain on Mobile. Keep input close to neutral and
                // never copy a malformed state as +24 dB or a deep attenuation.
                cap(1, 0.46f, 0.58f)
                cap(2, 0.12f, 0.78f)
                cap(3, 0.05f, 0.75f)
                set(4, 0.5f)
            }

            "Compressor" -> {
                cap(2, 0f, 0.88f)
                cap(5, 0f, 0.90f)
                // Makeup gain stays near unity instead of silently losing many dB.
                cap(6, 0.45f, 0.62f)
            }

            "Multiband Compressor" -> {
                cap(1, 0.45f, 0.62f)
                for (base in intArrayOf(6, 14, 22)) {
                    cap(base, 0.42f, 0.64f)
                    cap(base + 2, 0f, 0.88f)
                    cap(base + 4, 0f, 0.90f)
                    cap(base + 5, 0.42f, 0.64f)
                }
            }

            "Leveller" -> {
                // A converted Balance/Leveller must not mute a channel by itself.
                cap(1, 0.45f, 0.65f)
            }

            "Reverb 2" -> {
                cap(3, 0f, 0.84f)
                cap(4, 0f, 0.78f)
                cap(5, 0f, 0.72f)
                // Preserve enough direct voice so the reverb cannot swallow it.
                cap(7, 0.65f, 1f)
                cap(8, 0f, 0.52f)
                cap(9, 0f, 0.50f)
                set(10, 0f)
                cap(11, 0f, 0.65f)
                set(12, 0f)
                cap(13, 0f, 0.55f)
            }

            "Reverb" -> {
                cap(3, 0f, 0.62f)
                cap(5, 0f, 0.72f)
                cap(7, 0.65f, 1f)
                cap(13, 0f, 0.50f)
            }

            "Tape Delay", "Trance Delay" -> {
                cap(1, 0f, 0.92f)
                cap(2, 0f, 0.65f)
                centerExtreme(3, 0.20f, 0.80f)
                cap(4, 0.03f, 0.50f)
            }

            "Filter" -> cap(2, 0f, 0.82f)

            "Flanger" -> {
                cap(7, 0.08f, 0.88f)
                cap(10, 0.05f, 0.70f)
            }

            "Phaser" -> {
                cap(3, 0.08f, 0.85f)
                cap(5, 0.05f, 0.70f)
            }

            "Distortion" -> {
                cap(1, 0f, 0.82f)
                cap(3, 0f, 0.76f)
                cap(6, 0.18f, 0.82f)
            }

            "Waveshaper" -> cap(5, 0.42f, 0.65f)
        }

        // FLP slot mix and Mobile SMPR do not share a proven identical curve.
        // Values near zero were being multiplied through long chains, making a
        // few voices tens of decibels quieter. Keep custom mixes audible while
        // the module's own dry/wet parameters preserve the effect character.
        if (safeSlotMix != null) {
            val audibleMix = safeSlotMix.coerceIn(0.78f, 1f)
            if (audibleMix != safeSlotMix) {
                safeSlotMix = audibleMix
                changed = true
            }
        }

        return if (changed || safeSlotMix != translated.slotMix) {
            translated.copy(
                parameterUpdates = updates,
                slotMix = safeSlotMix,
                quality = if (translated.quality == EffectSettingsQuality.DEFAULT) {
                    EffectSettingsQuality.DEFAULT
                } else {
                    EffectSettingsQuality.ADAPTED
                },
                description = translated.description +
                    "; proteção contra clipping, feedback e perda excessiva de volume"
            )
        } else {
            translated
        }
    }
}
