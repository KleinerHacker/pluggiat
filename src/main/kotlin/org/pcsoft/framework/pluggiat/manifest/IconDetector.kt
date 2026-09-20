package org.pcsoft.framework.pluggiat.manifest

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Detects the image format of a plugin's Base64-encoded `icon` field.
 *
 * Raster formats are recognised via every [ImageIO] reader registered on the JVM (PNG, JPEG, GIF,
 * BMP, ...). SVG is detected separately since it is a text/XML format and not covered by `ImageIO`.
 */
internal object IconDetector {

    private const val SVG_SNIFF_LENGTH = 512

    /**
     * Decodes the given Base64 icon content and returns the recognised image format name.
     *
     * For raster formats, this is the uppercase format name reported by the responsible `ImageIO`
     * reader (e.g. `"PNG"`, `"JPEG"`). For vector icons, `"SVG"` is returned.
     *
     * @throws IconFormatException if the content is not valid Base64 or its image format is not recognised
     */
    fun detectFormat(base64Icon: String): String {
        val bytes = try {
            Base64.getDecoder().decode(base64Icon)
        } catch (_: IllegalArgumentException) {
            throw IconFormatException("Icon field is not valid Base64 content")
        }

        if (looksLikeSvg(bytes)) {
            return "SVG"
        }

        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { imageInputStream ->
            val readers = ImageIO.getImageReaders(imageInputStream)
            if (readers.hasNext()) {
                return readers.next().formatName.uppercase()
            }
        }

        throw IconFormatException("Icon format could not be recognised by any registered ImageIO reader or as SVG")
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        val sniffLength = minOf(bytes.size, SVG_SNIFF_LENGTH)
        val text = String(bytes, 0, sniffLength, Charsets.UTF_8)
        return text.contains("<svg", ignoreCase = true)
    }
}
