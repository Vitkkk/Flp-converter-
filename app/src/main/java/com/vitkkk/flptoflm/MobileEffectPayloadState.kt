package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies state that belongs to the FL Studio mixer slot rather than to the
 * effect algorithm itself.
 *
 * PRMS[0] is the Mobile module on/off state. PRST stores the visible preset
 * label. Imported settings use a fixed 15-byte label so the existing FLM chunk
 * size does not need to change.
 */
internal fun TranslatedMobileEffect.payloadForSlot(
    uniqueModuleId: Int,
    slot: FlpEffectSlot
): ByteArray {
    val output = payload(uniqueModuleId)
    patchFirstFloat(output, "PRMS", if (slot.enabled) 1f else 0f)
    patchPresetLabel(output, "Imported/FLP FX")
    return output
}

private fun patchFirstFloat(modulePayload: ByteArray, chunkName: String, value: Float) {
    visitSubchunks(modulePayload) { type, payloadOffset, length ->
        if (type == chunkName) {
            require(length >= 4) { "$chunkName não possui o primeiro parâmetro." }
            ByteBuffer.wrap(modulePayload, payloadOffset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(value.coerceIn(0f, 1f))
            return
        }
    }
}

private fun patchPresetLabel(modulePayload: ByteArray, label: String) {
    val encoded = label.toByteArray(Charsets.UTF_8)
    require(encoded.size == 15) { "O rótulo de preset importado deve possuir 15 bytes." }

    visitSubchunks(modulePayload) { type, payloadOffset, length ->
        if (type == "PRST") {
            require(length >= 23) { "Chunk PRST menor que o formato esperado." }
            ByteBuffer.wrap(modulePayload, payloadOffset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(encoded.size)
            encoded.copyInto(modulePayload, payloadOffset + 8)
            return
        }
    }
}

private inline fun visitSubchunks(
    modulePayload: ByteArray,
    action: (type: String, payloadOffset: Int, length: Int) -> Unit
) {
    require(modulePayload.size >= 8) { "Payload RMOd incompleto." }
    var offset = 8

    while (offset < modulePayload.size) {
        require(offset + 8 <= modulePayload.size) { "Subchunk de módulo incompleto." }
        val type = String(modulePayload, offset, 4, Charsets.US_ASCII)
        val length = ByteBuffer.wrap(modulePayload, offset + 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        require(length >= 0 && offset + 8L + length <= modulePayload.size.toLong()) {
            "Tamanho inválido no subchunk $type."
        }

        action(type, offset + 8, length)
        offset += 8 + length
    }
}
