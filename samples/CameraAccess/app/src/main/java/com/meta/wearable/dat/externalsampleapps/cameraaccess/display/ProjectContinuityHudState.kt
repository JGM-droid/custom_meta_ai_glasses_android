/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview

internal enum class ProjectHudDestination {
  OVERVIEW,
  DETAILS,
}

internal enum class ProjectHudPhoneDestination {
  PROJECT_DETAIL,
  PROJECT_REVIEW,
}

internal data class ProjectHudPhoneHandoff(
    val projectId: String,
    val destination: ProjectHudPhoneDestination,
)

internal data class ProjectHudContent(
    val projectId: String,
    val projectName: String,
    val whereWeLeftOff: String?,
    val nextAction: String?,
    val evidenceCount: Int,
    val latestGuidance: String?,
    val attentionSummary: String?,
) {
  val isEmpty: Boolean
    get() =
        whereWeLeftOff == null &&
            nextAction == null &&
            evidenceCount == 0 &&
            latestGuidance == null &&
            attentionSummary == null

  val hasAdditionalDetails: Boolean
    get() = evidenceCount > 0 || latestGuidance != null
}

internal sealed interface ProjectHudUiState {
  val projectId: String
  val projectName: String

  data class Loading(
      override val projectId: String,
      override val projectName: String,
  ) : ProjectHudUiState

  data class Ready(
      val content: ProjectHudContent,
      val destination: ProjectHudDestination = ProjectHudDestination.OVERVIEW,
  ) : ProjectHudUiState {
    override val projectId: String = content.projectId
    override val projectName: String = content.projectName
  }

  data class Stale(
      val content: ProjectHudContent,
      val message: String,
  ) : ProjectHudUiState {
    override val projectId: String = content.projectId
    override val projectName: String = content.projectName
  }

  data class Disconnected(
      override val projectId: String,
      override val projectName: String,
  ) : ProjectHudUiState

  data class Error(
      override val projectId: String,
      override val projectName: String,
      val message: String,
  ) : ProjectHudUiState
}

internal data class ProjectHudLoadRequest(
    val projectId: String,
    val projectName: String,
    val token: Long,
)

/**
 * Pure state machine for the read-only HUD. It owns no Project persistence and performs no
 * network or DAT calls, which makes identity/race/callback behavior deterministic to test.
 */
internal class ProjectContinuityHudStateMachine {
  var uiState: ProjectHudUiState? = null
    private set

  var renderGeneration: Long = 0
    private set

  private var selectedProjectId: String? = null
  private var selectedProjectName: String? = null
  private var requestToken: Long = 0
  private var consumedActions = mutableSetOf<String>()
  private var lastReadyContent: ProjectHudContent? = null

  fun selectProject(projectId: String, projectName: String): ProjectHudLoadRequest {
    require(projectId.isNotBlank()) { "The HUD requires an explicit project_id." }
    if (selectedProjectId != projectId) lastReadyContent = null
    selectedProjectId = projectId
    selectedProjectName = projectName
    uiState = ProjectHudUiState.Loading(projectId, projectName)
    advanceRender()
    return nextRequest(projectId, projectName)
  }

  fun refresh(reconnecting: Boolean = false): ProjectHudLoadRequest? {
    val projectId = selectedProjectId ?: return null
    val projectName = selectedProjectName ?: return null
    val retainedContent = (uiState as? ProjectHudUiState.Ready)?.content ?: lastReadyContent
    if (retainedContent != null) {
      uiState =
          ProjectHudUiState.Stale(
              retainedContent,
              if (reconnecting) "Reconnecting — checking current Project state"
              else "Refreshing current Project state",
          )
    } else {
      uiState = ProjectHudUiState.Loading(projectId, projectName)
    }
    advanceRender()
    return nextRequest(projectId, projectName)
  }

  fun accept(request: ProjectHudLoadRequest, overview: ProjectOverview): Boolean {
    if (!isCurrent(request) || overview.project.projectId != request.projectId) return false
    val content = mapOverview(overview, request.projectName)
    lastReadyContent = content
    uiState = ProjectHudUiState.Ready(content)
    advanceRender()
    return true
  }

  fun fail(request: ProjectHudLoadRequest, message: String): Boolean {
    if (!isCurrent(request)) return false
    val stale = uiState as? ProjectHudUiState.Stale
    uiState =
        stale?.copy(message = "Refresh failed — showing the last loaded Project state")
            ?: ProjectHudUiState.Error(
                request.projectId,
                request.projectName,
                message.ifBlank { "Project state is unavailable." },
            )
    advanceRender()
    return true
  }

  fun disconnected() {
    val projectId = selectedProjectId ?: return
    val projectName = selectedProjectName ?: return
    uiState = ProjectHudUiState.Disconnected(projectId, projectName)
    advanceRender()
  }

  fun showDetails(generation: Long): Boolean {
    if (!acceptAction(generation, "details")) return false
    val ready = uiState as? ProjectHudUiState.Ready ?: return false
    uiState = ready.copy(destination = ProjectHudDestination.DETAILS)
    advanceRender()
    return true
  }

  fun showOverview(generation: Long): Boolean {
    if (!acceptAction(generation, "back")) return false
    val ready = uiState as? ProjectHudUiState.Ready ?: return false
    uiState = ready.copy(destination = ProjectHudDestination.OVERVIEW)
    advanceRender()
    return true
  }

  fun phoneHandoff(generation: Long): ProjectHudPhoneHandoff? {
    if (!acceptAction(generation, "phone")) return null
    val projectId = selectedProjectId ?: return null
    return ProjectHudPhoneHandoff(
        projectId = projectId,
        destination =
            if (lastReadyContent?.attentionSummary != null) {
              ProjectHudPhoneDestination.PROJECT_REVIEW
            } else {
              ProjectHudPhoneDestination.PROJECT_DETAIL
            },
    )
  }

  fun acceptRefresh(generation: Long): ProjectHudLoadRequest? {
    if (!acceptAction(generation, "refresh")) return null
    return refresh()
  }

  fun phoneActionLabel(): String =
      if (lastReadyContent?.attentionSummary != null) "Review on phone" else "Continue on phone"

  private fun isCurrent(request: ProjectHudLoadRequest): Boolean =
      request.token == requestToken && request.projectId == selectedProjectId

  private fun nextRequest(projectId: String, projectName: String): ProjectHudLoadRequest {
    requestToken += 1
    return ProjectHudLoadRequest(projectId, projectName, requestToken)
  }

  private fun acceptAction(generation: Long, action: String): Boolean {
    if (generation != renderGeneration) return false
    return consumedActions.add("$generation:$action")
  }

  private fun advanceRender() {
    renderGeneration += 1
    consumedActions = mutableSetOf()
  }

  companion object {
    fun mapOverview(overview: ProjectOverview, fallbackProjectName: String): ProjectHudContent {
      val proposalCount = overview.pendingProposals.size
      val attention =
          when {
            proposalCount == 1 -> "1 suggested Project change is waiting for review on your phone."
            proposalCount > 1 -> "$proposalCount suggested Project changes are waiting for review on your phone."
            else -> null
          }
      val investigation = overview.latestInvestigation
      val latestGuidance =
          investigation?.hypothesis?.trim()?.takeIf(String::isNotEmpty)?.let {
            // A trust decision records the user's assessment of an inference; it does not make
            // the inferred RESULT a canonically confirmed fact. SavedInvestigationReview does
            // not currently expose confirmation_status, so the HUD must retain the honest
            // unconfirmed label for every AI hypothesis it projects.
            "AI suggestion — unconfirmed: $it"
          }
      return ProjectHudContent(
          projectId = overview.project.projectId,
          projectName = overview.project.name.ifBlank { fallbackProjectName },
          whereWeLeftOff = overview.checkpoint.whereWeLeftOff?.trim()?.takeIf(String::isNotEmpty),
          nextAction = overview.checkpoint.nextAction?.trim()?.takeIf(String::isNotEmpty),
          evidenceCount = investigation?.evidenceCount ?: 0,
          latestGuidance = latestGuidance,
          attentionSummary = attention,
      )
    }
  }
}
