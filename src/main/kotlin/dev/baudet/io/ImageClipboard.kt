package dev.baudet.io

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Reads the image currently on the system clipboard and encodes it as a PNG data URL, the
 * shape the OpenAI image_url part expects. Only the AWT image flavor is read; file and HTML
 * flavors are deliberately left for later. Every entry point is total: a clipboard with no
 * image, or one the toolkit cannot transfer, yields null rather than throwing, so the UI can
 * simply tell the user there was nothing to attach.
 */
object ImageClipboard {

    /** Encode an image as a base64 PNG data URL, ready for the image_url part of a request. */
    fun pngDataUrl(image: BufferedImage): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** The clipboard image as an ARGB raster, or null when there is no readable image. */
    fun readImage(): BufferedImage? = try {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        val flavor = contents?.transferDataFlavors?.firstOrNull { it == DataFlavor.imageFlavor }
        (flavor?.let { contents.getTransferData(it) } as? Image)?.toBufferedImage()
    } catch (_: Exception) {
        null
    }

    /** Coerce any AWT image to a raster; a loaded-but-not-yet-rasterized image is drawn into one. */
    private fun Image.toBufferedImage(): BufferedImage {
        if (this is BufferedImage) return this
        // A bare Image exposes no width/height property: the size needs an observer, and null
        // is fine here because a clipboard image is already fully loaded.
        val w = getWidth(null).coerceAtLeast(1)
        val h = getHeight(null).coerceAtLeast(1)
        val raster = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = raster.createGraphics()
        try {
            g.drawImage(this, 0, 0, null)
        } finally {
            g.dispose()
        }
        return raster
    }
}
