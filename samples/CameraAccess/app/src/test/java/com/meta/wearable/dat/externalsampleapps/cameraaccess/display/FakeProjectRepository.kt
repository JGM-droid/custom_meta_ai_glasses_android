/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectAskAnswer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectIdeasExecutionResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectIdeasProjection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectProgressPreview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectProgressRequest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectProgressSaveResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

/**
 * A local, in-memory, purely scriptable [ProjectRepository] for the Stage 2 acceptance harness -
 * never a live backend call, never a mutated real Project (matches this harness's constraints).
 * Deliberately distinct from production's own `MockProjectRepository` (see ProjectRepository.kt):
 * that one exists for Compose previews with fixed seed data; this one exists so a test can push a
 * *new* [ProjectOverview] before each `getProjectOverview()` call to simulate what canonical
 * Project state would look like after a Use/Analyze/trust decision actually landed - the harness
 * controls that scripting directly rather than this fake re-deriving it.
 *
 * [ProjectContinuityHudController] only ever calls [getProjectOverview] (see
 * ProjectContinuityHudContractTest's controllerIsReadOnlyAndDoesNotOwnDeviceSession) - every other
 * method here is unreachable from it and fails loudly if ever called, rather than silently
 * returning a value nothing validated.
 */
internal class FakeProjectRepository(initialOverview: ProjectOverview) : ProjectRepository {
  var currentOverview: ProjectOverview = initialOverview

  /** When set, the next [getProjectOverview] call throws this instead of returning. */
  var nextLoadFailure: Exception? = null

  var getProjectOverviewCallCount = 0
    private set

  override suspend fun getProjectOverview(projectId: String): ProjectOverview {
    getProjectOverviewCallCount++
    nextLoadFailure?.let {
      nextLoadFailure = null
      throw it
    }
    return currentOverview
  }

  override suspend fun listProjects(): List<ProjectSummary> =
      error("Not used by ProjectContinuityHudController")

  override suspend fun createProject(request: NewProjectRequest): ProjectSummary =
      error("Not used by ProjectContinuityHudController")

  override suspend fun getActiveProject(): ProjectSummary? =
      error("Not used by ProjectContinuityHudController")

  override suspend fun setActiveProject(projectId: String): ProjectSummary =
      error("Not used by ProjectContinuityHudController")

  override suspend fun clearActiveProject(): Unit = error("Not used by ProjectContinuityHudController")

  override suspend fun askProject(projectId: String, question: String): ProjectAskAnswer =
      error("Not used by ProjectContinuityHudController")

  override suspend fun previewProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressPreview =
      error("Not used by ProjectContinuityHudController")

  override suspend fun saveProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressSaveResult =
      error("Not used by ProjectContinuityHudController")

  override suspend fun getProjectIdeas(projectId: String): ProjectIdeasProjection =
      error("Not used by ProjectContinuityHudController")

  override suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String): ProjectIdeasExecutionResult =
      error("Not used by ProjectContinuityHudController")

  override suspend fun setProjectIdeaDisposition(
      projectId: String,
      ideaId: String,
      disposition: String,
      idempotencyKey: String,
  ): ProjectIdeasProjection = error("Not used by ProjectContinuityHudController")

  override suspend fun promoteProjectIdea(projectId: String, ideaId: String): Unit =
      error("Not used by ProjectContinuityHudController")

  override suspend fun applyCheckpointProposal(projectId: String, proposalId: String): Unit =
      error("Not used by ProjectContinuityHudController")

  override suspend fun rejectCheckpointProposal(projectId: String, proposalId: String): Unit =
      error("Not used by ProjectContinuityHudController")
}
