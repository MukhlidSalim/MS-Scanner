package com.example.engine.annotation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Storage and rendering for the signature vault, independent from ImageProcessor's JPEG pipeline.
 *
 * ROOT CAUSE of "the signature shows as a white rectangle instead of blending into the page":
 * every signature WAS drawn on a transparent canvas (Bitmap.createBitmap defaults to fully transparent
 * for ARGB_8888), but the vault saved it through ImageProcessor.saveBitmapToFile(), which always
 * calls bitmap.compress(Bitmap.CompressFormat.JPEG, ...). JPEG has no alpha channel, so the encoder
 * filled every transparent pixel with opaque white before writing the file - the transparency was lost
 * on save, not on draw. New signatures are now saved as PNG (lossless, alpha-preserving) through this
 * object, and [loadDisplayable] additionally repairs any signature saved by the OLD path so existing
 * saved signatures stop showing a white box too, with no re-drawing required from the user.
 */
object SignatureStore {
    private const val DIR = "signatures"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Saves [bitmap] as a lossless PNG (alpha channel preserved exactly). Returns the absolute path. */
    suspend fun savePng(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val file = File(dir(context), "sig_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file.absolutePath
    }

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /**
     * Loads a signature ready to display or place on a page: true alpha is kept as is; a LEGACY opaque
     * JPEG signature (white/near-white background, no transparency) is converted on the fly so pixels
     * close to the background colour become transparent and only the ink remains. [maxDim] bounds memory
     * for large legacy files.
     */
    suspend fun loadDisplayable(path: String, maxDim: Int = 1200): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return@withContext null
        if (hasRealAlpha(decoded)) return@withContext decoded
        val fixed = stripBackgroundToAlpha(decoded)
        if (fixed !== decoded) decoded.recycle()
        fixed
    }

    /** True when at least some pixels are not fully opaque (i.e. the file already has genuine transparency). */
    private fun hasRealAlpha(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w == 0 || h == 0) return false
        val stepX = max(1, w / 48); val stepY = max(1, h / 48)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if ((bmp.getPixel(x, y) ushr 24) != 0xFF) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * Legacy-compat conversion: estimates the background colour from the image corners, then turns every
     * pixel close to it transparent and keeps the ink opaque, with a soft edge in between (anti-aliased look).
     */
    private fun stripBackgroundToAlpha(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w < 2 || h < 2) return src
        val corners = intArrayOf(
            src.getPixel(0, 0), src.getPixel(w - 1, 0),
            src.getPixel(0, h - 1), src.getPixel(w - 1, h - 1)
        )
        val bgR = corners.map { (it ushr 16) and 0xFF }.average()
        val bgG = corners.map { (it ushr 8) and 0xFF }.average()
        val bgB = corners.map { it and 0xFF }.average()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowIn = IntArray(w)
        val rowOut = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(rowIn, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = rowIn[x]
                val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
                val dist = kotlin.math.sqrt(
                    (r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)
                ) / 441.67 // 441.67 == sqrt(3*255^2), normalises to 0..1
                // Close to background -> transparent; far from it (ink) -> opaque; smooth ramp between.
                val alpha = (255.0 * ((dist - 0.06) / 0.12).coerceIn(0.0, 1.0)).toInt()
                rowOut[x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(rowOut, 0, w, 0, y, w, 1)
        }
        return out
    }

    /**
     * Returns a new bitmap with the same alpha mask (so the stroke shape, thickness and anti-aliasing are
     * untouched) but every opaque pixel recoloured to [colorArgb]. Safe to call at any target size: callers
     * should resize AFTER tinting so edges stay smooth.
     */
    fun tinted(src: Bitmap, colorArgb: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }

    /** High-quality resize that keeps the alpha edge smooth (bilinear filtering). */
    fun resized(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= 0) return src
        val ratio = src.height.toFloat() / src.width.toFloat()
        val w = max(1, targetWidth)
        val h = max(1, (targetWidth * ratio).toInt())
        if (w == src.width && h == src.height) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Default swatches offered when placing or drawing a signature (ink-like + brand colours). */
    val DEFAULT_COLORS = listOf(
        Color.BLACK,
        Color.rgb(0x1A, 0x1A, 0x2E),   // ink navy
        Color.rgb(0x0D, 0x47, 0xA1),   // blue ink
        Color.rgb(0x8B, 0x00, 0x00),   // dark red (official stamps)
        Color.rgb(0x1B, 0x5E, 0x20)    // dark green
    )
}
