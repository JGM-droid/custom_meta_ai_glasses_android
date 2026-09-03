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

  /**
   * Proven physical gap: Capture -> Use leaves evidence captured but no explanation yet
   * (analysisEligibility.hasEvidence, no glasses-side explanation input - see analysisRow's
   * doc). Continue on phone from THAT state must land the phone directly on the active
   * investigation, not a generic Project screen the user then has to navigate away from to find
   * their own just-captured photos.
   */
  @Test
  fun activeInvestigationHandoffIsOfferedWhenEvidenceExistsWithoutExplanation() {
    val state = loadedState()
    state.setAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
    )
    val generation = state.renderGeneration

    assertEquals(
        ProjectHudPhoneHandoff(PROJECT_A, ProjectHudPhoneDestination.ACTIVE_INVESTIGATION),
        state.phoneHandoff(generation),
    )
  }

  /**
   * ACTIVE_INVESTIGATION takes priority over PROJECT_REVIEW (see phoneHandoff()'s doc): even
   * with a pending trust review already saved to the canonical Project, evidence captured THIS
   * sitting with nowhere else to go yet is the more useful phone landing.
   */
  @Test
  fun activeInvestigationHandoffTakesPriorityOverPendingReview() {
    val state = loadedState(proposalCount = 1)
    state.setAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
    )
    val generation = state.renderGeneration

    assertEquals(
        ProjectHudPhoneDestination.ACTIVE_INVESTIGATION,
        state.phoneHandoff(generation)?.destination,
    )
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

  @Test
  fun captureTapMovesToCapturingAndIgnoresADuplicateTapAtTheSameGeneration() {
    val state = loadedState()
    val generation = state.renderGeneration

    assertTrue(state.acceptCapture(generation))
    assertEquals(ProjectHudCaptureStatus.Capturing, state.captureStatus)
    // A second tap before the result comes back - whether it's the same render generation (stale
    // button reference) or the still-Capturing status itself - must never queue a second request.
    assertFalse(state.acceptCapture(generation))
    assertFalse(state.acceptCapture(state.renderGeneration))
    assertEquals(ProjectHudCaptureStatus.Capturing, state.captureStatus)
  }

  @Test
  fun captureSuccessAwaitsConfirmationRatherThanReturningStraightToIdle() {
    val state = loadedState()
    val generation = state.renderGeneration
    state.acceptCapture(generation)

    assertTrue(state.captureSucceeded())

    // The photo must stay pending - not silently usable, and not silently discarded - until an
    // explicit Use or Retake tap resolves it.
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, state.captureStatus)
  }

  @Test
  fun captureFailureIsShownHonestlyAndOnlyClearsOnAnExplicitRetryTap() {
    val state = loadedState()
    val generation = state.renderGeneration
    state.acceptCapture(generation)

    assertTrue(state.captureFailed("No active stream is available for capture."))
    val failed = state.captureStatus as ProjectHudCaptureStatus.Failed
    assertEquals("No active stream is available for capture.", failed.message)

    // The failure must never clear itself - only a new explicit tap (a fresh acceptCapture at the
    // new generation the failure's own render advanced to) moves off Failed.
    assertEquals(ProjectHudCaptureStatus.Failed(failed.message), state.captureStatus)
    assertTrue(state.acceptCapture(state.renderGeneration))
    assertEquals(ProjectHudCaptureStatus.Capturing, state.captureStatus)
  }

  @Test
  fun captureResultCallbacksAreIgnoredWhenNothingIsInFlight() {
    val state = loadedState()

    // A late/stray success or failure callback with nothing Capturing/AwaitingConfirmation (e.g.
    // it arrived after the HUD had already moved on) must not corrupt the current status.
    assertFalse(state.captureSucceeded())
    assertFalse(state.captureFailed("late error"))
    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun useTapIsAcceptedWhileAwaitingConfirmationAndIsDuplicatePressSafe() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    val generation = state.renderGeneration

    // acceptUse deliberately does not change captureStatus itself (see its doc) - the owner
    // performs the real append and reports back via captureAccepted/captureFailed.
    assertTrue(state.acceptUse(generation))
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, state.captureStatus)

    // A second Use tap at the same generation - e.g. a stale button reference - must not queue a
    // second append.
    assertFalse(state.acceptUse(generation))
  }

  @Test
  fun useIsRejectedWhenNothingIsAwaitingConfirmation() {
    val state = loadedState()

    assertFalse(state.acceptUse(state.renderGeneration))
  }

  @Test
  fun useAcceptedReturnsToIdleReadyForAnotherCapture() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    state.acceptUse(state.renderGeneration)

    assertTrue(state.captureAccepted())
    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)

    // Capture is immediately available again - a real slot check happens where the evidence slots
    // actually live (InvestigationSessionDebugViewModel), not in this HUD state machine.
    assertTrue(state.acceptCapture(state.renderGeneration))
  }

  @Test
  fun useFailureUsesTheSameHonestFailedPresentationAsACaptureFailure() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    state.acceptUse(state.renderGeneration)

    // e.g. the Investigation's 5-photo capacity filled between capture and Use.
    assertTrue(state.captureFailed("Investigation full. 5 photos added."))

    val failed = state.captureStatus as ProjectHudCaptureStatus.Failed
    assertEquals("Investigation full. 5 photos added.", failed.message)
  }

  @Test
  fun captureAcceptedIsIgnoredWhenNothingIsAwaitingConfirmation() {
    val state = loadedState()

    assertFalse(state.captureAccepted())
    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun retakeDiscardsThePendingPhotoAndReturnsImmediatelyToCapture() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    val generation = state.renderGeneration

    assertTrue(state.acceptRetake(generation))
    // Unlike Use, Retake resolves synchronously - it can never fail, so there is no owner
    // round-trip and no separate "retake accepted" callback.
    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
    assertTrue(state.acceptCapture(state.renderGeneration))
  }

  @Test
  fun retakeIsRejectedWhenNothingIsAwaitingConfirmationAndIsDuplicatePressSafe() {
    val state = loadedState()

    assertFalse(state.acceptRetake(state.renderGeneration))

    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    val generation = state.renderGeneration
    assertTrue(state.acceptRetake(generation))
    // A second Retake tap at the same (now-stale) generation must not do anything further.
    assertFalse(state.acceptRetake(generation))
  }

  @Test
  fun selectingAProjectResetsAnyLeftoverCaptureStatus() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    assertEquals(ProjectHudCaptureStatus.Capturing, state.captureStatus)

    state.selectProject(PROJECT_B, "Room Redesign")

    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun disconnectingResetsAnyLeftoverCaptureStatus() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)

    state.disconnected()

    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun selectingAProjectResetsAPendingAwaitingConfirmationStatusToo() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, state.captureStatus)

    // A Project switch mid-confirmation must never let the new Project inherit a decision about a
    // photo captured for the previous one.
    state.selectProject(PROJECT_B, "Room Redesign")

    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun disconnectingResetsAPendingAwaitingConfirmationStatusToo() {
    val state = loadedState()
    state.acceptCapture(state.renderGeneration)
    state.captureSucceeded()

    state.disconnected()

    assertEquals(ProjectHudCaptureStatus.Idle, state.captureStatus)
  }

  @Test
  fun staleGenerationFromBeforeARenderFailureStaysRejectedAfterRecovery() {
    // Models the proven physical bug: Capturing then Failed fire back-to-back, advancing
    // renderGeneration twice, while a transient DAT Display send failure could leave the glasses
    // still showing the ORIGINAL pre-tap screen until ProjectContinuityHudController's
    // render-retry catches the Display up. The fix belongs entirely in getting the Display to
    // resync to the current generation - this replay-protection check itself must not change.
    val state = loadedState()
    val originalGeneration = state.renderGeneration

    state.acceptCapture(originalGeneration)
    state.captureFailed("Failed to capture photo")

    // Two advanceRender() calls have happened (Capturing, then Failed).
    assertTrue(state.renderGeneration > originalGeneration + 1)

    // A tap carrying the ORIGINAL, pre-capture generation - i.e. from a screen the render-retry
    // has not yet caught up to - must still be rejected, for every action, not only Capture.
    assertFalse(state.acceptCapture(originalGeneration))
    assertNull(state.phoneHandoff(originalGeneration))
    assertNull(state.acceptRefresh(originalGeneration))

    // The CURRENT generation - what the render-retry will actually deliver to the glasses - must
    // keep working normally.
    assertTrue(state.acceptCapture(state.renderGeneration))
  }

  @Test
  fun mapOverviewExposesAPendingTrustReviewOnlyWhenUndecided() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")
    state.accept(request, overview(PROJECT_A, "AC Repair", trustDecision = null))

    val pending = (state.uiState as ProjectHudUiState.Ready).content.pendingTrustReview
    assertEquals("session-a", pending?.sessionId)
    assertEquals("Wiring may be reversed.", pending?.hypothesis)
    assertEquals("Inspect the terminals.", pending?.recommendedNextAction)
  }

  @Test
  fun mapOverviewHasNoPendingTrustReviewOnceADecisionIsRecorded() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")
    state.accept(request, overview(PROJECT_A, "AC Repair", trustDecision = "continue"))

    assertNull((state.uiState as ProjectHudUiState.Ready).content.pendingTrustReview)
  }

  @Test
  fun analysisEligibilityOnlyTriggersARenderOnAnActualChange() {
    val state = ProjectContinuityHudStateMachine()

    // Already the all-false default - no-op.
    assertFalse(state.setAnalysisEligibility(ProjectHudAnalysisEligibility()))
    val generationBeforeChange = state.renderGeneration

    assertTrue(state.setAnalysisEligibility(ELIGIBLE))
    assertEquals(ELIGIBLE, state.analysisEligibility)
    assertTrue(state.renderGeneration > generationBeforeChange)

    // Same value again - no-op.
    assertFalse(state.setAnalysisEligibility(ELIGIBLE))
  }

  /**
   * Stage 1 requirement: evidence with no explanation/context yet must be distinguishable from
   * both "nothing captured" and "ready to analyze" - this is what lets analysisRow show the
   * "Continue on your phone to finish analyzing this project." hint instead of silently omitting Analyze. The actual
   * rendered hint text itself is exercised by the Stage 2 harness (a real Display is needed to
   * observe rendered content) - this proves the eligibility signal it reads from is correct.
   */
  @Test
  fun evidenceWithoutContextIsDistinctFromNoEvidenceAndFromReadyToAnalyze() {
    val noEvidence = ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = false, hasExplanation = false)
    val evidenceNoContext = ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false)
    val evidenceWithContext = ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true)

    assertFalse(noEvidence.hasEvidence)
    assertTrue(evidenceNoContext.hasEvidence)
    assertFalse(evidenceNoContext.hasExplanation)
    assertFalse(evidenceNoContext.canAnalyze)
    assertTrue(evidenceWithContext.canAnalyze)
  }

  /** Stage 1 requirement: evidence + context present -> Analyze becomes available. */
  @Test
  fun evidenceWithContextMakesAnalyzeAvailable() {
    val state = loadedState()

    assertTrue(state.setAnalysisEligibility(ELIGIBLE))
    assertTrue(state.analysisEligibility.canAnalyze)
    assertTrue(state.acceptAnalyze(state.renderGeneration))
  }

  @Test
  fun analyzeIsRejectedWhileNotEligible() {
    val state = loadedState()

    assertFalse(state.acceptAnalyze(state.renderGeneration))
    assertEquals(ProjectHudAnalysisStatus.Idle, state.analysisStatus)

    // Evidence alone (no explanation) must still reject the tap - canAnalyze itself is unchanged.
    state.setAnalysisEligibility(ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false))
    assertFalse(state.acceptAnalyze(state.renderGeneration))
  }

  @Test
  fun analyzeTapMovesToWorkingAndIsDuplicatePressSafe() {
    val state = loadedState()
    state.setAnalysisEligibility(ELIGIBLE)
    val generation = state.renderGeneration

    assertTrue(state.acceptAnalyze(generation))
    assertEquals(ProjectHudAnalysisStatus.Working, state.analysisStatus)
    assertFalse(state.acceptAnalyze(generation))
    assertFalse(state.acceptAnalyze(state.renderGeneration))
  }

  @Test
  fun analysisSuccessReturnsToIdle() {
    val state = loadedState()
    state.setAnalysisEligibility(ELIGIBLE)
    state.acceptAnalyze(state.renderGeneration)

    assertTrue(state.analysisSucceeded())
    assertEquals(ProjectHudAnalysisStatus.Idle, state.analysisStatus)
  }

  @Test
  fun analysisFailureIsShownHonestlyAndOnlyClearsOnAnExplicitRetryTap() {
    val state = loadedState()
    state.setAnalysisEligibility(ELIGIBLE)
    state.acceptAnalyze(state.renderGeneration)

    assertTrue(state.analysisFailed("Analysis backend unavailable."))
    val failed = state.analysisStatus as ProjectHudAnalysisStatus.Failed
    assertEquals("Analysis backend unavailable.", failed.message)
    assertEquals(ProjectHudAnalysisStatus.Failed(failed.message), state.analysisStatus)

    state.setAnalysisEligibility(ELIGIBLE)
    assertTrue(state.acceptAnalyze(state.renderGeneration))
    assertEquals(ProjectHudAnalysisStatus.Working, state.analysisStatus)
  }

  @Test
  fun analysisResultCallbacksAreIgnoredWhenNothingIsWorking() {
    val state = loadedState()

    assertFalse(state.analysisSucceeded())
    assertFalse(state.analysisFailed("late error"))
    assertEquals(ProjectHudAnalysisStatus.Idle, state.analysisStatus)
  }

  @Test
  fun trustDecisionTapReturnsTheSessionIdOfThePendingReviewAndIsDuplicatePressSafe() {
    val state = loadedState() // default fixture has an undecided pending review - see overview()
    val generation = state.renderGeneration

    assertEquals(
        "session-a",
        state.acceptTrustDecision(generation, ProjectHudTrustAction.KEEP_AS_HYPOTHESIS),
    )
    assertEquals(ProjectHudAnalysisStatus.Working, state.analysisStatus)
    // A second trust tap while the first is still Working - whether ADD_EVIDENCE or the same
    // action again - must not queue a second submission.
    assertNull(state.acceptTrustDecision(generation, ProjectHudTrustAction.ADD_EVIDENCE))
  }

  @Test
  fun trustDecisionTapIsRejectedWhenThereIsNoPendingReviewToDecideOn() {
    val state = ProjectContinuityHudStateMachine()
    val request = state.selectProject(PROJECT_A, "AC Repair")
    // Already decided - mapOverview will not expose a pendingTrustReview for this content.
    state.accept(request, overview(PROJECT_A, "AC Repair", trustDecision = "continue"))

    assertNull(state.acceptTrustDecision(state.renderGeneration, ProjectHudTrustAction.RETURN))
  }

  @Test
  fun selectingAProjectResetsAnalysisEligibilityAndStatus() {
    val state = loadedState()
    state.setAnalysisEligibility(ELIGIBLE)
    state.acceptAnalyze(state.renderGeneration)
    assertEquals(ProjectHudAnalysisStatus.Working, state.analysisStatus)

    state.selectProject(PROJECT_B, "Room Redesign")

    assertEquals(ProjectHudAnalysisEligibility(), state.analysisEligibility)
    assertEquals(ProjectHudAnalysisStatus.Idle, state.analysisStatus)
  }

  @Test
  fun disconnectingResetsAnalysisStatusButNotEligibility() {
    val state = loadedState()
    state.setAnalysisEligibility(ELIGIBLE)
    state.acceptAnalyze(state.renderGeneration)

    state.disconnected()

    // analysisEligibility is an availability signal from the Investigation ViewModel, not a
    // request in flight - a dropped glasses connection does not change whether evidence/
    // explanation exist on the phone, so it is deliberately left alone (see disconnected()'s doc).
    assertEquals(ELIGIBLE, state.analysisEligibility)
    assertEquals(ProjectHudAnalysisStatus.Idle, state.analysisStatus)
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
    val ELIGIBLE = ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true)
  }
}
