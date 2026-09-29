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

/**
 * Result of automatic edge detection for one page. Persisted next to the raw image so that every
 * screen (review, preview, editor) knows whether the crop is REAL or a full-image fallback.
 */
enum class DetectionStatus {
    /** Edges found and validated: the page is auto-cropped and perspective-corrected. */
    DETECTED,
    /** Detection ran (all passes) and found nothing reliable: full image kept, user must be told. */
    NOT_FOUND,
    /** Detection intentionally not run (PDF pages, auto-crop disabled). */
    SKIPPED,
    /** The user set the crop by hand in the editor. */
    MANUAL
}

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
    val confidence: Float,
    val detectionStatus: DetectionStatus = if (autoCropped) DetectionStatus.DETECTED else DetectionStatus.NOT_FOUND
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
 *    together with the [DetectionStatus], so a failed detection is never presented as a real crop.
 *  - Detection chain (one engine, DocumentDetector): guided pass -> relaxed-shape pass -> contrast-
 *    boosted pass. Only when all fail is the full image kept, marked NOT_FOUND for manual review.
 *  - Rendering order is ALWAYS: perspective warp (raw + quad) -> user rotation -> filter -> enhancements.
 *  - All work runs on Dispatchers.Default / IO; callers never block the main thread.
 */
object DocumentPipeline {
    /** One default scan filter for every source (previously AUTO in some paths, MAGIC in others). */
    val DEFAULT_FILTER: FilterType = FilterType.AUTO
    private const val CAPTURE_MAX_SIDE = 2400
    private const val IMPORT_MAX_SIDE = 2048
    private const val PDF_MAX_SIDE = 2200
    private const val ALT_PASS_MAX_SIDE = 900

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
     * rectangular, so no border detection is run (quad = full page, status SKIPPED); the user can
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

    /**
     * Re-runs the full detection chain on an existing raw page and re-renders it ("Retry detection",
     * batch "auto-crop all"). When nothing is found the previous quad is kept and the page stays
     * NOT_FOUND (never silently replaced by a fake crop).
     */
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
            val detection = detectWithFallback(raw, null, expectedAspectRatio)
            val previousStatus = QuadStore.loadStatus(rawPath)
            val quad = detection?.quad ?: (QuadStore.load(rawPath) ?: DocumentQuad.fullQuad())
            val status = when {
                detection != null -> DetectionStatus.DETECTED
                previousStatus == DetectionStatus.MANUAL -> DetectionStatus.MANUAL
                else -> DetectionStatus.NOT_FOUND
            }
            QuadStore.save(rawPath, quad)
            QuadStore.saveStatus(rawPath, status)
            val out = renderBitmap(raw, quad, rotationDegrees, filter, 0f, 1f, false)
            try {
                val path = ImageProcessor.saveBitmapToFile(context, out, "${prefix}_")
                ProcessedPage(rawPath, path, quad, detection != null, detection?.confidence ?: 0f, status)
            } finally {
                if (out !== raw) out.recycle()
            }
        } finally {
            raw.recycle()
        }
    }

    /** Detection only (crop editor "Auto Detect"). Returns null when nothing reliable is found. */
    suspend fun detectOnRaw(bitmap: Bitmap, expectedAspectRatio: Float? = null): DocumentDetection? =
        withContext(Dispatchers.Default) { detectWithFallback(bitmap, null, expectedAspectRatio) }

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

    /**
     * Book mode: splits a two-page spread (already cropped / corrected) into two pages at the spine.
     * The spine is searched in the middle 20% (darkest / most shadowed column = binding), then 1% gutter is
     * trimmed on each side. Each half becomes its own raw image (quad = full, status MANUAL) so it can be
     * re-edited independently. Returns (leftPath, rightPath) or null when the image cannot be read.
     */
    suspend fun splitSpread(context: Context, imagePath: String): Pair<String, String>? = withContext(Dispatchers.Default) {
        val src = ImageProcessor.loadBitmapFromFile(imagePath, CAPTURE_MAX_SIDE) ?: return@withContext null
        try {
            if (src.width < 64) return@withContext null
            val spine = findSpine(src)
            val gutter = (src.width * 0.01f).roundToInt()
            val leftW = (spine - gutter).coerceAtLeast(1)
            val rightX = (spine + gutter).coerceAtMost(src.width - 1)
            val left = Bitmap.createBitmap(src, 0, 0, leftW, src.height)
            val right = Bitmap.createBitmap(src, rightX, 0, src.width - rightX, src.height)
            try {
                val lp = ImageProcessor.saveBitmapToFile(context, left, "book_raw_")
                val rp = ImageProcessor.saveBitmapToFile(context, right, "book_raw_")
                listOf(lp, rp).forEach {
                    QuadStore.save(it, DocumentQuad.fullQuad())
                    QuadStore.saveStatus(it, DetectionStatus.MANUAL)
                }
                lp to rp
            } finally {
                if (left !== src) left.recycle()
                if (right !== src) right.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    /** Column with the lowest mean brightness in the central 20% of a down-scaled copy. */
    private fun findSpine(src: Bitmap): Int {
        val scale = 400f / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(10)
        val h = (src.height * scale).roundToInt().coerceAtLeast(10)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        try {
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val from = (w * 0.4f).toInt()
            val to = (w * 0.6f).toInt().coerceAtLeast(from + 1)
            var bestX = w / 2
            var best = Float.MAX_VALUE
            for (x in from until to) {
                var sum = 0f
                for (y in 0 until h) {
                    val p = px[y * w + x]
                    sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                }
                // Slight preference for the exact centre when columns are equally dark (plain white pages).
                val score = sum / h + kotlin.math.abs(x - w / 2f) * 0.15f
                if (score < best) { best = score; bestX = x }
            }
            return (bestX / scale).roundToInt().coerceIn(1, src.width - 1)
        } finally {
            if (small !== src) small.recycle()
        }
    }

    // ------------------------------------------------------------------ detection chain

    /**
     * One engine (DocumentDetector), three passes:
     *  1. guided by the live-preview quad when available (sub-pixel confirmation on the still);
     *  2. without the ID-card / passport aspect constraint (user may have chosen the wrong mode);
     *  3. on a down-scaled, contrast-boosted copy (white paper on light desk, soft shadows).
     * Returns null only when every pass fails. Coordinates are normalized, so pass 3 is size-independent.
     */
    private suspend fun detectWithFallback(
        upright: Bitmap,
        prior: DocumentQuad?,
        expectedAspectRatio: Float?
    ): DocumentDetection? {
        fun valid(d: DocumentDetection?) = d?.takeIf { DocumentDetector.isPlausible(it.quad) && !it.quad.isFullImage() }

        valid(runCatching { DocumentDetector.detectWithPrior(upright, prior, expectedAspectRatio) }.getOrNull())?.let { return it }
        if (expectedAspectRatio != null) {
            valid(runCatching { DocumentDetector.detect(upright, null) }.getOrNull())?.let { return it }
        }
        val scale = ALT_PASS_MAX_SIDE.toFloat() / max(upright.width, upright.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * scale).roundToInt().coerceAtLeast(32),
                (upright.height * scale).roundToInt().coerceAtLeast(32),
                true
            )
        } else upright
        try {
            val boosted = ImageProcessor.adjustEnhancements(small, 0f, 1.8f, false)
            try {
                return valid(runCatching { DocumentDetector.detect(boosted, null) }.getOrNull())
                    ?.copy(imageWidth = upright.width, imageHeight = upright.height)
            } finally {
                if (boosted !== small) boosted.recycle()
            }
        } finally {
            if (small !== upright) small.recycle()
        }
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
        val detection = if (autoCrop) detectWithFallback(upright, prior, expectedAspectRatio) else null
        val quad = detection?.quad ?: DocumentQuad.fullQuad()
        val status = when {
            !autoCrop -> DetectionStatus.SKIPPED
            detection != null -> DetectionStatus.DETECTED
            else -> DetectionStatus.NOT_FOUND
        }
        QuadStore.save(rawPath, quad)
        QuadStore.saveStatus(rawPath, status)
        val out = renderBitmap(upright, quad, 0, filter, 0f, 1f, false)
        val procPath = try {
            ImageProcessor.saveBitmapToFile(context, out, "${prefix}_proc_")
        } finally {
            if (out !== upright) out.recycle()
        }
        return ProcessedPage(rawPath, procPath, quad, detection != null, detection?.confidence ?: 0f, status)
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
 * Persists the crop quad ("<raw>.quad.json") and the detection status ("<raw>.detect") next to the
 * raw image, so every workflow that only passes (rawPath, processedPath) pairs (camera -> edit
 * session -> crop editor -> save) keeps the real quad AND knows whether it was really detected.
 */
object QuadStore {
    private fun fileFor(rawPath: String) = File("$rawPath.quad.json")
    private fun statusFileFor(rawPath: String) = File("$rawPath.detect")

    fun save(rawPath: String, quad: DocumentQuad) {
        try { fileFor(rawPath).writeText(quad.toJson()) } catch (_: Exception) { }
    }

    fun load(rawPath: String): DocumentQuad? = try {
        val f = fileFor(rawPath)
        if (f.exists()) DocumentQuad.fromJsonOrNull(f.readText()) else null
    } catch (_: Exception) { null }

    fun saveStatus(rawPath: String, status: DetectionStatus) {
        try { statusFileFor(rawPath).writeText(status.name) } catch (_: Exception) { }
    }

    /** Null for pages created before detection status was recorded (treated as "unknown"). */
    fun loadStatus(rawPath: String): DetectionStatus? = try {
        val f = statusFileFor(rawPath)
        if (f.exists()) DetectionStatus.valueOf(f.readText().trim()) else null
    } catch (_: Exception) { null }

    fun delete(rawPath: String) {
        try { fileFor(rawPath).delete() } catch (_: Exception) { }
        try { statusFileFor(rawPath).delete() } catch (_: Exception) { }
    }
}
