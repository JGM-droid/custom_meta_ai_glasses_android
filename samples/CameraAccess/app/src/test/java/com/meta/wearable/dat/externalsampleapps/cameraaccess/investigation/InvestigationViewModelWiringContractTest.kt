package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationViewModelWiringContractTest {
  @Test
  fun streamScreenPassesSharedInvestigationViewModelToPanel() {
    val streamScreenPath =
      Paths.get(
        "src",
        "main",
        "java",
        "com",
        "meta",
        "wearable",
        "dat",
        "externalsampleapps",
        "cameraaccess",
        "ui",
        "StreamScreen.kt",
      )
    val streamScreen = String(Files.readAllBytes(streamScreenPath), StandardCharsets.UTF_8)

    assertTrue(streamScreen.contains("viewModel = investigationViewModel"))
  }

  @Test
  fun panelDoesNotCreateImplicitInvestigationViewModel() {
    val panelPath =
      Paths.get(
        "src",
        "main",
        "java",
        "com",
        "meta",
        "wearable",
        "dat",
        "externalsampleapps",
        "cameraaccess",
        "ui",
        "BackendInvestigationPanel.kt",
      )
    val panel = String(Files.readAllBytes(panelPath), StandardCharsets.UTF_8)

    assertTrue(panel.contains("viewModel: InvestigationSessionDebugViewModel"))
    assertFalse(panel.contains("viewModel: InvestigationSessionDebugViewModel ="))
  }
}
