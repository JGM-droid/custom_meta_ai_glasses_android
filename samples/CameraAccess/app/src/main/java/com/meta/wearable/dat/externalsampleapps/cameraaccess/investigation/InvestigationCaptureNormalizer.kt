package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

private const val BACKEND_JPEG_QUALITY = 92

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

internal fun normalizeImageEvidenceForBackend(
    evidence: InvestigationEvidenceInput,
    jpegQuality: Int = BACKEND_JPEG_QUALITY,
): InvestigationEvidenceInput {
  val mimeType = evidence.mimeType.trim().lowercase()
  if (mimeType == "image/jpeg" || mimeType == "image/png") {
    return evidence
  }
  if (mimeType != "image/heic" && mimeType != "image/heif") {
    throw InvestigationEvidenceConversionException(
        "Unsupported investigation image mime type: ${evidence.mimeType}.",
    )
  }

  val bitmap = BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size)
      ?: throw InvestigationEvidenceConversionException(
          "Failed to decode HEIC evidence ${evidence.filename} for backend upload.",
      )

  val output = ByteArrayOutputStream()
  val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
  bitmap.recycle()
  if (!compressed) {
    throw InvestigationEvidenceConversionException(
        "Failed to convert HEIC evidence ${evidence.filename} to JPEG for backend upload.",
    )
  }

  return evidence.copy(
      filename = toJpegFilename(evidence.filename),
      mimeType = "image/jpeg",
      bytes = output.toByteArray(),
  )
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