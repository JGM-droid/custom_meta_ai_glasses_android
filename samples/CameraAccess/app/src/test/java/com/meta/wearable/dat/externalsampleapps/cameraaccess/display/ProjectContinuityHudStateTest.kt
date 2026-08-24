/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.CheckpointProposalReview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectCheckpoint
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContinuityHudStateTest {
  @Test
  fun explicitProjectMapsCanonicalContinuityAndAttention() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")

    assertTrue(state.uiState is ProjectHudUiState.Loading)
    assertTrue(state.accept(request, overview(PROJECT_A, "AC Repair", proposalCount = 2)))

    val ready = state.uiState as ProjectHudUiState.Ready
    assertEquals(PROJECT_A, ready.projectId)
    assertEquals("Blower motor removed.", ready.content.whereWeLeftOff)
    assertEquals("Verify wiring before installation.", ready.content.nextAction)
    assertEquals(3, ready.content.evidenceCount)
    assertEquals("2 suggested Project changes are waiting for review on your phone.", ready.content.attentionSummary)
    assertEquals("AI suggestion — unconfirmed: Wiring may be reversed.", ready.content.latestGuidance)
  }

  @Test
  fun userTrustDecisionDoesNotMislabelAiInferenceAsConfirmed() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")

    state.accept(
        request,
        overview(PROJECT_A, "AC Repair", trustDecision = "continue"),
    )

    assertEquals(
        "AI suggestion — unconfirmed: Wiring may be reversed.",
        (state.uiState as ProjectHudUiState.Ready).content.latestGuidance,
    )
  }

  @Test
  fun staleProjectAResponseCannotLeakIntoProjectB() {
    val state = ProjectContinuityHudStateMachine()
    val requestA = state.selectProject(PROJECT_A, "AC Repair")
    val requestB = state.selectProject(PROJECT_B, "Room Redesign")

    assertFalse(state.accept(requestA, overview(PROJECT_A, "AC Repair")))
    assertTrue(state.accept(requestB, overview(PROJECT_B, "Room Redesign")))
    assertEquals(PROJECT_B, (state.uiState as ProjectHudUiState.Ready).projectId)
  }

  @Test
  fun reconnectForProjectBCannotShowRetainedProjectAContent() {
    val state = loadedState()
    state.selectProject(PROJECT_B, "Room Redesign")
    state.disconnected()

    state.refresh(reconnecting = true)

    val loading = state.uiState as ProjectHudUiState.Loading
    assertEquals(PROJECT_B, loading.projectId)
  }

  @Test
  fun sameProjectReopenStartsFreshCanonicalLoadBeforeReadyPresentation() {
    val state = loadedState()

    // A new shared-session attachment selects the explicit Project again. Even for the same
    // project_id, the prior Ready presentation must not remain visible while canonical state is
    // being reconstructed.
    val reopenRequest = state.selectProject(PROJECT_A, "AC Repair")

    assertTrue(state.uiState is ProjectHudUiState.Loading)
    assertTrue(
        state.accept(
            reopenRequest,
            overview(PROJECT_A, "AC Repair").copy(
                checkpoint = ProjectCheckpoint("Canonical state changed.", "Use the new state."),
            ),
        )
    )
    val ready = state.uiState as ProjectHudUiState.Ready
    assertEquals("Canonical state changed.", ready.content.whereWeLeftOff)
    assertEquals("Use the new state.", ready.content.nextAction)
  }

  @Test
  fun mismatchedBackendIdentityIsRejected() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")

    assertFalse(state.accept(request, overview(PROJECT_B, "Room Redesign")))
    assertTrue(state.uiState is ProjectHudUiState.Loading)
  }

  @Test
  fun emptyProjectRemainsHonest() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "New Project")
    state.accept(
        request,
        ProjectOverview(
            project = ProjectSummary(PROJECT_A, "New Project", "active"),
            checkpoint = ProjectCheckpoint(null, null),
            recentActivity = emptyList(),
        ),
    )

    assertTrue((state.uiState as ProjectHudUiState.Ready).content.isEmpty)
  }

  @Test
  fun overviewDetailsBackAndCallbackDedupAreDeterministic() {
    val state = loadedState()
    val overviewGeneration = state.renderGeneration

    assertTrue(state.showDetails(overviewGeneration))
    assertFalse(state.showDetails(overviewGeneration))
    assertEquals(ProjectHudDestination.DETAILS, (state.uiState as ProjectHudUiState.Ready).destination)

    val detailsGeneration = state.renderGeneration
    assertTrue(state.showOverview(detailsGeneration))
    assertFalse(state.showOverview(detailsGeneration))
    assertEquals(ProjectHudDestination.OVERVIEW, (state.uiState as ProjectHudUiState.Ready).destination)
  }

  @Test
  fun handoffKeepsExactProjectAndReviewDestinationAndDeduplicates() {
    val state = loadedState(proposalCount = 1)
    val generation = state.renderGeneration

    assertEquals(
        ProjectHudPhoneHandoff(PROJECT_A, ProjectHudPhoneDestination.PROJECT_REVIEW),
        state.phoneHandoff(generation),
    )
    assertNull(state.phoneHandoff(generation))
    assertEquals("Review on phone", state.phoneActionLabel())
  }

  @Test
  fun renderedNonReadyStatesHaveWorkingExplicitProjectHandoffs() {
    val loading = ProjectContinuityHudStateMachine()
    loading.selectProject(PROJECT_A, "AC Repair")
    assertEquals(
        ProjectHudPhoneHandoff(PROJECT_A, ProjectHudPhoneDestination.PROJECT_DETAIL),
        loading.phoneHandoff(loading.renderGeneration),
    )

    val error = ProjectContinuityHudStateMachine()
    val errorRequest = error.selectProject(PROJECT_A, "AC Repair")
    error.fail(errorRequest, "offline")
    assertEquals(PROJECT_A, error.phoneHandoff(error.renderGeneration)?.projectId)

    val stale = loadedState(proposalCount = 1)
    stale.refresh()
    assertEquals(
        ProjectHudPhoneDestination.PROJECT_REVIEW,
        stale.phoneHandoff(stale.renderGeneration)?.destination,
    )

    val disconnected = loadedState(proposalCount = 1)
    disconnected.disconnected()
    val generation = disconnected.renderGeneration
    assertEquals(
        ProjectHudPhoneHandoff(PROJECT_A, ProjectHudPhoneDestination.PROJECT_REVIEW),
        disconnected.phoneHandoff(generation),
    )
    assertNull(disconnected.phoneHandoff(generation))
    assertEquals("Review on phone", disconnected.phoneActionLabel())
  }

  @Test
  fun detailsAreHiddenWhenTheyWouldOnlyRepeatOverview() {
    val content =
        ProjectHudContent(
            projectId = PROJECT_A,
            projectName = "AC Repair",
            whereWeLeftOff = "Blower removed",
            nextAction = "Check wiring",
            evidenceCount = 0,
            latestGuidance = null,
            attentionSummary = "1 suggested Project change is waiting for review on your phone.",
        )

    assertFalse(content.hasAdditionalDetails)
    assertTrue(content.copy(evidenceCount = 1).hasAdditionalDetails)
    assertTrue(content.copy(latestGuidance = "AI suggestion — unconfirmed: inspect wiring").hasAdditionalDetails)
  }

  @Test
  fun refreshAndReconnectRequireNewCanonicalResponse() {
    val state = loadedState()
    val oldGeneration = state.renderGeneration
    val request = state.refresh(reconnecting = true)!!

    assertTrue(state.uiState is ProjectHudUiState.Stale)
    assertFalse(state.showDetails(oldGeneration))
    assertTrue(state.accept(request, overview(PROJECT_A, "AC Repair")))
    assertTrue(state.uiState is ProjectHudUiState.Ready)
  }

  @Test
  fun disconnectedStatePreservesExplicitIdentity() {
    val state = loadedState()
    state.disconnected()

    val disconnected = state.uiState as ProjectHudUiState.Disconnected
    assertEquals(PROJECT_A, disconnected.projectId)
    assertEquals("AC Repair", disconnected.projectName)
    state.refresh(reconnecting = true)
    assertTrue(state.uiState is ProjectHudUiState.Stale)
  }

  private fun loadedState(proposalCount: Int = 0): ProjectContinuityHudStateMachine =
      ProjectContinuityHudStateMachine().also { state ->
        val request = state.selectProject(PROJECT_A, "AC Repair")
        state.accept(request, overview(PROJECT_A, "AC Repair", proposalCount))
      }

  private fun overview(
      projectId: String,
      name: String,
      proposalCount: Int = 0,
      trustDecision: String? = null,
  ): ProjectOverview =
      ProjectOverview(
          project = ProjectSummary(projectId, name, "active"),
          checkpoint =
              ProjectCheckpoint(
                  whereWeLeftOff = "Blower motor removed.",
                  nextAction = "Verify wiring before installation.",
              ),
          recentActivity = emptyList(),
          latestInvestigation =
              SavedInvestigationReview(
                  sessionId = "session-a",
                  projectId = projectId,
                  status = "completed",
                  completedAtUtc = "2026-08-24T00:00:00Z",
                  evidenceCount = 3,
                  explanation = null,
                  hypothesis = "Wiring may be reversed.",
                  recommendedNextAction = "Inspect the terminals.",
                  trustDecision = trustDecision,
                  proposalId = null,
                  proposalStatus = null,
                  followUpSessionId = null,
                  retainedImage = null,
              ),
          pendingProposals =
              (1..proposalCount).map { index ->
                CheckpointProposalReview(
                    proposalId = "proposal-$index",
                    projectId = projectId,
                    status = "pending",
                    reason = "Review suggested change",
                    proposedFields = mapOf("next_action" to "Action $index"),
                )
              },
      )

  private companion object {
    const val PROJECT_A = "11111111-1111-1111-1111-111111111111"
    const val PROJECT_B = "22222222-2222-2222-2222-222222222222"
  }
}
