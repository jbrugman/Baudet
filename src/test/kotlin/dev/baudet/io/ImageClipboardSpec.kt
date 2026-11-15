package dev.baudet.io

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.util.Base64

/**
 * The encoder is pure and headless-safe, so it is the part worth pinning without a display:
 * a PNG data URL carries the right prefix and decodes back to PNG magic bytes. The clipboard
 * read itself needs a real desktop and is left to manual checks.
 */
class ImageClipboardSpec : BehaviorSpec({
    given("a one pixel image") {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val url = ImageClipboard.pngDataUrl(image)
        `when`("encoding it to a data url") {
            then("it carries the png data-url prefix") {
                url.startsWith("data:image/png;base64,") shouldBe true
            }
            then("the payload decodes to png magic bytes") {
                val body = url.removePrefix("data:image/png;base64,")
                val bytes = Base64.getDecoder().decode(body)
                bytes.take(4).toList() shouldBe listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47)
            }
        }
    }
})
