package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

// Phase 3C phone-photo-ingestion fix: an ordinary modern phone camera JPEG (often several MB) must
// become AI/Evidence-appropriate automatically - the user must never have to resize/compress it
// manually, and a backend 413 must never be the normal failure mode for a real phone photo. These
// bounds are enforced HERE, client-side, safely under the backend's own MAX_IMAGE_UPLOAD_BYTES
// (2,000,000 bytes) hard limit - the backend limit itself is unchanged and remains the safety net.
private const val MAX_EVIDENCE_LONGEST_EDGE_PX = 2048
private const val MAX_EVIDENCE_BYTES = 1_500_000
private const val INITIAL_JPEG_QUALITY = 88
private const val MIN_JPEG_QUALITY = 40
private const val QUALITY_STEP = 12
private const val MAX_QUALITY_PASSES = 5
private const val MIN_LONGEST_EDGE_PX = 640
private const val DIMENSION_STEP_FACTOR = 0.75f
private const val MAX_DIMENSION_PASSES = 4

internal class InvestigationEvidenceConversionException(
  override val message: String,
  override val cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun bitmapToInvestigationEvidence(
    bitmap: Bitmap,
    slotIndex: Int,
    filename: String,
    source: InvestigationEvidenceSource,
): InvestigationEvidenceInput {
  val output = ByteArrayOutputStream()
  val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
  if (!compressed) {
    throw IllegalStateException("Unable to convert captured bitmap to PNG bytes.")
  }
  return InvestigationEvidenceInput(
      slotIndex = slotIndex,
      filename = filename,
      mimeType = "image/png",
      bytes = output.toByteArray(),
      source = source,
  )
}

internal fun heicBytesToInvestigationEvidence(
    heicBytes: ByteArray,
    slotIndex: Int,
    filename: String,
    source: InvestigationEvidenceSource,
): InvestigationEvidenceInput {
  return InvestigationEvidenceInput(
      slotIndex = slotIndex,
      filename = filename,
      mimeType = "image/heic",
      bytes = heicBytes.copyOf(),
      source = source,
  )
}

/**
 * The single shared normalization boundary for every Evidence source (phone camera, gallery,
 * glasses live capture) - called uniformly by InvestigationSessionRepository.uploadEvidence for
 * ALL of them, never a second upload/normalization path. For newly ingested Evidence only (never
 * mutates anything already persisted): bakes in EXIF orientation, downscales oversized dimensions
 * (never upscales), and JPEG-encodes within a deterministic client-side byte budget safely under
 * the backend's own hard limit - so a real phone photo either comes out normalized and acceptable,
 * or this fails loudly and locally, never as a surprise backend 413. Already-small, already-upright,
 * already-supported input is returned byte-identical - no needless recompression.
 */
internal fun normalizeImageEvidenceForBackend(
    evidence: InvestigationEvidenceInput,
): InvestigationEvidenceInput {
  val mimeType = evidence.mimeType.trim().lowercase()
  val isHeic = mimeType == "image/heic" || mimeType == "image/heif"
  if (!isHeic && mimeType != "image/jpeg" && mimeType != "image/png") {
    throw InvestigationEvidenceConversionException(
        "Unsupported investigation image mime type: ${evidence.mimeType}.",
    )
  }

  val orientation = readOrientation(evidence.bytes)
  val needsOrientationFix = orientation != ExifInterface.ORIENTATION_NORMAL &&
      orientation != ExifInterface.ORIENTATION_UNDEFINED
  val bounds = decodeBounds(evidence.bytes)
      ?: throw InvestigationEvidenceConversionException(
          "Failed to read evidence ${evidence.filename} for normalization.",
      )
  val alreadyWithinDimensionCap = max(bounds.first, bounds.second) <= MAX_EVIDENCE_LONGEST_EDGE_PX
  val alreadyWithinByteBudget = evidence.bytes.size <= MAX_EVIDENCE_BYTES

  // Byte-identical pass-through: HEIC always needs conversion regardless of size, but an
  // already-supported, already-upright, already-bounded JPEG/PNG needs no work at all.
  if (!isHeic && !needsOrientationFix && alreadyWithinDimensionCap && alreadyWithinByteBudget) {
    return evidence
  }

  val decoded = BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size)
      ?: throw InvestigationEvidenceConversionException(
          "Failed to decode evidence ${evidence.filename} for normalization.",
      )
  val oriented = applyOrientation(decoded, orientation)

  val normalizedBytes = try {
    encodeWithinBudget(oriented, MAX_EVIDENCE_LONGEST_EDGE_PX, MAX_EVIDENCE_BYTES)
  } finally {
    oriented.recycle()
  }

  return evidence.copy(
      filename = toJpegFilename(evidence.filename),
      mimeType = "image/jpeg",
      bytes = normalizedBytes,
  )
}

/**
 * Cheap EXIF orientation peek - never decodes pixels. Returns ORIENTATION_NORMAL (identity) when
 * no EXIF block exists at all (the common PNG/glasses case), never throws.
 */
internal fun readOrientation(bytes: ByteArray): Int {
  return try {
    ByteArrayInputStream(bytes).use { input -> ExifInterface(input) }
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  } catch (e: IOException) {
    ExifInterface.ORIENTATION_NORMAL
  }
}

/** Cheap dimension-only peek (inJustDecodeBounds) - never decodes pixels. */
internal fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
  val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  if (options.outWidth <= 0 || options.outHeight <= 0) return null
  return options.outWidth to options.outHeight
}

/** Mirrors the existing EXIF-orientation-to-Matrix mapping used for live glasses frames
 * (StreamViewModel.getTransform) - kept as a small, self-contained duplicate here rather than
 * extracting a shared utility, since this file owns Evidence normalization exclusively. */
internal fun orientationMatrix(orientation: Int): Matrix {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    else -> Unit // NORMAL/UNDEFINED - identity, no transform.
  }
  return matrix
}

/** Bakes EXIF orientation into pixels; returns the SAME bitmap unchanged when no correction is
 * needed (identity matrix), recycling the source only when a genuinely new bitmap was produced. */
internal fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = orientationMatrix(orientation)
  if (matrix.isIdentity) return bitmap
  return try {
    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed !== bitmap) bitmap.recycle()
    transformed
  } catch (e: OutOfMemoryError) {
    bitmap
  }
}

/** Downscales to fit within maxLongestEdge, preserving aspect ratio - NEVER upscales (returns the
 * same bitmap unchanged when it already fits). */
internal fun scaleToLongestEdge(bitmap: Bitmap, maxLongestEdge: Int): Bitmap {
  val longest = max(bitmap.width, bitmap.height)
  if (longest <= maxLongestEdge) return bitmap
  val scale = maxLongestEdge.toFloat() / longest.toFloat()
  val newWidth = max(1, (bitmap.width * scale).toInt())
  val newHeight = max(1, (bitmap.height * scale).toInt())
  return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

/**
 * Bounded, deterministic JPEG encode-within-budget: an outer dimension-reduction loop (capped at
 * MAX_DIMENSION_PASSES) wrapping an inner quality-reduction loop (capped at MAX_QUALITY_PASSES,
 * floored at MIN_JPEG_QUALITY) - never loops indefinitely. Produces a valid image at or under
 * maxBytes, or throws InvestigationEvidenceConversionException locally (never uploads a
 * still-oversized image and never silently exceeds the budget it reports success for).
 */
internal fun encodeWithinBudget(bitmap: Bitmap, maxLongestEdge: Int, maxBytes: Int): ByteArray {
  var current = scaleToLongestEdge(bitmap, maxLongestEdge)
  var ownsCurrent = current !== bitmap
  try {
    var dimensionPass = 0
    while (true) {
      var quality = INITIAL_JPEG_QUALITY
      repeat(MAX_QUALITY_PASSES) {
        val output = ByteArrayOutputStream()
        val ok = current.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (!ok) {
          throw InvestigationEvidenceConversionException(
              "Failed to JPEG-encode evidence during normalization.")
        }
        val bytes = output.toByteArray()
        if (bytes.size <= maxBytes) {
          return bytes
        }
        quality = max(MIN_JPEG_QUALITY, quality - QUALITY_STEP)
      }
      dimensionPass += 1
      val currentLongest = max(current.width, current.height)
      val nextLongest = max(MIN_LONGEST_EDGE_PX, (currentLongest * DIMENSION_STEP_FACTOR).toInt())
      if (dimensionPass >= MAX_DIMENSION_PASSES || nextLongest >= currentLongest) {
        break
      }
      val resized = scaleToLongestEdge(current, nextLongest)
      if (ownsCurrent) current.recycle()
      current = resized
      ownsCurrent = true
    }
  } finally {
    if (ownsCurrent) current.recycle()
  }
  throw InvestigationEvidenceConversionException(
      "Could not normalize evidence within the $maxBytes byte budget after bounded quality/dimension reduction.")
}

private fun toJpegFilename(filename: String): String {
  val trimmed = filename.trim()
  if (trimmed.isBlank()) {
    return "investigation_capture.jpg"
  }
  val lastDotIndex = trimmed.lastIndexOf('.')
  return if (lastDotIndex <= 0) {
    "$trimmed.jpg"
  } else {
    "${trimmed.substring(0, lastDotIndex)}.jpg"
  }
}

internal fun liveCaptureFilename(slotIndex: Int, extension: String): String {
  return "investigation_capture_${slotIndex + 1}.$extension"
}