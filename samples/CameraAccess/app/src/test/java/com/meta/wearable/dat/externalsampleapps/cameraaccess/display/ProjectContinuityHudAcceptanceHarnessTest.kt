/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectCheckpoint
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First Glasses Acceptance Harness scenario (see docs/ROADMAP.md's Glasses foundation) - drives
 * the real [ProjectContinuityHudController] end to end so a workflow/state/integration regression
 * across Capture -> Use -> Analyze -> trust decision surfaces here, before requiring physical
 * glasses. See [ProjectContinuityHudTestHarness]'s class doc for exactly what is and is not faked,
 * and why. Kept deliberately narrow - one full-loop scenario, one isolated regression test for the
 * missing-explanation gap that escaped Stage 1's state-machine-only tests, and one disconnect
 * scenario - not an attempt to emulate every DAT condition.
 */
class ProjectContinuityHudAcceptanceHarnessTest {
  @Test
  fun fullLoop_captureFailureRecovery_use_analyzeEligibility_analyze_trustReview_trustDecision_refresh() =
      runBlocking {
        val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())

        // --- Project HUD ---
        harness.openProject(PROJECT_A, "AC Repair")
        harness.attachDisplay()
        assertTrue(harness.stateMachine.uiState is ProjectHudUiState.Ready)
        assertTrue((harness.stateMachine.uiState as ProjectHudUiState.Ready).content.isEmpty)
        assertEquals(1, harness.display.sendContentCallCount)

        // --- Capture ---
        harness.tapCapture()
        assertEquals(ProjectHudCaptureStatus.Capturing, harness.stateMachine.captureStatus)
        assertEquals(1, harness.captureRequestedCount)

        // --- capture failure + display failure/recovery ---
        // The proven physical bug: a transient DAT Display send failure right as the capture
        // itself fails must not leave the HUD permanently stale. Script the very next Display
        // send (the Failed-status render) to fail once, then recover on its own bounded retry.
        val generationBeforeFailure = harness.stateMachine.renderGeneration
        harness.display.scriptNextSendContentResult(false)
        val sendCountBeforeFailure = harness.display.sendContentCallCount
        harness.completeCaptureFailure("Failed to capture photo")
        harness.settle(900) // > RENDER_RETRY_DELAY_MS (500ms) so the bounded retry has run

        val failed = harness.stateMachine.captureStatus as ProjectHudCaptureStatus.Failed
        assertEquals("Failed to capture photo", failed.message)
        // One failed attempt + one recovering retry for this transition.
        assertEquals(sendCountBeforeFailure + 2, harness.display.sendContentCallCount)
        // The retry succeeded, so the owner was never told the Display update failed.
        assertTrue(harness.displayErrors.isEmpty())
        // Generation/recovery: renderGeneration moved on (Capturing -> Failed), and a tap carrying
        // the OLD, pre-failure generation must still be rejected - the fix is in the Display
        // catching back up, never in relaxing this check.
        assertTrue(harness.stateMachine.renderGeneration > generationBeforeFailure)
        val statusBeforeStaleTap = harness.stateMachine.captureStatus
        harness.controller.dispatchCapture(generationBeforeFailure)
        assertEquals(statusBeforeStaleTap, harness.stateMachine.captureStatus)

        // --- successful Capture (retry) ---
        harness.tapCapture()
        assertEquals(ProjectHudCaptureStatus.Capturing, harness.stateMachine.captureStatus)
        harness.completeCaptureSuccess()
        assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, harness.stateMachine.captureStatus)

        // --- Use ---
        harness.tapUse()
        assertEquals(1, harness.useRequestedCount)
        harness.completeUseAccepted()
        assertEquals(ProjectHudCaptureStatus.Idle, harness.stateMachine.captureStatus)

        // --- Analyze eligibility: the missing-explanation gap, exercised through the real
        // controller (not just the state machine directly - see the dedicated test below for the
        // isolated version of this same regression) ---
        harness.pushAnalysisEligibility(
            ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
        )
        harness.tapAnalyze()
        assertEquals(0, harness.analyzeRequestedCount)
        assertEquals(ProjectHudAnalysisStatus.Idle, harness.stateMachine.analysisStatus)

        // Context added on the phone - eligibility becomes true through the same reactive path.
        harness.pushAnalysisEligibility(
            ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true),
        )
        assertTrue(harness.stateMachine.analysisEligibility.canAnalyze)

        // --- Analyze ---
        harness.tapAnalyze()
        assertEquals(1, harness.analyzeRequestedCount)
        assertEquals(ProjectHudAnalysisStatus.Working, harness.stateMachine.analysisStatus)

        // --- pending AI trust review ---
        harness.repository.currentOverview = overviewWithUndecidedReview()
        harness.completeAnalysisSucceeded()
        val pending = (harness.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview
        assertEquals(SESSION_ID, pending?.sessionId)
        assertEquals("Wiring may be reversed.", pending?.hypothesis)
        assertEquals(ProjectHudAnalysisStatus.Idle, harness.stateMachine.analysisStatus)

        // --- trust decision ---
        harness.tapTrustDecision(ProjectHudTrustAction.KEEP_AS_HYPOTHESIS)
        assertEquals(listOf(ProjectHudTrustAction.KEEP_AS_HYPOTHESIS to SESSION_ID), harness.trustDecisionRequests)
        assertEquals(ProjectHudAnalysisStatus.Working, harness.stateMachine.analysisStatus)

        // --- refreshed Project state ---
        harness.repository.currentOverview = overviewWithDecidedReview()
        harness.completeAnalysisSucceeded()
        assertNull((harness.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview)
        assertEquals(ProjectHudAnalysisStatus.Idle, harness.stateMachine.analysisStatus)
        // The refresh actually re-fetched canonical state, not just cleared local status.
        assertTrue(harness.repository.getProjectOverviewCallCount >= 3)
        harness.close()
      }

  /**
   * Isolated regression test for the exact gap that escaped Stage 1's state-machine-only tests:
   * evidence exists (a real Capture -> Use just ran through the real controller) but no
   * explanation does, so Analyze must stay unreachable even though a tap is attempted - proven
   * through dispatchAnalyze()/onAnalyzeRequested, not just through setAnalysisEligibility() in
   * isolation.
   */
  @Test
  fun missingExplanationKeepsAnalyzeUnreachableThroughTheRealController() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()

    // Evidence exists (Use just succeeded); explanation does not - the exact proven physical gap.
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
    )

    harness.tapAnalyze()

    assertEquals(0, harness.analyzeRequestedCount)
    assertEquals(ProjectHudAnalysisStatus.Idle, harness.stateMachine.analysisStatus)
    assertFalse(harness.stateMachine.analysisEligibility.canAnalyze)
    harness.close()
  }

  /**
   * Proven physical gap this closes: from the exact same missing-explanation state as the test
   * above, "Continue on phone" (dispatchPhone) through the REAL controller must land on
   * ACTIVE_INVESTIGATION - not the generic PROJECT_DETAIL a caller (StreamScreen) would otherwise
   * send the user to a screen with no visible trace of their own just-captured evidence. The
   * pushAnalysisEligibility call here is unrelated to that destination now (see
   * activeInvestigationHandoffFiresWithoutAnyAnalysisEligibilityPush below for the direct proof
   * of that) - it only sets up this test's OWN missing-explanation condition, still exercised
   * because ADD/hasExplanation gating (canAnalyze) is otherwise untouched by this milestone.
   */
  @Test
  fun continueOnPhoneFromMissingExplanationStateOffersActiveInvestigationHandoff() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
    )

    harness.tapPhone()

    assertEquals(1, harness.phoneHandoffs.size)
    assertEquals(ProjectHudPhoneDestination.ACTIVE_INVESTIGATION, harness.phoneHandoffs.single().destination)
    harness.close()
  }

  /**
   * The direct proof of this milestone's determinism fix: ACTIVE_INVESTIGATION must fire from
   * captureAccepted() alone - this test never calls pushAnalysisEligibility at all, unlike every
   * other test above/below that happens to also exercise it for its own (canAnalyze-gating)
   * reasons. phoneHandoff() no longer reads analysisEligibility for this decision at all (see
   * ProjectContinuityHudState.kt).
   */
  @Test
  fun activeInvestigationHandoffFiresWithoutAnyAnalysisEligibilityPush() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()

    assertTrue(harness.stateMachine.evidenceAcceptedThisSession)
    harness.tapPhone()

    assertEquals(1, harness.phoneHandoffs.size)
    assertEquals(ProjectHudPhoneDestination.ACTIVE_INVESTIGATION, harness.phoneHandoffs.single().destination)
    harness.close()
  }

  /**
   * Option B closed loop (docs/ROADMAP.md): "Continue on phone" (dispatchPhone) hands off to the
   * phone-native ContinueInvestigationSection (ProjectDetailScreen.kt), which reuses the SAME
   * InvestigationSessionDebugViewModel instance the glasses side was using (see
   * investigationViewModelKey's doc - this app has no NavHost, so the Activity's shared
   * ViewModelStore is what actually carries evidence/explanation across the handoff, not any new
   * persistence). "Resume on glasses" then re-enters Capture for the same Project, which
   * re-selects it (configureProjectHud's selectProject() call, since stopStream() clears
   * projectHudProjectId - see StreamViewModel's doc) and re-attaches the Display.
   * selectProject() resets analysisEligibility to its all-false default
   * (ProjectContinuityHudState.kt) - this proves the SAME reactive eligibility push already
   * exercised elsewhere in this harness (StreamScreen's LaunchedEffect(hudAnalysisEligibility) in
   * production) is what re-establishes it from the now-populated phone-side state after that
   * reset, not any value magically carried through reselection itself. Note: this test is about
   * canAnalyze gating specifically, not the phone-handoff DESTINATION decision, which is now
   * evidenceAcceptedThisSession's job (also correctly reset by this same selectProject() call -
   * see evidenceAcceptedThisSessionResetsOnFreshProjectSelection in
   * ProjectContinuityHudStateTest.kt).
   */
  @Test
  fun phoneHandoffThenResumeOnGlasses_reselectionResetsEligibilityUntilReactivelyRestored() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = false, hasEvidence = true, hasExplanation = false),
    )

    // --- Continue on phone ---
    harness.tapPhone()
    assertEquals(1, harness.phoneHandoffs.size)
    assertEquals(PROJECT_A, harness.phoneHandoffs.single().projectId)

    // --- Resume on glasses: re-enters Capture for the SAME Project, exactly as
    // configureProjectHud() does once stopStream() has cleared projectHudProjectId. ---
    harness.openProject(PROJECT_A, "AC Repair")
    assertFalse(harness.stateMachine.analysisEligibility.canAnalyze)

    harness.attachDisplay()
    // The phone-side explanation the user just typed, read back through the SAME ViewModel
    // instance (investigationViewModelKey) - reactively re-pushed on recomposition, exactly like
    // StreamScreen's LaunchedEffect(hudAnalysisEligibility) already does in production.
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true),
    )
    assertTrue(harness.stateMachine.analysisEligibility.canAnalyze)

    harness.tapAnalyze()
    assertEquals(1, harness.analyzeRequestedCount)
    harness.close()
  }

  /**
   * Simplified MVP: the trust-review screen's third action ("See details on phone") is a plain
   * phone handoff (dispatchPhone), never a ProjectHudTrustAction.RETURN/DISAGREE submission -
   * proven through the real controller by asserting no trust decision was ever requested and the
   * pending review is still exactly as undecided as before the tap (the user is only looking
   * closer, not deciding yet).
   */
  @Test
  fun seeDetailsOnPhoneFromPendingTrustReviewIsAPlainHandoffNotATrustDecision() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true),
    )
    harness.tapAnalyze()
    harness.repository.currentOverview = overviewWithUndecidedReview()
    harness.completeAnalysisSucceeded()
    val pendingBefore = (harness.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview
    assertEquals(SESSION_ID, pendingBefore?.sessionId)

    harness.tapPhone()

    assertEquals(1, harness.phoneHandoffs.size)
    assertTrue(harness.trustDecisionRequests.isEmpty())
    assertEquals(ProjectHudAnalysisStatus.Idle, harness.stateMachine.analysisStatus)
    val pendingAfter = (harness.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview
    assertEquals(pendingBefore, pendingAfter)
    harness.close()
  }

  /**
   * Simplified MVP requirement: "Add more info" must naturally allow the user to continue
   * gathering evidence, including another glasses photo. Proven through the real controller: once
   * the follow-up round's canonical refresh lands (the SAME existing refresh mechanism every
   * other completed Analyze/trust round already uses - see onAnalysisSucceeded's doc), Capture is
   * immediately dispatchable again with no special-casing - captureRow only ever hides Capture
   * for Loading/Disconnected/Error, none of which a fresh follow-up round is.
   */
  @Test
  fun addMoreInfoTrustDecisionLeavesTheHudReadyForAnotherCapture() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    harness.completeCaptureSuccess()
    harness.tapUse()
    harness.completeUseAccepted()
    harness.pushAnalysisEligibility(
        ProjectHudAnalysisEligibility(canAnalyze = true, hasEvidence = true, hasExplanation = true),
    )
    harness.tapAnalyze()
    harness.repository.currentOverview = overviewWithUndecidedReview()
    harness.completeAnalysisSucceeded()

    harness.tapTrustDecision(ProjectHudTrustAction.ADD_EVIDENCE)
    assertEquals(listOf(ProjectHudTrustAction.ADD_EVIDENCE to SESSION_ID), harness.trustDecisionRequests)
    assertEquals(ProjectHudAnalysisStatus.Working, harness.stateMachine.analysisStatus)

    // The follow-up round's own canonical refresh - no pending review yet for the NEW round.
    harness.repository.currentOverview = emptyOverview()
    harness.completeAnalysisSucceeded()

    assertNull((harness.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview)
    assertEquals(ProjectHudCaptureStatus.Idle, harness.stateMachine.captureStatus)
    harness.tapCapture()
    assertEquals(ProjectHudCaptureStatus.Capturing, harness.stateMachine.captureStatus)
    harness.close()
  }

  /**
   * Glasses UX hardening pass, requirement 4: a pure DAT capture failure (no accompanying Display
   * send failure - see fullLoop's compound case above for that one) must never strand the HUD.
   * captureRow renders the Failed status ALONGSIDE the rest of the current screen, never taking
   * it over the way AwaitingConfirmation/pendingTrustReview do (see renderState()'s doc) - this
   * proves that structurally: a completely unrelated action (Continue on phone) stays dispatchable
   * the whole time, and the SAME Capture button retries cleanly at the CURRENT generation. Not
   * fixing DAT's underlying capture reliability - proving the HUD's OWN state never gets stuck
   * because of it.
   *
   * Deliberately does not assert an exact sendContentCallCount: sendCurrentState() reads
   * whatever is CURRENT at the moment it actually runs (by design - see its doc), so a render()
   * queued during setup can still be pending on the harness's own IO dispatcher and land after a
   * later generation's state is already current, "borrowing" that later content. Harmless in
   * production (every send this could produce is a snapshot of genuinely-current state - never
   * stale, never wrong), but it makes an exact per-action send count fragile in a fresh harness
   * within one settle() window. What actually matters for this requirement - the Failed status,
   * message, no real Display error, and every subsequent action's dispatchability - is asserted
   * directly below instead.
   */
  @Test
  fun pureCaptureFailureLeavesCaptureRetryableAndOtherActionsDispatchable() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()

    harness.tapCapture()
    harness.completeCaptureFailure("Photo capture timed out.")

    val failed = harness.stateMachine.captureStatus as ProjectHudCaptureStatus.Failed
    assertEquals("Photo capture timed out.", failed.message)
    assertTrue(harness.displayErrors.isEmpty())

    // "Return without restarting the Project": an unrelated action on the same screen still works
    // - the Failed status never took over the whole HUD.
    harness.tapPhone()
    assertEquals(1, harness.phoneHandoffs.size)

    // "Retry": the SAME Capture button, at the CURRENT generation, works normally again.
    harness.tapCapture()
    assertEquals(ProjectHudCaptureStatus.Capturing, harness.stateMachine.captureStatus)
    harness.completeCaptureSuccess()
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, harness.stateMachine.captureStatus)
    assertEquals(2, harness.captureRequestedCount)
    harness.close()
  }

  @Test
  fun disconnectDuringCaptureResetsToDisconnectedAndClearsCaptureStatus() = runBlocking {
    val harness = ProjectContinuityHudTestHarness(initialOverview = emptyOverview())
    harness.openProject(PROJECT_A, "AC Repair")
    harness.attachDisplay()
    harness.tapCapture()
    assertEquals(ProjectHudCaptureStatus.Capturing, harness.stateMachine.captureStatus)

    harness.disconnect()

    assertTrue(harness.stateMachine.uiState is ProjectHudUiState.Disconnected)
    assertEquals(ProjectHudCaptureStatus.Idle, harness.stateMachine.captureStatus)
    harness.close()
  }

  private fun emptyOverview(): ProjectOverview =
      ProjectOverview(
          project = ProjectSummary(PROJECT_A, "AC Repair", "active"),
          checkpoint = ProjectCheckpoint(whereWeLeftOff = null, nextAction = null),
          recentActivity = emptyList(),
      )

  private fun overviewWithUndecidedReview(): ProjectOverview =
      ProjectOverview(
          project = ProjectSummary(PROJECT_A, "AC Repair", "active"),
          checkpoint = ProjectCheckpoint(whereWeLeftOff = null, nextAction = null),
          recentActivity = emptyList(),
          latestInvestigation =
              SavedInvestigationReview(
                  sessionId = SESSION_ID,
                  projectId = PROJECT_A,
                  status = "completed",
                  completedAtUtc = "2026-09-02T00:00:00Z",
                  evidenceCount = 1,
                  explanation = "Why isn't this blower working?",
                  hypothesis = "Wiring may be reversed.",
                  recommendedNextAction = "Inspect the terminals.",
                  trustDecision = null,
                  proposalId = null,
                  proposalStatus = null,
                  followUpSessionId = null,
                  retainedImage = null,
              ),
      )

  private fun overviewWithDecidedReview(): ProjectOverview =
      overviewWithUndecidedReview().let { it.copy(latestInvestigation = it.latestInvestigation?.copy(trustDecision = "continue")) }

  private companion object {
    const val PROJECT_A = "11111111-1111-1111-1111-111111111111"
    const val SESSION_ID = "session-a"
  }
}
