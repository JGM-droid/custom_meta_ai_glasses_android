package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationPanelReopenContractTest {
  @Test
  fun sheetDismissInStreamScreenOnlyHidesPanelVisibility() {
    val streamScreen =
        readSource(
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

    assertTrue(streamScreen.contains("onDismissRequest = { streamViewModel.hideInvestigationPanel() }"))
  }

  @Test
  fun hideInvestigationPanelDoesNotClearCapturedEvidenceState() {
    val streamViewModel =
        readSource(
            "src",
            "main",
            "java",
            "com",
            "meta",
            "wearable",
            "dat",
            "externalsampleapps",
            "cameraaccess",
            "stream",
            "StreamViewModel.kt",
        )

    val start = streamViewModel.indexOf("fun hideInvestigationPanel()")
    val end = streamViewModel.indexOf("fun consumeCapturedInvestigationEvidence()")
    assertTrue(start >= 0)
    assertTrue(end > start)

    val hideBlock = streamViewModel.substring(start, end)
    assertTrue(hideBlock.contains("isInvestigationPanelVisible = false"))
    assertFalse(hideBlock.contains("capturedInvestigationEvidence"))
  }

  @Test
  fun activeInvestigationShowsReopenAffordanceAndTapReopensPanel() {
    val streamScreen =
        readSource(
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

    assertTrue(streamScreen.contains("if (showInvestigationReopenAffordance)"))
    assertTrue(streamScreen.contains("!streamUiState.isInvestigationPanelVisible"))
    assertTrue(streamScreen.contains("hasActiveInvestigation(investigationUiState)"))
    assertTrue(streamScreen.contains("onClick = { streamViewModel.showInvestigationPanel() }"))
    assertTrue(streamScreen.contains("Text(\"Resume ${'$'}investigationReopenLabel\")"))
  }

  @Test
  fun streamScreenKeepsSharedInvestigationViewModelInstance() {
    val streamScreen =
        readSource(
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

    assertTrue(streamScreen.contains("investigationViewModel: InvestigationSessionDebugViewModel ="))
    assertTrue(streamScreen.contains("viewModel = investigationViewModel"))
  }

  private fun readSource(vararg path: String): String {
    val fullPath = Paths.get(path.first(), *path.drop(1).toTypedArray())
    return String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8)
  }
}
