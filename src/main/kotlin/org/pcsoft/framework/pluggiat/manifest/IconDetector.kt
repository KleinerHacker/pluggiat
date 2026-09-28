/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

package org.pcsoft.framework.pluggiat.manifest

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

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
     * @throws IconFormatException if the content is not valid Base64, its image format is not recognised,
     * or the image reading facility of the JVM is unavailable (e.g. no `java.desktop` module)
     */
    fun detectFormat(base64Icon: String): String {
        // SECURITY: the icon is plugin-controlled text; a decode failure is reported as an invalid manifest
        // SECURITY: rather than propagating a raw decoder exception out of the scan.
        val bytes = try {
            Base64.getDecoder().decode(base64Icon)
        } catch (_: IllegalArgumentException) {
            throw IconFormatException("Icon field is not valid Base64 content")
        }

        if (looksLikeSvg(bytes)) {
            return "SVG"
        }

        // SECURITY: only the format is detected here - the image is never decoded into pixels, so a crafted
        // SECURITY: image cannot reach an image codec's parsing code during the scan.
        // SECURITY: a memory-backed stream is used directly instead of ImageIO.createImageInputStream, which
        // SECURITY: would write a temporary file whenever the JVM-wide ImageIO cache is enabled.
        val format = try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { imageInputStream ->
                val readers = ImageIO.getImageReaders(imageInputStream)
                if (readers.hasNext()) readers.next().formatName.uppercase() else null
            }
        } catch (e: LinkageError) {
            throw IconFormatException("Icon format cannot be checked, the image reading facility is unavailable (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            throw IconFormatException("Icon format cannot be checked, the image reading facility failed (${e.javaClass.simpleName})")
        }

        return format
            ?: throw IconFormatException("Icon format could not be recognised by any registered ImageIO reader or as SVG")
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        val sniffLength = minOf(bytes.size, SVG_SNIFF_LENGTH)
        val text = String(bytes, 0, sniffLength, Charsets.UTF_8)
        return text.contains("<svg", ignoreCase = true)
    }
}
