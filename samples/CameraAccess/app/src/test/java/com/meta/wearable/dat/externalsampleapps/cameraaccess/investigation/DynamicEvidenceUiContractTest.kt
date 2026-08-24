package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicEvidenceUiContractTest {
  @Test
  fun evidenceUiUsesDynamicCountAndDoesNotRequireFivePhotos() {
    val panel = readUiSource()

    assertTrue(panel.contains("1 photo added"))
    assertTrue(panel.contains("photos added"))
    assertTrue(panel.contains("Add another from device"))
    assertTrue(panel.contains("productState.hasCaptureCapacity"))
    assertFalse(panel.contains("Take 5 photos"))
    assertFalse(panel.contains("not captured"))
  }

  @Test
  fun phoneFallbackAppendsThroughTheSameAcceptedEvidencePath() {
    val panel = readUiSource()

    assertTrue(panel.contains("ActivityResultContracts.TakePicturePreview()"))
    assertTrue(panel.contains("viewModel.appendLiveEvidence(evidence)"))
    assertFalse(panel.contains("viewModel.setEvidence(0, evidence)"))
  }

  private fun readUiSource(): String {
    val path =
        Paths.get(
            "src", "main", "java", "com", "meta", "wearable", "dat", "externalsampleapps",
            "cameraaccess", "ui", "BackendInvestigationPanel.kt",
        )
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
