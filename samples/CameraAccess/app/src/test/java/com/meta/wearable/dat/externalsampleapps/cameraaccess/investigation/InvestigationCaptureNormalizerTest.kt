package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InvestigationCaptureNormalizerTest {
  @Test
  fun heicEvidencePreservesBytesAndSource() {
    val bytes = byteArrayOf(0x01, 0x23, 0x45, 0x67)

    val evidence =
        heicBytesToInvestigationEvidence(
            heicBytes = bytes,
            slotIndex = 0,
            filename = "investigation_capture_1.heic",
            source = InvestigationEvidenceSource.LIVE_GLASSES,
        )

    assertEquals(0, evidence.slotIndex)
    assertEquals("investigation_capture_1.heic", evidence.filename)
    assertEquals("image/heic", evidence.mimeType)
    assertEquals(InvestigationEvidenceSource.LIVE_GLASSES, evidence.source)
    assertArrayEquals(bytes, evidence.bytes)
  }

  @Test
  fun sourceLabelsRemainDistinguishable() {
    assertEquals("Live glasses", InvestigationEvidenceSource.LIVE_GLASSES.displayLabel)
    assertEquals("Mock device", InvestigationEvidenceSource.MOCK_DEVICE.displayLabel)
    assertEquals("Local picker", InvestigationEvidenceSource.LOCAL_PICKER.displayLabel)
  }
}