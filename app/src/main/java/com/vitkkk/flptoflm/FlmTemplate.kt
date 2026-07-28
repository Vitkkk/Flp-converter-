package com.vitkkk.flptoflm

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Projeto FLM 4.10.17 criado no FL Studio Mobile:
 * 120 BPM, um canal DirectWave vazio, sem efeitos e sem notas.
 */
internal object FlmTemplate {
    private val encodedGzip: String =
        "H4sIAK4faGoC/zM08HHzcHV0KWRkYAAiMChLrMrMZxjpIM6BBMUOD6ta7IH0AsOJdfZiJ9/wT53+Zm7fNO6iriuFijBFjIxMQJIdiE30DA30DM29XSOd/FgY" +
        "GHSAuJpLAQiUkjPyi1KKlawUosF8EKiGs8AqkhKLU/3yS1KBagx0UKVSK0pS84oz8/PABsSiyeYnlySWgbQZo0kUlibmZJZUggyES9TqEGG/EbXtNyTNfpMB" +
        "tt90gMPffIDttxzg8Dc0pLYDjElzAJkZ0ISYCACzoEYoFeXnl8Ds5KqFFSisQOybmJVfBOXbg0lf1xBHTh4GBiMDIzNdA3NdIwsFAwMQw8BipJXhBR/UBR0Y" +
        "RsEoGAWjYBSMglEwCkbByAIoDWErA3MrQ6MR5f8QF88w0MgDC0uQo7N3HlTU0MA7KMjD1UUAyP4PBLDRHxA7KCDIVwLIDk+Lsof3K5D6GEHBvgFqYDN8g0H8" +
        "AEeXYDHwEAcTMwsrGzsHJxc3Dy8fv4CgkLCIKMjWemYG8AATuq1MtLPV1z+FkxkyqgWyBTTKxcmAGOVidHENdmYByrikpiWW5pSM2Owx0v0PTkpBwSHiUDY/" +
        "Ikz0obSji4shM1iyHphGgzvAzAZwojx75owtNIHaQ8QakBIuLjYyaEDWZ49bDAXAxYB5IogHLgZR7xwc4ssHzijhLr6eLp5M4Czg7OHn08cBKxI9nJ09PFxA" +
        "o4++jsEhrkGj1cQoGAUjHexzf2jHAJ3GgIjYOOBTz8aAXn6EBHl7aABLFWDtmqzAgqhusQGf1LLUnNFAHwWjYMQCUKtEGdouh7VKQkDlR7hCcGJuQU5qkYLh" +
        "aCiNglEwUsEH+zg+XXtiVYPaG6D+ELbyI9jVz0UCS78LNOAAwqC2y3SktgtsYAIEkM1nZBgtn0bBKBjxbRcfz4B0KNvIwMfZ2ccjRRVDlYADctkBB1H+/r4K" +
        "6GobfBwg3a88UIF34HTCFHvXMD8j0ACOCAMAHuMSjawnAAA="

    fun bytes(): ByteArray {
        val compressed = Base64.getDecoder().decode(encodedGzip)
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
    }
}
