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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class IconDetectorTest {

    // A well-known minimal 1x1 transparent PNG, used as a raster test fixture.
    private val pngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

    private val svgBase64 = Base64.getEncoder().encodeToString(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()
    )

    /**
     * Use case: a Base64-encoded PNG icon is recognised via the ImageIO-based raster detection path.
     */
    @Test
    fun `detects PNG icon via ImageIO`() {
        assertEquals("PNG", IconDetector.detectFormat(pngBase64))
    }

    /**
     * Use case: a Base64-encoded SVG icon is recognised via the XML-based detection path, since SVG
     * is not covered by ImageIO.
     */
    @Test
    fun `detects SVG icon via XML sniffing`() {
        assertEquals("SVG", IconDetector.detectFormat(svgBase64))
    }

    /**
     * Use case: Base64 content that decodes successfully but is neither a known raster format nor
     * SVG is reported as an unrecognised icon format instead of silently passing.
     */
    @Test
    fun `throws for unrecognisable image content`() {
        val garbage = Base64.getEncoder().encodeToString("not an image".toByteArray())
        assertThrows(IconFormatException::class.java) { IconDetector.detectFormat(garbage) }
    }

    /**
     * Use case: content that is not valid Base64 at all is reported as an icon format error rather
     * than propagating a raw decoding exception.
     */
    @Test
    fun `throws for content that is not valid Base64`() {
        assertThrows(IconFormatException::class.java) { IconDetector.detectFormat("not-base64!!!") }
    }
}
