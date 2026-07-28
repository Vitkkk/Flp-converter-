package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Effect modules captured from a real FL Studio Mobile 4.10.17 project.
 *
 * The source project contains one open DirectWave followed by every Mobile
 * effect saved with its Default preset and collapsed in the rack. The catalog
 * extracts the RMOd payloads directly so the writer can clone valid modules.
 */
internal data class MobileEffectTemplate(
    val mobileName: String,
    val moduleType: Int,
    private val originalPayload: ByteArray
) {
    fun payload(uniqueModuleId: Int): ByteArray =
        originalPayload.copyOf().also { payload ->
            require(payload.size >= 8) {
                "Template de efeito FLM inválido: $mobileName"
            }
            ByteBuffer.wrap(payload, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(uniqueModuleId)
        }
}

internal object MobileEffectCatalog {
    private data class EffectSpec(val name: String, val moduleType: Int)
    private data class Chunk(val type: String, val payload: ByteArray)

    private val specs = listOf(
        EffectSpec("Reverb", 32),
        EffectSpec("Reverb 2", 35),
        EffectSpec("Tape Delay", 18),
        EffectSpec("Trance Delay", 16),
        EffectSpec("Autoduck", 29),
        EffectSpec("Compressor", 8),
        EffectSpec("Distortion", 6),
        EffectSpec("Gate", 27),
        EffectSpec("Leveller", 20),
        EffectSpec("Limiter", 28),
        EffectSpec("Multiband Compressor", 36),
        EffectSpec("Waveshaper", 41),
        EffectSpec("Comb Filter", 40),
        EffectSpec("Equalizer", 3),
        EffectSpec("Filter", 9),
        EffectSpec("Graphic EQ", 7),
        EffectSpec("Spacer", 34),
        EffectSpec("Spreader", 44),
        EffectSpec("Stereoizer", 15),
        EffectSpec("Tuned EQ", 30),
        EffectSpec("Analyzer", 26),
        EffectSpec("Chorus", 11),
        EffectSpec("Flanger", 13),
        EffectSpec("MultiFX", 4),
        EffectSpec("Phaser", 14),
        EffectSpec("Pitcher", 25),
        EffectSpec("Pitcher 2", 45),
        EffectSpec("Tape Stop", 42),
        EffectSpec("Tremolo", 46),
        EffectSpec("Tuner", 24),
        EffectSpec("Wow & Flutter", 43),
        EffectSpec("Arpeggiator", 51),
        EffectSpec("Note Echo", 47),
        EffectSpec("Randomizer", 49),
        EffectSpec("Scale", 50),
        EffectSpec("Transpose", 48)
    )

    private val encodedProject: String =
            "H4sIAAAAAAAC/+2dX2xTVRjAT7uyf5RtjAEDBt6MDbexbr3tYDLXe1vaki5ry9IuTGEmFNYhZqyyjYEOpA8kQkiYIgEHMTEmohnG" +
            "V3xSE/XJ8WbCgy8YNCY+qC/Im3i++6c9ve3tOm7NHvp92WnPuef03rb7ne9833fOueXtwQMBv8d32kQI/ZMkHD/LRROTJ7i23nZS" +
            "qpKMuVfQ2v3L25dE+vwx//474qPE9F1b09mPrrSPzM2VTT5VG5lMZvpYQVNPF2/v4nsH/a/uD1sI6aRprpqj0nz89cTU2HRzH3dE" +
            "KoPMpXJSi2Ox6Xg4MROnbeydmVXxczPxyemTiUnpBK9pahPHZ2Kz8DKnpuL0mdjEyZm34ISpigudBVzfUezr8yu7fs8qX3/PKn//" +
            "vat8/X2r/P3zfLHfgHNlb+A5O2BPIf8AKaeconkqkZhRr1l9QVUoa2gKxd5ITCllUXoM+Yc9VVZCHHbHXpu91+Z4ieMdNuc+G7+n" +
            "1HR4/diVo26CgoKCgoKCgoJSWpJhCPf18H2O3pL6/MO+gUMQebBYIh7v4KRylLcPRiIBv6+O5p9RUaM/kI8MRUKNND8yflhM+RWM" +
            "jxGJhoZ2SecIRaE85PFFN0khDnOZZU15RWVV9VrruprauvX1Gxo2wlV/PUakAJP2qrv/v6uGDo5VlclRLfj0EOWqIukol8nnj3ot" +
            "tMYXH4+dmZgp2e5R6p9fQikSHd6s5GvT30m38uzx+fgyqfIiZTR6WfYuJSgfLC25FEBF+ViSAVcvn+Glsq8T9Y9lSOoY7RMRa+qY" +
            "3N4bHQ7VSB1lxBca8A2YpS4AHeIezXK0VMZ0CA47BHaI5+wQJrlDuBVoaWcQHiwFpWcFSCEH0FL5z/PPMupygQzI3qdE7oTRi0F2" +
            "JyKLyBpDNqigV/1wp/C1faEfcLvpvepSj8kYB8X20dos/ds+Oi/h/d2Tz4VP/LNSXg/fi5TOelooZ/CtR3wRX2P4Nij43l7YIiXA" +
            "DbSuHobvUcrA5q5gMKxDDBFDYxg2KRiOjx9QBv0Mi1XUs04lJwy4vEqx207zlQyX25FL5NIYl5zC5RWPpBZd8Kz1pvRU5QdmGccq" +
            "BslKRBKRNIZkm8bvUUfua+Ws0y/n9dB81ywbktUMmuWIJqJZnFE8013PP4oDjvOUtm20sJbBcRviiDgaw7EljaAk1B3vV7AUmEiS" +
            "rq9zllIH/pGVwbIBsUQsjWFpVbD84Y9+cbmID2jUGga/JsQP8StOxEcZmCE4KeSLm/9OKWtRojwqhi2IIWJoDMPzDIa3FxYEp9Op" +
            "maiXB26og8Takc9fR5Z1jb6hNMNulfUM7u2IO+JuDPdhjS3KokiypuuzcM1aw7Jc8OkOpRQiBfUMxm2IMWJsDGM7gzHMWsLMJiFu" +
            "ddWKoMU331QSaFo49QYG0TJEFBEtmqZVbFsijs6bUziyZXXGM10fZMukEE0LbpoaGVAxrkKMEePiuGlauzhfDBUm5TcyGFYghoih" +
            "MQxbNNECvaSHJczLN9PCJgbLZsQSsTSGJZcbywz3KN+A3UkLmxkkOxFJRLI4A7bmJGK+OXg4ayODYS1iiBgaw7CJsRu/2rGG8c7z" +
            "xz1vUdp20MIWBscdiCPiaAzHzgLtR2gF3rgenk8pfVuJnFQ8tyKeiKcxPC0Knnrri5Op8yZFto4d4DPr5fyHN28Kdz9tZetI5soo" +
            "In6x2CHcW+xIdQHmNVJqGbgusDYrrI7axuC/FvFH/Itjs0JcVI6FyjtB8s0qrSPyMhQVw3WIIWJoDEM7gyHkHixZNRrTLWq0aF63" +
            "CrT6dgZRCyKKiBbHrWIGffGftqaCNoHUKK6UimMN4og4GsNR3QTy/U+/wU5jQb57BLusOW236qH52Cx7+y8waG5BNBFNY2iO5tCU" +
            "ma4S0bhFKxM9V61Begsmk/qgirqG1UbkmScVdRuijqgbQ32CsUdJzrzesax78eSILGTfs0cP/UqmjTrp2kHk25uouHcg7oi7Mdy5" +
            "lJsWhIV/wr3FRSkVsuMUIgddRN45oCLZhUgiksWJHNx5uc+Vx6hgtWveyAFMxrYyiDYiooioMUSXm2Koy0JVHsRhQhbuarmLwXE3" +
            "4og4GsNRnZB95d9+Qdn6Jzx6eNmVjiAQ4j103yUvhdbfHP0Xpc9JCy8yeDoRT8TTGJ6XFDxbB64Le2+MCLlWVEHqu/ZY0B7L7Vol" +
            "V9CWkPunfhYg6WEPNz7tJvJeKhX7bsQesTeGvTsdKRD2HzknpWWWzWhRzrvelSfyLlYVWR6RRWSNIZt7vav+dANoTocSiFIxdCCG" +
            "iGGxNGcawcJisOnnfDcAsitul4qsHZFFZI0ha03jpxuQ8gbCwWtKXN9hD3i9gYAPfuIz5IkO+yMEBQWlxGXWVZ36rWD5SL87X3u4" +
            "w2em/hiODAbaqFahY9lxzkKYWfNsCcZn4xP4paOglKyAVdKt/PiVapUcBf0xwkVjp96ciE9xPH5LKCilKn+LTz47UPC6PrA3wPNh" +
            "9Ye2DO2i/rCvMcvBT4rwC3+QwI65xdgxIM8UYa9lIqirUFBK3o4JDgydUPIOe9DrDQbGWrNa1blZ3ZGSwwcPhrjMlsnEl2G37IpN" +
            "gvL79sejN0T/obADfjGxgfwHghb5Nx2HAAA="

    val all: List<MobileEffectTemplate> by lazy {
        val project = GZIPInputStream(
            ByteArrayInputStream(Base64.getDecoder().decode(encodedProject))
        ).use { it.readBytes() }

        require(
            project.size >= 4 &&
                String(project, 0, 4, Charsets.US_ASCII) == "10LF"
        ) { "Projeto-base de efeitos FLM inválido." }

        val racks = parseChunks(project, 4, project.size)
            .filter { it.type == "RACK" }

        require(racks.size >= 2) {
            "Projeto-base não contém o rack de instrumentos esperado."
        }

        val modulePayloads = parseChunks(
            racks[1].payload,
            8,
            racks[1].payload.size
        )
            .filter { it.type == "RMOd" }
            .drop(1)

        require(modulePayloads.size == specs.size) {
            "Quantidade inesperada de efeitos no projeto-base."
        }

        specs.zip(modulePayloads).map { (spec, chunk) ->
            val storedType = getInt(chunk.payload, 0)
            require(storedType == spec.moduleType) {
                "ID inesperado para ${spec.name}: $storedType"
            }
            MobileEffectTemplate(
                mobileName = spec.name,
                moduleType = spec.moduleType,
                originalPayload = chunk.payload
            )
        }
    }

    private val byMobileName: Map<String, MobileEffectTemplate> by lazy {
        all.associateBy { normalize(it.mobileName) }
    }

    private val desktopAliases: Map<String, String> = mapOf(
        "fruity reeverb" to "Reverb",
        "fruity reeverb 2" to "Reverb 2",
        "fruity delay 2" to "Tape Delay",
        "fruity delay 3" to "Tape Delay",
        "fruity compressor" to "Compressor",
        "fruity limiter" to "Limiter",
        "fruity multiband compressor" to "Multiband Compressor",
        "fruity chorus" to "Chorus",
        "fruity flanger" to "Flanger",
        "fruity phaser" to "Phaser",
        "fruity fast dist" to "Distortion",
        "fruity blood overdrive" to "Distortion",
        "fruity waveshaper" to "Waveshaper",
        "fruity parametric eq" to "Equalizer",
        "fruity parametric eq 2" to "Equalizer",
        "fruity 7 band eq" to "Graphic EQ",
        "fruity free filter" to "Filter",
        "fruity filter" to "Filter",
        "fruity stereo enhancer" to "Stereoizer",
        "fruity balance" to "Leveller",
        "fruity soft clipper" to "Waveshaper"
    )

    fun findMobile(name: String): MobileEffectTemplate? =
        byMobileName[normalize(name)]

    fun findDesktopEquivalent(pluginName: String): MobileEffectTemplate? {
        val mobileName = desktopAliases[normalize(pluginName)] ?: return null
        return findMobile(mobileName)
    }

    private fun parseChunks(
        bytes: ByteArray,
        start: Int,
        end: Int
    ): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var offset = start

        while (offset < end) {
            require(offset + 8 <= end) { "Chunk FLM incompleto." }
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = getInt(bytes, offset + 4)
            require(length >= 0 && offset + 8L + length <= end.toLong()) {
                "Tamanho inválido no chunk $type."
            }

            val payloadStart = offset + 8
            val payloadEnd = payloadStart + length
            result += Chunk(
                type,
                bytes.copyOfRange(payloadStart, payloadEnd)
            )
            offset = payloadEnd
        }

        require(offset == end) { "Estrutura FLM desalinhada." }
        return result
    }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
