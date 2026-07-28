package com.vitkkk.flptoflm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Controls the expanded/collapsed state of a module inside an FLM RMOd payload.
 *
 * Byte comparison between otherwise identical FL Studio Mobile 4.10.17
 * projects confirmed that the first byte of the ADD1 chunk is the rack UI
 * state:
 *
 * 0 = expanded/open
 * 1 = collapsed/minimized
 */
internal object FlmModuleUiState {
    fun setCollapsed(modulePayload: ByteArray, collapsed: Boolean): ByteArray {
        require(modulePayload.size >= 8) { "Payload RMOd incompleto." }

        val output = modulePayload.copyOf()
        var offset = 8 // module type + unique module ID

        while (offset < output.size) {
            require(offset + 8 <= output.size) { "Subchunk de módulo FLM incompleto." }

            val type = String(output, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(output, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            require(length >= 0 && offset + 8L + length <= output.size.toLong()) {
                "Tamanho inválido no subchunk $type."
            }

            if (type == "ADD1") {
                require(length >= 1) { "Chunk ADD1 sem campo de estado visual." }
                output[offset + 8] = if (collapsed) 1 else 0
                return output
            }

            offset += 8 + length
        }

        error("Chunk ADD1 não encontrado no módulo FLM.")
    }

    fun isCollapsed(modulePayload: ByteArray): Boolean {
        require(modulePayload.size >= 8) { "Payload RMOd incompleto." }
        var offset = 8

        while (offset < modulePayload.size) {
            require(offset + 8 <= modulePayload.size) { "Subchunk de módulo FLM incompleto." }

            val type = String(modulePayload, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(modulePayload, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            require(length >= 0 && offset + 8L + length <= modulePayload.size.toLong()) {
                "Tamanho inválido no subchunk $type."
            }

            if (type == "ADD1") {
                require(length >= 1) { "Chunk ADD1 sem campo de estado visual." }
                return modulePayload[offset + 8].toInt() != 0
            }

            offset += 8 + length
        }

        error("Chunk ADD1 não encontrado no módulo FLM.")
    }
}

/** Creates a valid effect payload and explicitly keeps it minimized in the rack. */
internal fun MobileEffectTemplate.collapsedPayload(uniqueModuleId: Int): ByteArray =
    FlmModuleUiState.setCollapsed(payload(uniqueModuleId), collapsed = true)
