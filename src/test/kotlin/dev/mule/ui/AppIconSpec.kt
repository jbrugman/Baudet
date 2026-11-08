package dev.mule.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage

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
})
