package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Minimal FLP container inspection for the Android alpha. */
data class FlpProject(
    val format: Int,
    val channels: Int,
    val ppq: Int,
    val sourceSize: Long
)

object FlpInspector {
    fun inspect(bytes: ByteArray): FlpProject =
        ByteArrayInputStream(bytes).use { inspect(it, bytes.size.toLong()) }

    /**
     * Reads only the FLP container header instead of loading the complete project into RAM.
     * This keeps large projects from killing the Android process during file selection.
     */
    fun inspect(input: InputStream, sourceSize: Long = -1L): FlpProject {
        val signature = readExact(input, 4)
        require(String(signature, Charsets.US_ASCII) == "FLhd") {
            "Assinatura FLhd não encontrada."
        }

        val headerLength = readLeUnsignedInt(input)
        require(headerLength in 6L..1_048_576L) { "Cabeçalho FLP inválido." }

        val format = readLeUnsignedShort(input)
        val channels = readLeUnsignedShort(input)
        val ppq = readLeUnsignedShort(input)
        require(channels in 1..65_535) { "Número de canais inválido." }
        require(ppq > 0) { "PPQ inválido." }

        skipFully(input, headerLength - 6L)

        val dataSignature = readExact(input, 4)
        require(String(dataSignature, Charsets.US_ASCII) == "FLdt") {
            "Chunk FLdt não encontrado."
        }

        // Confirms that the FLdt length field exists, without loading its payload.
        readLeUnsignedInt(input)

        return FlpProject(format, channels, ppq, sourceSize)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("O arquivo terminou antes do esperado.")
            offset += read
        }
        return result
    }

    private fun readLeUnsignedShort(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw EOFException("Cabeçalho FLP incompleto.")
        return b0 or (b1 shl 8)
    }

    private fun readLeUnsignedInt(input: InputStream): Long {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw EOFException("Cabeçalho FLP incompleto.")
        }
        return b0.toLong() or
            (b1.toLong() shl 8) or
            (b2.toLong() shl 16) or
            (b3.toLong() shl 24)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException("Cabeçalho FLP incompleto.")
                remaining--
            }
        }
    }
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
