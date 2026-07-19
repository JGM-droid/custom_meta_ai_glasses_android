package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

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

internal fun liveCaptureFilename(slotIndex: Int, extension: String): String {
  return "investigation_capture_${slotIndex + 1}.$extension"
}