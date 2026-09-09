package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private const val MAX_EVIDENCE_BYTES_FOR_TEST = 1_500_000
private const val MAX_LONGEST_EDGE_FOR_TEST = 2048

@RunWith(AndroidJUnit4::class)
class InvestigationCaptureNormalizerInstrumentedTest {
  @Test
  fun bitmapEvidenceConvertsToPngAndKeepsSource() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    bitmap.setPixel(0, 0, Color.RED)
    bitmap.setPixel(1, 0, Color.GREEN)
    bitmap.setPixel(0, 1, Color.BLUE)
    bitmap.setPixel(1, 1, Color.WHITE)

    val evidence =
        bitmapToInvestigationEvidence(
            bitmap = bitmap,
            slotIndex = 0,
            filename = liveCaptureFilename(slotIndex = 0, extension = "png"),
            source = InvestigationEvidenceSource.LIVE_GLASSES,
        )

    assertEquals("image/png", evidence.mimeType)
    assertEquals(InvestigationEvidenceSource.LIVE_GLASSES, evidence.source)
    assertEquals("investigation_capture_1.png", evidence.filename)
    assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), evidence.bytes.copyOfRange(0, 8))
  }

  // --- Phone-photo-ingestion fix: normalizeImageEvidenceForBackend ---

  private fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
    val output = ByteArrayOutputStream()
    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
    return output.toByteArray()
  }

  /** High-entropy per-pixel noise defeats JPEG's DCT compression far more than any real
   * photograph would, giving a deterministic, device-independent way to force "still oversized
   * after normal compression" without depending on any specific encoder's behavior on real
   * photographic content. */
  private fun noiseBitmap(width: Int, height: Int, seed: Long): Bitmap {
    val random = Random(seed)
    val pixels = IntArray(width * height) { random.nextInt() or -0x1000000 } // opaque alpha
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  /** A smoothly-varying (gradient) bitmap - used by the orientation tests below, which rely on
   * comparing specific sampled pixels and need a predictable, low-frequency image. */
  private fun gradientBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val r = (255 * x / width)
        val g = (255 * y / height)
        val b = 128
        pixels[y * width + x] = Color.rgb(r, g, b)
      }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
  }

  /** A gradient with modest per-pixel jitter layered on top - unlike a flat gradient (which JPEG
   * compresses extremely well, unrealistically for a real photo) this keeps enough per-pixel
   * high-frequency detail to compress more like an ordinary detailed real photograph, while still
   * being far less adversarial than pure noise. Used only for the "realistic oversized photo"
   * case - the orientation tests use the plain gradient above, which needs predictable sampled
   * pixels. */
  private fun photoLikeBitmap(width: Int, height: Int, seed: Long): Bitmap {
    val random = Random(seed)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val jitter = random.nextInt(-28, 29)
        val r = (255 * x / width + jitter).coerceIn(0, 255)
        val g = (255 * y / height + jitter).coerceIn(0, 255)
        val b = (128 + jitter).coerceIn(0, 255)
        pixels[y * width + x] = Color.rgb(r, g, b)
      }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
  }

  /** Writes JPEG bytes to a temp file, stamps an EXIF orientation tag via ExifInterface's
   * file-based read/write path (writing EXIF into an in-memory byte array isn't supported by the
   * library), then reads the stamped bytes back. */
  private fun jpegWithOrientation(bitmap: Bitmap, orientation: Int): ByteArray {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("normalizer_test_", ".jpg", context.cacheDir)
    try {
      file.writeBytes(jpegBytes(bitmap, quality = 100))
      ExifInterface(file.absolutePath).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        saveAttributes()
      }
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  private fun evidenceInput(bytes: ByteArray, mimeType: String, filename: String = "photo.jpg") =
      InvestigationEvidenceInput(
          slotIndex = 0, filename = filename, mimeType = mimeType, bytes = bytes,
          source = InvestigationEvidenceSource.LOCAL_PICKER,
      )

  // A. realistic oversized JPEG substantially above 2MB.
  @Test
  fun oversizedRealisticPhotoIsNormalizedWithinBudget() {
    val bitmap = photoLikeBitmap(4000, 3000, seed = 17)
    val original = jpegBytes(bitmap, quality = 95)
    assertTrue("fixture must actually exceed 2MB", original.size > 2_000_000)

    val normalized = normalizeImageEvidenceForBackend(evidenceInput(original, "image/jpeg"))

    assertEquals("image/jpeg", normalized.mimeType)
    assertTrue(normalized.bytes.size <= MAX_EVIDENCE_BYTES_FOR_TEST)
    val decoded = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
    assertTrue(maxOf(decoded.width, decoded.height) <= MAX_LONGEST_EDGE_FOR_TEST)
  }

  // B. highly detailed/noisy oversized JPEG - either satisfies the budget or fails explicitly.
  @Test
  fun highlyDetailedOversizedPhotoEitherSatisfiesBudgetOrFailsExplicitly() {
    val bitmap = noiseBitmap(3000, 3000, seed = 42)
    val original = jpegBytes(bitmap, quality = 95)
    assertTrue("fixture must actually exceed 2MB", original.size > 2_000_000)

    try {
      val normalized = normalizeImageEvidenceForBackend(evidenceInput(original, "image/jpeg"))
      assertTrue(normalized.bytes.size <= MAX_EVIDENCE_BYTES_FOR_TEST)
    } catch (expected: InvestigationEvidenceConversionException) {
      // An explicit, typed local failure is an acceptable outcome for adversarial content -
      // never a silent oversized upload.
    }
  }

  // C. already-small upright JPEG - byte-identical pass-through.
  @Test
  fun alreadySmallUprightJpegPassesThroughByteIdentical() {
    val bitmap = gradientBitmap(200, 150)
    val original = jpegBytes(bitmap, quality = 90)

    val normalized = normalizeImageEvidenceForBackend(evidenceInput(original, "image/jpeg"))

    assertArrayEquals(original, normalized.bytes)
    assertEquals("image/jpeg", normalized.mimeType)
  }

  // D. JPEG carrying EXIF 90/180/270 orientation - output pixels upright, correct dimensions,
  // orientation no longer depends on EXIF metadata.
  @Test
  fun exifRotation90ProducesUprightSwappedDimensions() {
    val bitmap = gradientBitmap(400, 200) // wider than tall
    val rotated = jpegWithOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_90)

    val normalized = normalizeImageEvidenceForBackend(evidenceInput(rotated, "image/jpeg"))

    val decoded = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
    // A 90-degree correction swaps the long/short axis.
    assertTrue(decoded.height > decoded.width)
    // The re-encoded JPEG carries no residual orientation tag - upright-ness is now baked into
    // the pixels themselves, not dependent on metadata a downstream consumer might ignore.
    val outputOrientation = readOrientation(normalized.bytes)
    assertTrue(
        outputOrientation == ExifInterface.ORIENTATION_NORMAL ||
            outputOrientation == ExifInterface.ORIENTATION_UNDEFINED,
    )
  }

  @Test
  fun exifRotation270ProducesUprightSwappedDimensions() {
    val bitmap = gradientBitmap(400, 200)
    val rotated = jpegWithOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_270)

    val normalized = normalizeImageEvidenceForBackend(evidenceInput(rotated, "image/jpeg"))

    val decoded = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
    assertTrue(decoded.height > decoded.width)
  }

  @Test
  fun exifRotation180PreservesDimensionsButBakesInPixelFlip() {
    val bitmap = gradientBitmap(400, 200)
    val rotated = jpegWithOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_180)

    val normalized = normalizeImageEvidenceForBackend(evidenceInput(rotated, "image/jpeg"))

    val decoded = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
    assertEquals(400, decoded.width)
    assertEquals(200, decoded.height)
    // A 180 correction changed pixel content even though dimensions stayed the same: the
    // gradient's top-left corner (originally near-black) becomes near the bottom-right instead.
    val topLeft = decoded.getPixel(2, 2)
    val bottomRight = decoded.getPixel(decoded.width - 3, decoded.height - 3)
    assertTrue(Color.red(bottomRight) < Color.red(topLeft) || Color.green(bottomRight) < Color.green(topLeft))
  }

  // E. HEIC/HEIF existing behavior remains supported (still forced through conversion to JPEG,
  // regardless of size) - using PNG-encoded bytes labeled as HEIC so this exercises
  // normalizeImageEvidenceForBackend's own mime-type branch logic without depending on this
  // device's HEIC encoder support.
  @Test
  fun heicLabeledInputIsAlwaysConvertedToJpeg() {
    val bitmap = gradientBitmap(100, 100)
    val output = ByteArrayOutputStream()
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    val pngBytesLabeledHeic = output.toByteArray()

    val normalized = normalizeImageEvidenceForBackend(
        evidenceInput(pngBytesLabeledHeic, "image/heic", filename = "capture.heic"))

    assertEquals("image/jpeg", normalized.mimeType)
    assertEquals("capture.jpg", normalized.filename)
    assertTrue(normalized.bytes.size <= MAX_EVIDENCE_BYTES_FOR_TEST)
  }

  // F. PNG/glasses-style input already under constraints does not regress.
  @Test
  fun smallGlassesStylePngPassesThroughByteIdentical() {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val evidence = bitmapToInvestigationEvidence(
        bitmap = bitmap, slotIndex = 0,
        filename = liveCaptureFilename(0, "png"), source = InvestigationEvidenceSource.LIVE_GLASSES,
    )

    val normalized = normalizeImageEvidenceForBackend(evidence)

    assertArrayEquals(evidence.bytes, normalized.bytes)
    assertEquals("image/png", normalized.mimeType)
  }

  // G. aspect ratio preserved during resize.
  @Test
  fun resizePreservesAspectRatio() {
    val bitmap = noiseBitmap(4000, 2000, seed = 7) // 2:1
    val scaled = scaleToLongestEdge(bitmap, 1000)

    assertEquals(1000, maxOf(scaled.width, scaled.height))
    val originalRatio = 4000.0 / 2000.0
    val scaledRatio = scaled.width.toDouble() / scaled.height.toDouble()
    assertTrue(Math.abs(originalRatio - scaledRatio) < 0.02)
  }

  // H. no image is upscaled.
  @Test
  fun smallBitmapIsNeverUpscaled() {
    val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)

    val result = scaleToLongestEdge(bitmap, 2048)

    assertEquals(100, result.width)
    assertEquals(50, result.height)
  }

  // I. output never exceeds the client Evidence budget when normalization reports success -
  // covered by the explicit budget assertions in A/B/E above; asserted again directly here
  // against the bounded encoder itself.
  @Test
  fun encodeWithinBudgetNeverExceedsTheRequestedBudget() {
    val bitmap = noiseBitmap(2500, 2500, seed = 99)
    val bytes = try {
      encodeWithinBudget(bitmap, maxLongestEdge = 2048, maxBytes = MAX_EVIDENCE_BYTES_FOR_TEST)
    } catch (expected: InvestigationEvidenceConversionException) {
      return // an explicit local failure also satisfies "never exceeds the budget it reports success for"
    }
    assertTrue(bytes.size <= MAX_EVIDENCE_BYTES_FOR_TEST)
  }

  // J. failure occurs locally, before any upload, when constraints truly cannot be met -
  // forced deterministically via an impossibly small budget rather than adversarial image
  // content, so this test is not flaky across devices/encoders.
  @Test
  fun impossibleBudgetFailsLocallyWithATypedException() {
    val bitmap = gradientBitmap(200, 200)
    try {
      encodeWithinBudget(bitmap, maxLongestEdge = 2048, maxBytes = 100)
      fail("Expected InvestigationEvidenceConversionException for an unsatisfiable byte budget.")
    } catch (expected: InvestigationEvidenceConversionException) {
      // expected - a clear, typed, local failure; no network call was ever attempted.
    }
  }
}