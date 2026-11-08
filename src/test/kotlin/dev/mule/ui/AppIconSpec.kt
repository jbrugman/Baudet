package dev.mule.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The icon is drawn in code, so the behaviour worth locking is that it paints the
 * mark at the sizes a window manager asks for, and keeps the muzzle as the lighter
 * band across the lower face -- the trait that separates a donkey from a round
 * mascot that reads as a rabbit.
 */
class AppIconSpec : BehaviorSpec({

    given("the application mark at a small taskbar size") {
        val img = AppIcon.render(32)
        `when`("rendered at 32 pixels") {
            then("it is exactly that many pixels square") {
                img.width shouldBe 32
                img.height shouldBe 32
            }
            then("it paints a shape rather than a blank square") {
                var painted = 0
                for (y in 0 until 32) for (x in 0 until 32)
                    if (((img.getRGB(x, y) ushr 24) and 0xFF) > 0) painted++
                (painted > 32 * 32 / 10) shouldBe true
            }
        }
    }

    given("the mark rendered large enough to sample") {
        val img = AppIcon.render(200)
        fun lum(px: Int): Int =
            (0.299 * ((px ushr 16) and 0xFF) +
                0.587 * ((px ushr 8) and 0xFF) +
                0.711 * (px and 0xFF)).toInt()
        `when`("comparing the muzzle to the forehead") {
            then("the muzzle is the lighter band across the lower face") {
                // 38%/45% is forehead grey, 50%/78% is the muzzle in the mule's space.
                val forehead = img.getRGB(76, 90)
                val muzzle = img.getRGB(100, 156)
                ((muzzle ushr 24) and 0xFF) shouldBe 255
                (lum(muzzle) > lum(forehead)) shouldBe true
            }
        }
    }

    given("the full icon set") {
        `when`("asking for the images to hand to the window manager") {
            then("it is a square of each taskbar size, largest last") {
                val sizes = AppIcon.images().map { (it as BufferedImage).width to it.getHeight(null) }
                sizes shouldBe listOf(
                    16 to 16, 24 to 24, 32 to 32, 48 to 48,
                    64 to 64, 128 to 128, 256 to 256, 512 to 512
                )
            }
        }
    }

    given("the macOS icon set written to disk") {
        val dir = Files.createTempDirectory("mule-iconset")
        val files = AppIcon.writeIconSet(dir)
        val expected = mapOf(
            "icon_16x16.png" to 16, "icon_16x16@2x.png" to 32,
            "icon_32x32.png" to 32, "icon_32x32@2x.png" to 64,
            "icon_128x128.png" to 128, "icon_128x128@2x.png" to 256,
            "icon_256x256.png" to 256, "icon_256x256@2x.png" to 512,
            "icon_512x512.png" to 512, "icon_512x512@2x.png" to 1024,
        )
        `when`("written under iconutil's canonical layout") {
            then("every PNG is named and sized exactly as the .iconset contract demands") {
                val actual = files.associate {
                    val img = ImageIO.read(it.toFile())
                    it.fileName.toString() to (img.width to img.height)
                }
                actual shouldBe expected.mapValues { (_, edge) -> edge to edge }
            }
        }
    }
})
