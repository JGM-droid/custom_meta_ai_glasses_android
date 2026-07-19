package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
}