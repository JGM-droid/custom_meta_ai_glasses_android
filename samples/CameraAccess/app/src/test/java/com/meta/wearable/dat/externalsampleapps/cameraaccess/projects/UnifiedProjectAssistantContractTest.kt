/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedProjectAssistantContractTest {
  private val root = File(System.getProperty("user.dir") ?: error("user.dir is required"))

  @Test
  fun workspacePresentsOneGroundedComposerAndThreeDeterministicActions() {
    val source = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/ProjectWorkspaceScreen.kt").readText()
    assertTrue(source.contains("WHAT DO YOU NEED HELP WITH?"))
    assertTrue(source.contains("Ask Project"))
    assertTrue(source.contains("Continue where I left off"))
    assertTrue(source.contains("Add photos"))
    assertTrue(source.contains("Get ideas"))
    assertTrue(source.contains("viewModel.askProject(draftText)"))
    assertTrue(source.contains("onOpenCapture = onOpenCapture"))
    assertFalse(source.contains("Evidence capture - coming soon"))
  }

  @Test
  fun clientUsesExistingProjectScopedAskAndExploreContractsOnly() {
    val source = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectBackendClient.kt").readText()
    assertTrue(source.contains("/projects/${'$'}{normalizeId(projectId)}/ask"))
    assertTrue(source.contains("/projects/${'$'}{normalizeId(projectId)}/interactions/explore"))
    assertTrue(source.contains("/projects/${'$'}{normalizeId(projectId)}/ideas/${'$'}{normalizeId(ideaId)}/disposition"))
    assertFalse(source.contains("assistant/resolve"))
    assertFalse(source.contains("assistant/execute"))
  }

  @Test
  fun ideasRemainUnconfirmedAndSeparateFromCanonicalProjectState() {
    val source = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/ProjectWorkspaceScreen.kt").readText()
    val viewModel = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectDetailViewModel.kt").readText()
    assertTrue(source.contains("AI suggestions stay unconfirmed"))
    assertTrue(viewModel.contains("canonical Project state is unchanged"))
    assertTrue(source.contains("Add to Roadmap"))
    assertTrue(source.contains("Keep for consideration"))
    assertTrue(source.contains("Choose as preferred direction"))
    assertTrue(viewModel.contains("current Project checkpoint is unchanged"))
  }

  @Test
  fun askResultSurfacesExistingInsufficientContextAndUncertaintyFields() {
    val source = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/ProjectWorkspaceScreen.kt").readText()
    assertTrue(source.contains("answer.insufficientContext"))
    assertTrue(source.contains("answer.uncertaintyNote"))
    assertTrue(source.contains("workspace_ask_uncertainty"))
    assertTrue(source.contains("AI ANSWER — BASED ON SAVED PROJECT INFORMATION"))
    assertTrue(source.contains("Partially grounded — some information may be missing"))
    assertTrue(source.contains("Not enough saved Project information"))
    assertTrue(source.contains("answer.referenceSummaries.take(3)"))
    assertTrue(source.contains("This answer does not change your Project."))
  }

  @Test
  fun getIdeasPreservesInformationRequestWithoutSuccessClaimOrFallbackOptions() {
    val client = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectBackendClient.kt").readText()
    val viewModel = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectDetailViewModel.kt").readText()
    assertTrue(client.contains("ProjectIdeasExecutionResult.InformationRequest(prompt)"))
    assertTrue(client.contains("INFORMATION_REQUEST"))
    assertTrue(viewModel.contains("is ProjectIdeasExecutionResult.InformationRequest"))
    assertTrue(viewModel.contains("result.prompt"))
    assertTrue(viewModel.contains("preserveIdeasForInformationRequest(projectId, previousProjection, reloaded)"))
    assertFalse(viewModel.substringAfter("is ProjectIdeasExecutionResult.InformationRequest").substringBefore("}").contains("Three AI suggestions"))
  }

  @Test
  fun informationRequestKeepsPreexistingOptionsAndClarificationIsRenderedSeparately() {
    val existing =
        ProjectIdeasProjection(
            projectId = "project-a",
            options = listOf(ProjectIdeaOption("idea-1", 1, "Existing direction", null, null, false)),
            preferredIdeaId = null,
        )
    val preserved = preserveIdeasForInformationRequest("project-a", existing, reloaded = null)
    assertTrue(preserved?.options?.single()?.summary == "Existing direction")

    val viewModel = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectDetailViewModel.kt").readText()
    val screen = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/ProjectWorkspaceScreen.kt").readText()
    assertTrue(viewModel.contains("ProjectIdeasState.Ready(projection, result.prompt)"))
    assertTrue(screen.contains("state.message?.let"))
  }

  @Test
  fun getIdeasRejectsMismatchedProjectIdentity() {
    val client = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectBackendClient.kt").readText()
    val viewModel = root.resolve("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/projects/ProjectDetailViewModel.kt").readText()
    assertTrue(client.contains("response.getString(\"project_id\") != normalizedProjectId"))
    assertTrue(client.contains("projection.projectId != normalizedProjectId"))
    assertTrue(viewModel.contains("result.projection.projectId != projectId"))
    assertTrue(viewModel.contains("exc.category == \"project_mismatch\""))
    assertTrue(viewModel.contains("return@launch"))
  }
}
