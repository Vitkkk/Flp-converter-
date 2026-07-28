package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * FL Studio Mobile 4.10.17 effect module templates captured from a real FLM
 * project. Every effect was saved with its Default preset and collapsed in
 * the rack. DirectWave is intentionally not part of this catalog.
 */
internal data class MobileEffectTemplate(
    val mobileName: String,
    val moduleType: Int,
    private val encodedGzip: String
) {
    fun payload(uniqueModuleId: Int): ByteArray {
        val compressed = Base64.getDecoder().decode(encodedGzip)
        val payload = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }

        require(payload.size >= 8) { "Template de efeito FLM inválido: $mobileName" }
        ByteBuffer.wrap(payload, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(uniqueModuleId)

        return payload
    }
}

internal object MobileEffectCatalog {
    val all: List<MobileEffectTemplate> = listOf(
        MobileEffectTemplate(
            mobileName = "Reverb",
            moduleType = 32,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmRgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Reverb 2",
            moduleType = 35,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiamRgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Tape Delay",
            moduleType = 18,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Trance Delay",
            moduleType = 16,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmRgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Autoduck",
            moduleType = 29,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Compressor",
            moduleType = 8,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Distortion",
            moduleType = 6,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Gate",
            moduleType = 27,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Leveller",
            moduleType = 20,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Limiter",
            moduleType = 28,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Multiband Compressor",
            moduleType = 36,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Waveshaper",
            moduleType = 41,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Comb Filter",
            moduleType = 40,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Equalizer",
            moduleType = 3,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Filter",
            moduleType = 9,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Graphic EQ",
            moduleType = 7,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Spacer",
            moduleType = 34,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Spreader",
            moduleType = 44,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Stereoizer",
            moduleType = 15,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Tuned EQ",
            moduleType = 30,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Analyzer",
            moduleType = 26,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Chorus",
            moduleType = 11,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Flanger",
            moduleType = 13,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "MultiFX",
            moduleType = 4,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Phaser",
            moduleType = 14,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Pitcher",
            moduleType = 25,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Pitcher 2",
            moduleType = 45,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Tape Stop",
            moduleType = 42,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Tremolo",
            moduleType = 46,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Tuner",
            moduleType = 24,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Wow & Flutter",
            moduleType = 43,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Arpeggiator",
            moduleType = 51,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Note Echo",
            moduleType = 47,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Randomizer",
            moduleType = 49,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        ),
        MobileEffectTemplate(
            mobileName = "Scale",
            moduleType = 50,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgaGRgZmZmZm" +
                "BgYGBgYGBgYGBgAAVOnQyWsCAAA="
        ),
        MobileEffectTemplate(
            mobileName = "Transpose",
            moduleType = 48,
            encodedGzip = "H4sIAN0weWgC/2NgYGBmYGBw8HBl5GTAQAHEDAzOQPLMtMy8kvySxJzM5EQGBgYGJgZmRmYGBkYGBoaGPwYGxpQMCs7PK0nNS0xO" +
                "SszNzEtX8EnMSy9KzEtP1csFMx2DQx2DBLAAAwMDAz8DAwMDQ0MD4P////8/BgYGBgZGRoZGJiYmBgYmRkYmBgYmRkZGBgZmBgaGZmZm" +
                "BgYGBgYGBgYAAFDp0MmrAgAA"
        )
    )

    private val byMobileName: Map<String, MobileEffectTemplate> =
        all.associateBy { normalize(it.mobileName) }

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
        val normalized = normalize(pluginName)
        val mobileName = desktopAliases[normalized] ?: return null
        return findMobile(mobileName)
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
