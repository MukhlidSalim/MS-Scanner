package com.example.engine.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.model.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Outcome of processing one page through the unified pipeline. */
data class ProcessedPage(
    /** Upright, uncropped source. All quads refer to this image. */
    val rawPath: String,
    /** Rendered output (warp -> rotate -> filter -> enhancements). */
    val processedPath: String,
    /** Quad normalized to [rawPath]. Full image when nothing was detected. */
    val quad: DocumentQuad,
    /** True when [quad] comes from a real detection (or a confirmed live-preview quad). */
    val autoCropped: Boolean,
    val confidence: Float
)

/** Expected document shapes. Values are long-side / short-side. */
object ScanAspect {
    const val ID_CARD = 1.586f   // ISO/IEC 7810 ID-1
    const val PASSPORT = 1.42f   // ID-3 data page
}

/**
 * THE single processing pipeline for every input source:
 *   Single scan, Batch scan, ID card, Passport, Imported photos, Imported PDF, Re-crop, Editor save,
 *   Rotate, Filter.
 *
 * Invariants:
 *  - A raw image is always saved upright (EXIF applied) and never modified afterwards.
 *  - The quad is always normalized to that raw image and persisted next to it ([QuadStore]),
 *    so the editor opens with the real detected quad instead of re-guessing.
 *  - Rendering order is ALWAYS: perspective warp (raw + quad) -> user rotation -> filter -> enhancements.
 *  - All work runs on Dispatchers.Default / IO; callers never block the main thread.
 */
object DocumentPipeline {

    /** One default scan filter for every source (previously AUTO in some paths, MAGIC in others). */
    val DEFAULT_FILTER: FilterType = FilterType.AUTO

    private const val CAPTURE_MAX_SIDE = 2400
    private const val IMPORT_MAX_SIDE = 2048
    private const val PDF_MAX_SIDE = 2200

    // ------------------------------------------------------------------ entry points

    /** Camera capture (in-app CameraX). [previewQuad] is the live quad at shutter time (same frame). */
    suspend fun processCapturedFile(
        context: Context,
        file: File,
        previewQuad: DocumentQuad?,
        expectedAspectRatio: Float?,
        filter: FilterType? = FilterType.AUTO,
        autoCrop: Boolean = true
    ): ProcessedPage? = withContext(Dispatchers.Default) {
        val upright = decodeUprightFile(file.absolutePath, CAPTURE_MAX_SIDE) ?: return@withContext null
        try {
            processUpright(context, upright, previewQuad, expectedAspectRatio, filter, autoCrop, "scan")
        } finally {
            upright.recycle()
            file.delete()
        }
    }

    /** Imported gallery image / system camera result / shared image. */
    suspend fun processUri(
        context: Context,
        uri: Uri,
        autoCrop: Boolean = true,
        expectedAspectRatio: Float? = null,
        filter: FilterType? = FilterType.AUTO,
        prefix: String = "import"
    ): ProcessedPage? = withContext(Dispatchers.Default) {
        val upright = decodeUprightUri(context, uri, IMPORT_MAX_SIDE) ?: return@withContext null
        try {
            processUpright(context, upright, null, expectedAspectRatio, filter, autoCrop, prefix)
        } finally {
            upright.recycle()
        }
    }

    /** A file path produced outside CameraX (e.g. system camera temp file). */
    suspend fun processFile(
        context: Context,
        path: String,
        autoCrop: Boolean = true,
        expectedAspectRatio: Float? = null,
        filter: FilterType? = FilterType.AUTO,
        prefix: String = "scan"
    ): ProcessedPage? = withContext(Dispatchers.Default) {
        val upright = decodeUprightFile(path, CAPTURE_MAX_SIDE) ?: return@withContext null
        try {
            processUpright(context, upright, null, expectedAspectRatio, filter, autoCrop, prefix)
        } finally {
            upright.recycle()
        }
    }

    /**
     * PDF import: each page is rasterized on a white background. PDF pages are already flat and
     * rectangular, so no border detection is run (quad = full page, autoCropped = false); the user can
     * still crop manually in the editor.
     */
    suspend fun importPdf(
        context: Context,
        uri: Uri,
        filter: FilterType? = null,
        onPage: suspend (index: Int, page: ProcessedPage) -> Unit = { _, _ -> }
    ): List<ProcessedPage> = withContext(Dispatchers.IO) {
        val result = ArrayList<ProcessedPage>()
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext result
        try {
            val renderer = PdfRenderer(pfd)
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bitmap: Bitmap
                    try {
                        val scale = PDF_MAX_SIDE.toFloat() / max(page.width, page.height)
                        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                    try {
                        val processed = processUpright(context, bitmap, null, null, filter, autoCrop = false, prefix = "pdf_p${i + 1}")
                        result.add(processed)
                        onPage(i, processed)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
        result
    }

    /** Re-runs detection on an existing raw page and re-renders it (batch "auto-crop all"). */
    suspend fun redetect(
        context: Context,
        rawPath: String,
        expectedAspectRatio: Float? = null,
        rotationDegrees: Int = 0,
        filter: FilterType? = FilterType.AUTO,
        prefix: String = "recrop"
    ): ProcessedPage? = withContext(Dispatchers.Default) {
        val raw = ImageProcessor.loadBitmapFromFile(rawPath, IMPORT_MAX_SIDE) ?: return@withContext null
        try {
            val detection = runCatching { DocumentDetector.detect(raw, expectedAspectRatio) }.getOrNull()
            val quad = detection?.quad ?: (QuadStore.load(rawPath) ?: DocumentQuad.fullQuad())
            QuadStore.save(rawPath, quad)
            val out = renderBitmap(raw, quad, rotationDegrees, filter, 0f, 1f, false)
            try {
                val path = ImageProcessor.saveBitmapToFile(context, out, "${prefix}_")
                ProcessedPage(rawPath, path, quad, detection != null, detection?.confidence ?: 0f)
            } finally {
                if (out !== raw) out.recycle()
            }
        } finally {
            raw.recycle()
        }
    }

    /** Detection only (crop editor "Auto Detect"). Returns null when nothing reliable is found. */
    suspend fun detectOnRaw(bitmap: Bitmap, expectedAspectRatio: Float? = null): DocumentDetection? =
        withContext(Dispatchers.Default) { DocumentDetector.detect(bitmap, expectedAspectRatio) }

    /**
     * Authoritative render used by editor save, rotate, filter and re-crop.
     * Returns the saved path, or null if the raw image cannot be loaded.
     */
    suspend fun render(
        context: Context,
        rawPath: String,
        quad: DocumentQuad,
        rotationDegrees: Int,
        filter: FilterType?,
        brightness: Float = 0f,
        contrast: Float = 1f,
        sharpen: Boolean = false,
        prefix: String = "edit_proc"
    ): String? = withContext(Dispatchers.Default) {
        val raw = ImageProcessor.loadBitmapFromFile(rawPath, IMPORT_MAX_SIDE) ?: return@withContext null
        try {
            val safeQuad = if (DocumentDetector.isPlausible(quad)) quad.clamped() else DocumentQuad.fullQuad()
            QuadStore.save(rawPath, safeQuad)
            val out = renderBitmap(raw, safeQuad, rotationDegrees, filter, brightness, contrast, sharpen)
            try {
                ImageProcessor.saveBitmapToFile(context, out, "${prefix}_")
            } finally {
                if (out !== raw) out.recycle()
            }
        } finally {
            raw.recycle()
        }
    }

    /**
     * In-memory render (used for editor previews). The caller owns [raw]; the returned bitmap may be
     * [raw] itself when no operation changes it, so compare by identity before recycling.
     */
    suspend fun renderBitmap(
        raw: Bitmap,
        quad: DocumentQuad,
        rotationDegrees: Int,
        filter: FilterType?,
        brightness: Float,
        contrast: Float,
        sharpen: Boolean
    ): Bitmap {
        var current = raw
        fun replace(next: Bitmap) {
            if (next !== current) {
                if (current !== raw) current.recycle()
                current = next
            }
        }
        replace(warp(raw, quad))
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot != 0) replace(rotate(current, rot))
        if (filter != null) replace(ImageProcessor.applyFilter(current, filter))
        if (brightness != 0f || contrast != 1f || sharpen) {
            replace(ImageProcessor.adjustEnhancements(current, brightness, contrast, sharpen))
        }
        return current
    }

    /**
     * Clockwise rotation by a multiple of 90 degrees. This is the ONLY rotation used for pages, so it is
     * guaranteed to match DocumentQuad.rotated() (x, y) -> (1 - y, x) for 90 degrees.
     * Returns [src] itself for 0.
     */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * True perspective rectification using the full homography (Matrix.setPolyToPoly with 4 points).
     * Returns [src] itself when the quad covers the whole image (no resampling, no quality loss).
     */
    fun warp(src: Bitmap, quad: DocumentQuad): Bitmap {
        if (quad.isFullImage()) return src
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val (dstW, dstH) = quad.targetDimensions(w, h)
        val matrix = quad.getTransformationMatrix(w, h, dstW, dstH)
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ------------------------------------------------------------------ core

    private suspend fun processUpright(
        context: Context,
        upright: Bitmap,
        prior: DocumentQuad?,
        expectedAspectRatio: Float?,
        filter: FilterType?,
        autoCrop: Boolean,
        prefix: String
    ): ProcessedPage {
        val rawPath = ImageProcessor.saveBitmapToFile(context, upright, "${prefix}_raw_")
        val detection = if (autoCrop) {
            runCatching { DocumentDetector.detectWithPrior(upright, prior, expectedAspectRatio) }.getOrNull()
        } else null
        val quad = detection?.quad?.takeIf { DocumentDetector.isPlausible(it) } ?: DocumentQuad.fullQuad()
        val autoCropped = detection != null && !quad.isFullImage()
        QuadStore.save(rawPath, quad)
        val out = renderBitmap(upright, quad, 0, filter, 0f, 1f, false)
        val procPath = try {
            ImageProcessor.saveBitmapToFile(context, out, "${prefix}_proc_")
        } finally {
            if (out !== upright) out.recycle()
        }
        return ProcessedPage(rawPath, procPath, quad, autoCropped, detection?.confidence ?: 0f)
    }

    // ------------------------------------------------------------------ decoding

    fun decodeUprightFile(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        val rotation = try {
            exifToDegrees(ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } catch (_: Exception) { 0 }
        return uprightAndBound(decoded, rotation, maxSide)
    }

    fun decodeUprightUri(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = try {
            resolver.openInputStream(uri)?.use {
                exifToDegrees(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            } ?: 0
        } catch (_: Exception) { 0 }
        return uprightAndBound(decoded, rotation, maxSide)
    }

    private fun uprightAndBound(decoded: Bitmap, rotation: Int, maxSide: Int): Bitmap {
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation == 0 && scale == 1f) return decoded
        val m = android.graphics.Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (out !== decoded) decoded.recycle()
        return out
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun exifToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

/**
 * Persists the crop quad next to its raw image ("<raw>.quad.json"), so that every workflow that only
 * passes (rawPath, processedPath) pairs (camera -> edit session -> crop editor) keeps the real quad.
 */
object QuadStore {
    private fun fileFor(rawPath: String) = File("$rawPath.quad.json")

    fun save(rawPath: String, quad: DocumentQuad) {
        try { fileFor(rawPath).writeText(quad.toJson()) } catch (_: Exception) { }
    }

    fun load(rawPath: String): DocumentQuad? = try {
        val f = fileFor(rawPath)
        if (f.exists()) DocumentQuad.fromJsonOrNull(f.readText()) else null
    } catch (_: Exception) { null }

    fun delete(rawPath: String) {
        try { fileFor(rawPath).delete() } catch (_: Exception) { }
    }
}
