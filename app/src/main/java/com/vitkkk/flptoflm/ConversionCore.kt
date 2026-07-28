package com.vitkkk.flptoflm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal FLP container inspection for the first Android alpha. */
data class FlpProject(
    val format: Int,
    val channels: Int,
    val ppq: Int,
    val sourceSize: Int
)

object FlpInspector {
    fun inspect(bytes: ByteArray): FlpProject {
        require(bytes.size >= 22) { "Arquivo pequeno demais para ser um FLP." }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "FLhd") { "Assinatura FLhd não encontrada." }

        val headerLength = leInt(bytes, 4)
        require(headerLength >= 6) { "Cabeçalho FLP inválido." }
        val format = leShort(bytes, 8)
        val channels = leShort(bytes, 10)
        val ppq = leShort(bytes, 12)
        require(channels in 1..65535) { "Número de canais inválido." }
        require(ppq > 0) { "PPQ inválido." }

        val dataOffset = 8 + headerLength
        require(dataOffset + 8 <= bytes.size) { "Chunk de dados ausente." }
        require(String(bytes, dataOffset, 4, Charsets.US_ASCII) == "FLdt") { "Chunk FLdt não encontrado." }

        return FlpProject(format, channels, ppq, bytes.size)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

/**
 * Alpha container writer.
 *
 * This establishes the writer boundary and channel mapping contract. It is NOT yet
 * a fully compatible Image-Line FLM serializer. The next milestone replaces this
 * payload with decoded FLM records for DirectWave channels, notes and slide flags.
 */
object ExperimentalFlmWriter {
    fun write(project: FlpProject): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('1'.code.toByte(), '0'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        out.write("\nFLP_TO_FLM_ALPHA=1\n".toByteArray())
        out.write("FORMAT=${project.format}\n".toByteArray())
        out.write("CHANNELS=${project.channels}\n".toByteArray())
        out.write("PPQ=${project.ppq}\n".toByteArray())
        out.write("SOURCE_SIZE=${project.sourceSize}\n".toByteArray())
        repeat(project.channels) { index ->
            out.write("CHANNEL=${index + 1};INSTRUMENT=DirectWave;PRESET=EMPTY\n".toByteArray())
        }
        return out.toByteArray()
    }
}
