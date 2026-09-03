package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedInvestigationProjectDetailContractTest {
  private val root = File("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess")

  @Test
  fun projectDetailShowsSavedInferenceEvidenceTrustAndPendingApproval() {
    val screen = File(root, "ui/ProjectDetailScreen.kt").readText()
    assertTrue(screen.contains("Investigation saved"))
    assertTrue(screen.contains("AI suggestion — unconfirmed"))
    assertTrue(screen.contains("Retained Investigation evidence"))
    assertTrue(screen.contains("Your assessment:"))
    assertTrue(screen.contains("SUGGESTED PROJECT CHANGE — REVIEW REQUIRED"))
    assertTrue(screen.contains("proposals.forEachIndexed"))
    assertTrue(screen.contains("OF \${proposals.size}"))
    assertTrue(screen.contains("proposal.proposalId.take(8)"))
    assertTrue(screen.contains("proposal.reason"))
    assertTrue(screen.contains("proposal.proposedFields"))
    assertTrue(screen.contains("Apply to Project"))
    assertTrue(screen.contains("Reject change"))
  }

  @Test
  fun reviewAndApprovalRemainProjectScopedAndCanonical() {
    val client = File(root, "projects/ProjectBackendClient.kt").readText()
    val viewModel = File(root, "projects/ProjectDetailViewModel.kt").readText()
    assertTrue(client.contains("/projects/\$normalizedProjectId/investigation-sessions"))
    assertTrue(client.contains("/projects/\${normalizeId(projectId)}/checkpoint-proposals/\${normalizeId(proposalId)}/apply"))
    assertTrue(client.contains("/projects/\${normalizeId(projectId)}/checkpoint-proposals/\${normalizeId(proposalId)}/reject"))
    assertTrue(viewModel.contains("loadOverview()"))
    assertTrue(client.contains("/projects/\$normalizedProjectId/checkpoint-proposals"))
    assertTrue(client.contains("filter { it.status == \"pending\" }"))
    assertTrue(viewModel.contains("A lost response may follow a successful backend mutation"))
    assertFalse(viewModel.contains("setActiveProject(projectId, proposalId"))
  }

  @Test
  fun allPendingProposalsAndMoreEvidenceContinuationAreRecoverable() {
    val models = File(root, "projects/ProjectModels.kt").readText()
    val screen = File(root, "ui/ProjectDetailScreen.kt").readText()
    val appRoot = File(root, "ui/AppRoot.kt").readText()
    val investigationViewModel = File(root, "investigation/InvestigationSessionDebugViewModel.kt").readText()
    assertTrue(models.contains("val pendingProposals: List<CheckpointProposalReview>"))
    assertTrue(models.contains("val followUpSessionId: String?"))
    assertTrue(screen.contains("proposals.forEach"))
    assertTrue(screen.contains("Add more evidence"))
    assertTrue(appRoot.contains("rememberSaveable"))
    assertTrue(appRoot.contains("continuationSessionId"))
    assertTrue(investigationViewModel.contains("SavedStateHandle"))
    assertTrue(investigationViewModel.contains("investigation_session_id"))
    assertTrue(investigationViewModel.contains("investigation_image_uri_"))
  }

  @Test
  fun noAndroidPersistenceOrFilesystemEvidencePathIsIntroduced() {
    val models = File(root, "projects/ProjectModels.kt").readText()
    val client = File(root, "projects/ProjectBackendClient.kt").readText()
    assertFalse(models.contains("storageRef"))
    assertFalse(client.contains("storage_ref"))
    assertFalse(client.contains("RoomDatabase"))
  }

  @Test
  fun primaryActionAndGlobalCaptureStayProjectScoped() {
    val detail = File(root, "ui/ProjectDetailScreen.kt").readText()
    val home = File(root, "ui/ProjectsHomeScreen.kt").readText()
    val appRoot = File(root, "ui/AppRoot.kt").readText()
    assertTrue(detail.contains("ProjectPrimaryAction.ADD_EVIDENCE"))
    assertTrue(detail.contains("ProjectPrimaryAction.REVIEW_CHANGES"))
    assertTrue(detail.contains("ProjectPrimaryAction.USE_GLASSES"))
    assertTrue(detail.indexOf("PendingProposalsSection(") < detail.indexOf("text = \"PROJECT HISTORY\""))
    assertTrue(home.contains("activeProject?.let(onOpenCapture)"))
    assertTrue(home.contains("Choose a Project to use glasses"))
    assertTrue(appRoot.contains("TopLevelScreen.Capture(sourceProject = project)"))
    assertFalse(appRoot.contains("onOpenCapture = { topLevelScreen = TopLevelScreen.Capture() }"))
  }

  /**
   * Glasses<->phone trust UX simplification: the phone panel's three trust-action labels read as
   * plain human language ("Looks right"/"Add more info"/"Not quite"), not engineering shorthand
   * ("working hypothesis"/"record your assessment"/"does not update the Project"/"apply to
   * project") - but only the labels/copy changed. Each still routes to its unchanged
   * BackendTrustDecision, proving persistence/trust semantics are untouched underneath.
   */
  @Test
  fun humanLabelsPreserveUnderlyingTrustSemantics() {
    val panel = File(root, "ui/BackendInvestigationPanel.kt").readText()

    assertTrue(panel.contains("Looks right"))
    assertTrue(panel.contains("BackendTrustDecision.CONTINUE"))
    assertTrue(panel.contains("Not quite"))
    assertTrue(panel.contains("BackendTrustDecision.DISAGREE"))
    assertTrue(panel.contains("Add more info"))
    assertTrue(panel.contains("BackendTrustDecision.MORE_EVIDENCE"))

    // The old engineering-facing copy is gone from the normal flow.
    assertFalse(panel.contains("working hypothesis"))
    assertFalse(panel.contains("record your assessment"))
    assertFalse(panel.contains("does not update the Project"))
    assertFalse(panel.contains("Apply to Project"))
    assertFalse(panel.contains("I disagree"))
    assertFalse(panel.contains("Add more evidence"))
  }
}
