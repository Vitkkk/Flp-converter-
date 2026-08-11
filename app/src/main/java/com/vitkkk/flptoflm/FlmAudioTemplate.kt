package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Real FL Studio Mobile 4.10.19 structures extracted from the user-provided
 * audio.zip reference: one empty audio RACK and one CHNL containing a single
 * Audio Clip sampler. Only structural bytes are stored; no audio file is embedded.
 */
internal object FlmAudioTemplate {
    private const val RACK_GZIP =
        "H4sIAC+Ne2oC/wtydPZ2YAACRgYGQwPvoCAPVxcBIPc/EDAyQACIHRQQ5CsBZIenRdkDKXsGBACzAe5uorlIAAAA"

    private const val CHANNEL_GZIP =
        "H4sIAC+Ne2oC/3P28PM5zsfAwMDIwGBk4OHs7OHh4s/CwOBYmpKZr2DIMApGwSgYyeCDfcGzADtiVQOLEQZ2BozyI9jVz0UCrqjBHobD06LAOCTI28OBi4HBxTU4WQFY/DABVf2HAmSjGRhGi6ZRMApGOnD28QwQYIOwjQx8nJ19PFJUMVQ5OCCXHQlPLyjd/mnowBDl7++rQJQlwbkLWcEWBPs6evpJs0AKJWi5uD+lKDE9P0+3uARUIOkm5+QnZ+uWZCZnZ+al6xanVeiamJmbWJiNRtZwBb6VCsGJuQU5qcWjYTHSgT0Do4+nXza0JwVvu/z/HxwS5CyJ3JpCosEYqMBQEFMBA0NAiIehNZC2ZHDwcUBKbPrElTt6uQXGAUHBIauBRuQBsX5xST5QY6p+am5pTmJJaoq+gb5jXkpRfmaKfkpiSaJ+cn6uXmYuUEVOZl6qnpuPr35aJsg6smw2RckfJDnZN1gHqBnUKoQFEWZwI1qQIHnXMD8jUItRhAEAFf/OGM8OAAA="

    fun rackChunk(): ByteArray = inflate(RACK_GZIP)
    fun channelChunk(): ByteArray = inflate(CHANNEL_GZIP)

    private fun inflate(value: String): ByteArray {
        val bytes = Base64.getDecoder().decode(value)
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }
}
