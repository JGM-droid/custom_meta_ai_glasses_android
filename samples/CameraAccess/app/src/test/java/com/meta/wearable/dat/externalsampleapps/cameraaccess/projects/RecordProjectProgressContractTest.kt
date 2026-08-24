package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordProjectProgressContractTest {
  private val root = File(System.getProperty("user.dir") ?: error("user.dir is required"))

  @Test
  fun workspaceRequiresPreviewBeforeSaveAndExplainsProposalBoundary() {
    val screen = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/ProjectWorkspaceScreen.kt").readText()
    assertTrue(screen.contains("Record progress"))
    assertTrue(screen.contains("Review before saving"))
    assertTrue(screen.contains("state is ProjectProgressState.PreviewReady"))
    assertTrue(screen.contains("Suggested Project changes will be created for separate Apply or Reject review."))
    assertTrue(screen.contains("Where we left off:"))
    assertTrue(screen.contains("Blockers:"))
    assertTrue(screen.contains("Next action:"))
    assertTrue(screen.contains("→"))
    assertFalse(screen.contains("Automatically apply"))
  }

  @Test
  fun clientUsesOnlyExplicitProjectScopedPreviewAndSaveRoutes() {
    val client = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectBackendClient.kt").readText()
    assertTrue(client.contains("/projects/${'$'}{normalizeId(projectId)}/progress/preview"))
    assertTrue(client.contains("/projects/${'$'}normalizedProjectId/progress"))
    assertTrue(client.contains("expected_project_revision"))
    assertTrue(client.contains("idempotency_key"))
    assertTrue(client.contains("Backend saved progress for a different Project."))
  }

  @Test
  fun ambiguousSaveRetriesFrozenRequestAndReloadsCanonicalState() {
    val viewModel = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectDetailViewModel.kt").readText()
    assertTrue(viewModel.contains("pendingProgressRequest"))
    assertTrue(viewModel.contains("repository.saveProjectProgress(projectId, request)"))
    assertTrue(viewModel.contains("first.code == 409 && first.category == \"revision_conflict\""))
    assertTrue(viewModel.contains("Preview again before saving."))
    assertTrue(viewModel.contains("result.idempotencyKey != request.idempotencyKey"))
    assertTrue(viewModel.contains("Retry the exact frozen request with the same"))
    assertTrue(viewModel.contains("completeProgressSave(request, result)"))
    assertTrue(viewModel.contains("loadOverview()"))
    assertFalse(viewModel.contains("setActiveProject(projectId)\n        pendingProgressRequest"))
  }
}
