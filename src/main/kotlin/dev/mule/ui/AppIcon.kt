package dev.mule.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * The application mark: a front-on donkey, the literal face behind the "AI reversed
 * into IA" pun that named the app. Painted in code rather than shipped as a bitmap:
 * it draws sharp at any taskbar size, adds no binary to the jar, and stays
 * reflection-free for a future native-image build. The long tapered ears and the
 * plain muzzle are what read as "donkey"; a round face with a coloured nose instead
 * reads as a rabbit, which is exactly the confusion to avoid.
 */
object AppIcon {

    // Modelled in a 100x100 space and scaled per draw, so every size renders at its
    // own resolution instead of shrinking one big bitmap down.
    private val head = Color(0x8B9099)
    private val mane = Color(0x4A4F57)
    private val muzzle = Color(0xC2C7CF)
    private val innerEar = Color(0xC08A95)
    private val ink = Color(0x24272C)

    /** Render the mark with a transparent background at exactly [size] pixels square. */
    fun render(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.scale(size / 100.0, size / 100.0)
            paint(g)
        } finally {
            g.dispose()
        }
        return img
    }

    /** The usual window-manager sizes, largest last, so the OS can pick a sharp one. */
    fun images(): List<Image> = intArrayOf(16, 24, 32, 48, 64, 128, 256, 512).map { render(it) }

    private fun paint(g: Graphics2D) {
        drawEar(g, left = true)
        drawEar(g, left = false)
        drawForelock(g)

        // Head over the ear bases, so the ears look attached rather than floating.
        g.color = head
        g.fill(Ellipse2D.Float(31f, 34f, 38f, 52f))

        // Eyes sit on the face, well above the muzzle.
        fillOval(g, ink, 41f, 52f, 6f, 7f)
        fillOval(g, ink, 53f, 52f, 6f, 7f)

        // Muzzle, then narrow vertical nostrils and a mouth line: a clearly separate
        // lower face, so the dots never read as a second pair of eyes.
        g.color = muzzle
        g.fill(Ellipse2D.Float(32f, 66f, 36f, 24f))
        fillOval(g, ink, 44f, 72f, 3f, 8f)
        fillOval(g, ink, 53f, 72f, 3f, 8f)
        g.color = ink
        g.stroke = BasicStroke(1.4f)
        val mouth = Path2D.Float()
        mouth.moveTo(44.0, 85.0)
        mouth.quadTo(50.0, 88.0, 56.0, 85.0)
        g.draw(mouth)
    }

    private fun drawEar(g: Graphics2D, left: Boolean) {
        val bx = if (left) 41.0 else 59.0
        val by = 44.0
        val saved = g.transform
        val t = AffineTransform(saved)
        t.rotate(Math.toRadians(if (left) -20.0 else 20.0), bx, by)
        g.transform = t
        fillShape(g, head, leaf(bx, by, 40.0, 8.0, 10.0))
        fillShape(g, innerEar, leaf(bx, by - 3.0, 30.0, 3.4, 4.8))
        g.transform = saved
    }

    /** A pointed leaf (donkey ear): wide flat base tapering to a tip straight above it. */
    private fun leaf(bx: Double, by: Double, len: Double, halfBase: Double, bow: Double): Path2D.Double {
        val p = Path2D.Double()
        p.moveTo(bx - halfBase, by)
        p.quadTo(bx - bow, by - len * 0.6, bx, by - len)
        p.quadTo(bx + bow, by - len * 0.6, bx + halfBase, by)
        p.closePath()
        return p
    }

    private fun drawForelock(g: Graphics2D) {
        val p = Path2D.Float()
        p.moveTo(45.0, 42.0)
        p.quadTo(50.0, 29.0, 55.0, 42.0)
        p.closePath()
        fillShape(g, mane, p)
    }

    private fun fillOval(g: Graphics2D, c: Color, x: Float, y: Float, w: Float, h: Float) {
        g.color = c
        g.fill(Ellipse2D.Float(x, y, w, h))
    }

    private fun fillShape(g: Graphics2D, c: Color, s: java.awt.Shape) {
        g.color = c
        g.fill(s)
    }
}
