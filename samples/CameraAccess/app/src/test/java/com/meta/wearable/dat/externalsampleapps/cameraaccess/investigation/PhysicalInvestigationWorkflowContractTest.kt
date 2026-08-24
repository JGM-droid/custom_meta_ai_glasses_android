package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalInvestigationWorkflowContractTest {
  @Test
  fun captureWaitsForStreamingAndInvestigationIsPrimaryPhotoAction() {
    val stream = readUi("StreamScreen.kt")
    val photo = readUi("SharePhotoDialog.kt")

    assertTrue(stream.contains("enabled = streamUiState.streamState == StreamState.STREAMING"))
    assertTrue(photo.contains("Text(\"Use for Investigation\")"))
    assertTrue(photo.indexOf("Use for Investigation") < photo.indexOf("stringResource(R.string.share)"))
  }

  @Test
  fun explicitProjectIdentityAndSameProjectReturnRemainVisible() {
    val appRoot = readUi("AppRoot.kt")
    val stream = readUi("StreamScreen.kt")
    val panel = readUi("BackendInvestigationPanel.kt")

    assertTrue(appRoot.contains("sourceProjectId = screen.sourceProject?.projectId"))
    assertTrue(appRoot.contains("sourceProjectName = screen.sourceProject?.name"))
    assertTrue(stream.contains("Working on ${'$'}projectName"))
    assertTrue(panel.contains("Project: ${'$'}projectName"))
    assertTrue(panel.contains("Return to ${'$'}{sourceProjectName ?: \"Project\"}"))
    assertFalse(appRoot.contains("viewModel.setActiveProject("))
    assertFalse(appRoot.contains("viewModel::setActiveProject"))
  }

  @Test
  fun trustControlsAreResultGatedAndUseExistingViewModelContract() {
    val panel = readUi("BackendInvestigationPanel.kt")
    val viewModel = readInvestigation("InvestigationSessionDebugViewModel.kt")

    assertTrue(panel.contains("productState.phase == InvestigationProductPhase.COMPLETED"))
    assertTrue(panel.contains("if (uiState.trustControlsAvailable)"))
    assertTrue(panel.contains("BackendTrustDecision.CONTINUE"))
    assertTrue(panel.contains("BackendTrustDecision.DISAGREE"))
    assertTrue(panel.contains("BackendTrustDecision.MORE_EVIDENCE"))
    assertTrue(panel.contains("AI SUGGESTION — UNCONFIRMED"))
    assertTrue(viewModel.contains("sourceProjectId ?: return"))
    assertTrue(viewModel.contains("continuationSessionId = followUpId"))
  }

  @Test
  fun dismissalRetainsDraftAndOffersProminentResume() {
    val stream = readUi("StreamScreen.kt")
    assertTrue(stream.contains("onDismissRequest = { streamViewModel.hideInvestigationPanel() }"))
    assertTrue(stream.contains("Text(\"Resume ${'$'}investigationReopenLabel\")"))
  }

  private fun readUi(file: String): String =
      readSource("ui", file)

  private fun readInvestigation(file: String): String =
      readSource("investigation", file)

  private fun readSource(folder: String, file: String): String {
    val path =
        Paths.get(
            "src", "main", "java", "com", "meta", "wearable", "dat", "externalsampleapps",
            "cameraaccess", folder, file,
        )
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
