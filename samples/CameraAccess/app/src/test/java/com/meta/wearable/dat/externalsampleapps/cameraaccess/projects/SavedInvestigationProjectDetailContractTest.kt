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
    assertTrue(screen.contains("Your Investigation was saved."))
    assertTrue(screen.contains("AI inference — unconfirmed"))
    assertTrue(screen.contains("Retained Investigation evidence"))
    assertTrue(screen.contains("Decision:"))
    assertTrue(screen.contains("PENDING PROJECT UPDATE"))
    assertTrue(screen.contains("Apply update"))
    assertTrue(screen.contains("Reject"))
  }

  @Test
  fun reviewAndApprovalRemainProjectScopedAndCanonical() {
    val client = File(root, "projects/ProjectBackendClient.kt").readText()
    val viewModel = File(root, "projects/ProjectDetailViewModel.kt").readText()
    assertTrue(client.contains("/projects/\$normalizedProjectId/investigation-sessions"))
    assertTrue(client.contains("/projects/\${normalizeId(projectId)}/checkpoint-proposals/\${normalizeId(proposalId)}/apply"))
    assertTrue(client.contains("/projects/\${normalizeId(projectId)}/checkpoint-proposals/\${normalizeId(proposalId)}/reject"))
    assertTrue(viewModel.contains("loadOverview()"))
    assertFalse(viewModel.contains("setActiveProject(projectId, proposalId"))
  }

  @Test
  fun noAndroidPersistenceOrFilesystemEvidencePathIsIntroduced() {
    val models = File(root, "projects/ProjectModels.kt").readText()
    val client = File(root, "projects/ProjectBackendClient.kt").readText()
    assertFalse(models.contains("storageRef"))
    assertFalse(client.contains("storage_ref"))
    assertFalse(client.contains("RoomDatabase"))
  }
}
