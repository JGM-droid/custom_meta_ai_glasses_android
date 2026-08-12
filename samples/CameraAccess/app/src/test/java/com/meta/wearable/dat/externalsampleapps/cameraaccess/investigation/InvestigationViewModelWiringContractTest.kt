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
    assertTrue(streamScreen.contains("onCaptureAnotherView ="))
    assertTrue(streamScreen.contains("onPrefillApplied ="))
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
    assertTrue(panel.contains("onCaptureAnotherView: (() -> Unit)? = null"))
    assertTrue(panel.contains("onPrefillApplied: (() -> Unit)? = null"))
  }

  @Test
  fun typedAndSpeechInputsBothUseSingleExplanationSetter() {
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

    assertTrue(panel.contains("onValueChange = viewModel::setExplanationText"))
    assertTrue(panel.contains("viewModel.setExplanationText(transcript)"))
    assertFalse(panel.contains("voiceExplanation"))
    assertFalse(panel.contains("speechExplanation"))
    assertFalse(panel.contains("audioExplanation"))
  }

  @Test
  fun investigationUiStateKeepsSingleExplanationField() {
    val viewModelPath =
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
        "investigation",
        "InvestigationSessionDebugViewModel.kt",
      )
    val source = String(Files.readAllBytes(viewModelPath), StandardCharsets.UTF_8)

    assertTrue(source.contains("val explanationText: String = \"\""))
    assertFalse(source.contains("voiceExplanation"))
    assertFalse(source.contains("speechExplanation"))
    assertFalse(source.contains("audioExplanation"))
  }
}
