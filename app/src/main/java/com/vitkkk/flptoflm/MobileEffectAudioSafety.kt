package com.vitkkk.flptoflm

/**
 * Final safety pass for effects whose Desktop and Mobile parameter curves are
 * different enough to create runaway feedback, endless reverb or hard clipping.
 *
 * This runs after the normal semantic translator. It preserves the translated
 * setting whenever it is inside an audible/safe range and only clamps the
 * dangerous controls. Other Mobile effects pass through unchanged.
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
                // Serial EQs can multiply extreme boosts/cuts. Keep each band to
                // roughly +/-6 dB and prevent razor-thin resonant peaks.
                for (gain in intArrayOf(1, 5, 9, 13)) cap(gain, 0.33f, 0.67f)
                for (width in intArrayOf(3, 7, 11, 15)) cap(width, 0.18f, 0.78f)
                safeSlotMix = safeSlotMix?.coerceAtMost(0.90f)
            }

            "Graphic EQ" -> {
                for (band in 1..8) cap(band, 0.33f, 0.67f)
                safeSlotMix = safeSlotMix?.coerceAtMost(0.90f)
            }

            "Limiter" -> {
                // Mobile gain controls are bipolar around 0.5. Never copy a
                // malformed state as +24 dB input/output.
                cap(1, 0.38f, 0.58f) // input gain, about -6..+4 dB
                cap(2, 0.10f, 0.78f) // threshold/ceiling
                cap(3, 0.05f, 0.78f) // release: avoid the 2 s maximum
                set(4, 0.5f)         // output gain always starts at 0 dB
                safeSlotMix = safeSlotMix?.coerceAtMost(1f)
            }

            "Reverb 2" -> {
                // Explicitly disable the two uncertain toggles and keep decay,
                // wet and predelay away from freeze/infinite-tail territory.
                cap(3, 0f, 0.84f)    // room size
                cap(4, 0f, 0.78f)    // diffusion
                cap(5, 0f, 0.72f)    // decay
                cap(7, 0.45f, 1f)    // dry
                cap(8, 0f, 0.55f)    // early reflections
                cap(9, 0f, 0.55f)    // wet
                set(10, 0f)          // unknown/freeze-like toggle off
                cap(11, 0f, 0.65f)   // predelay
                set(12, 0f)          // unknown/hold-like toggle off
                cap(13, 0f, 0.55f)   // modulation amount
                safeSlotMix = safeSlotMix?.coerceAtMost(0.80f)
            }

            "Reverb" -> {
                cap(3, 0f, 0.65f)    // overall mix
                cap(5, 0f, 0.72f)    // size/decay region
                cap(7, 0.45f, 1f)    // dry
                cap(13, 0f, 0.55f)   // wet
                safeSlotMix = safeSlotMix?.coerceAtMost(0.80f)
            }

            "Tape Delay", "Trance Delay" -> {
                cap(1, 0f, 0.92f)    // delay time
                cap(2, 0f, 0.72f)    // feedback; never self-oscillate forever
                centerExtreme(3, 0.20f, 0.80f) // invalid async/stereo -> center
                cap(4, 0.03f, 0.55f) // wet mix
                safeSlotMix = safeSlotMix?.coerceAtMost(0.80f)
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
                    "; proteção específica anti-clipping, feedback infinito e cauda sem fim"
            )
        } else {
            translated
        }
    }
}
