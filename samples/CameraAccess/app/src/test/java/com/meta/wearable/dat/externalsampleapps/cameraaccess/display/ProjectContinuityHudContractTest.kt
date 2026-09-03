/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContinuityHudContractTest {
  private val root = File("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess")

  @Test
  fun controllerIsReadOnlyAndDoesNotOwnDeviceSession() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    assertTrue(controller.contains("repository.getProjectOverview(request.projectId)"))
    assertTrue(controller.contains("fun attachTo(session: DeviceSession)"))
    assertFalse(controller.contains("Wearables.createSession"))
    assertFalse(controller.contains("setActiveProject("))
    assertFalse(controller.contains("applyCheckpointProposal("))
    assertFalse(controller.contains("rejectCheckpointProposal("))
    assertFalse(controller.contains("capturePhoto("))
  }

  @Test
  fun cameraSessionRemainsTheSingleSessionOwner() {
    val stream = File(root, "stream/StreamViewModel.kt").readText()
    assertTrue(stream.contains("session?.let(projectHudController::attachTo)"))
    assertTrue(stream.contains("Wearables.createSession(deviceSelector)"))
    assertFalse(stream.contains("DisplayCapture"))
    assertFalse(stream.contains("captureFromDisplay"))
    assertFalse(stream.contains("CapabilityHandoff"))
    val detachIndex = stream.indexOf("projectHudController.detach()")
    val selectionResetIndex = stream.indexOf("projectHudProjectId = null", startIndex = detachIndex)
    assertTrue(detachIndex >= 0)
    assertTrue(selectionResetIndex > detachIndex)
  }

  @Test
  fun phoneHandoffCarriesProjectAndReviewDestination() {
    val state = File(root, "display/ProjectContinuityHudState.kt").readText()
    val appRoot = File(root, "ui/AppRoot.kt").readText()
    assertTrue(state.contains("val projectId: String"))
    assertTrue(state.contains("PROJECT_REVIEW"))
    assertTrue(appRoot.contains("focusReview = destination == ProjectHudPhoneDestination.PROJECT_REVIEW"))
  }

  /**
   * Proven physical gap: Continue on phone from an active, pre-Analyze investigation (evidence
   * captured, no explanation yet - see ProjectContinuityHudState.kt's phoneHandoff() doc) must
   * carry the caller's OWN continuationSessionId through to the phone landing, so it resolves the
   * SAME investigationViewModelKey instance rather than a fresh, empty one - reusing an existing
   * session id where one is already known, per this milestone's explicit requirement.
   */
  @Test
  fun activeInvestigationHandoffCarriesContinuationSessionIdThroughToThePhoneLanding() {
    val state = File(root, "display/ProjectContinuityHudState.kt").readText()
    val stream = File(root, "ui/StreamScreen.kt").readText()
    val appRoot = File(root, "ui/AppRoot.kt").readText()

    assertTrue(state.contains("ACTIVE_INVESTIGATION"))
    assertTrue(state.contains("analysisEligibility.hasEvidence"))
    assertTrue(stream.contains("onProjectHudPhoneHandoff?.invoke(handoff.destination, continuationSessionId)"))
    assertTrue(appRoot.contains("focusActiveInvestigation = destination == ProjectHudPhoneDestination.ACTIVE_INVESTIGATION"))
    assertTrue(appRoot.contains("activeInvestigationSessionId = continuationSessionId"))
  }

  /**
   * Proven physical gap (second pass): the first ACTIVE_INVESTIGATION fix only scrolled Project
   * Detail to a bare custom card - not the actual "blue tab" investigation panel
   * (BackendInvestigationPanel) the user already knew and expected. This proves ProjectDetailScreen
   * now opens that EXACT SAME component through the EXACT SAME presentation pattern
   * (ModalBottomSheet) and the same reopen-affordance functions StreamScreen's own blue tab already
   * uses - not a second, parallel investigation UI, and not the removed ad hoc reimplementation
   * (bare "CONTINUE YOUR INVESTIGATION" text + a standalone explanation field).
   */
  @Test
  fun activeInvestigationHandoffOpensTheSameBackendInvestigationPanelAsStreamScreensBlueTab() {
    val projectDetail = File(root, "ui/ProjectDetailScreen.kt").readText()
    val stream = File(root, "ui/StreamScreen.kt").readText()

    // Same component, same presentation pattern, same reopen-affordance functions StreamScreen's
    // own blue tab already uses - not reimplemented.
    assertTrue(projectDetail.contains("BackendInvestigationPanel("))
    assertTrue(projectDetail.contains("ModalBottomSheet("))
    assertTrue(projectDetail.contains("hasActiveInvestigation(investigationUiState)"))
    assertTrue(projectDetail.contains("investigationReopenAffordanceLabel(investigationUiState)"))
    assertTrue(stream.contains("BackendInvestigationPanel("))
    assertTrue(stream.contains("ModalBottomSheet("))

    // The removed ad hoc reimplementation is genuinely gone, not just supplemented.
    assertFalse(projectDetail.contains("CONTINUE YOUR INVESTIGATION"))

    // ACTIVE_INVESTIGATION opens it automatically - the proven gap - rather than leaving the user
    // to notice and tap a reopen affordance themselves.
    assertTrue(projectDetail.contains("LaunchedEffect(focusActiveInvestigation)"))
    assertTrue(projectDetail.contains("if (focusActiveInvestigation) isPanelVisible = true"))
  }

  @Test
  fun hudContainsNoForbiddenTechnicalOrCaptureSpikePresentation() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    listOf(
            "interaction_id",
            "Context Pack",
            "checkpoint revision",
            "source_type",
            "API status",
            "Band capture",
            "capturePhoto",
        )
        .forEach { forbidden -> assertFalse("forbidden HUD text: $forbidden", controller.contains(forbidden)) }
  }

  /**
   * The HUD's Capture button (approved MVP requirement - see docs/ROADMAP.md's "DAT 0.8 Capture
   * Capability Gate" status update) must stay a *request*, never a second owner: the controller
   * asks; StreamViewModel - the existing single session/Display owner - performs the capture and
   * reports the outcome back. This is the same shape as onPhoneHandoff/onDisplayError above, and
   * is what keeps cameraSessionRemainsTheSingleSessionOwner and
   * controllerIsReadOnlyAndDoesNotOwnDeviceSession true even with capture wired in.
   */
  @Test
  fun hudCaptureRequestsRouteThroughTheSingleSessionOwnerNotADirectCall() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    val stream = File(root, "stream/StreamViewModel.kt").readText()

    assertTrue(controller.contains("onCaptureRequested: () -> Unit"))
    assertTrue(controller.contains("fun onCaptureSucceeded()"))
    assertTrue(controller.contains("fun onCaptureFailed(message: String)"))
    // Exactly the one pre-existing Display attachment for the HUD itself - never a second one
    // added for capture.
    assertEquals(1, Regex("""\baddDisplay\(""").findAll(controller).count())

    assertTrue(stream.contains("onCaptureRequested = { onHudCaptureRequested() }"))
    assertTrue(stream.contains("projectHudController.onCaptureSucceeded()"))
    assertTrue(stream.contains("projectHudController.onCaptureFailed("))
  }

  /**
   * Same request/report shape for Use and Retake as onCaptureRequested above - the controller
   * never appends Investigation evidence itself; it asks, and the owner (via StreamScreen, the one
   * place StreamViewModel and InvestigationSessionDebugViewModel are both in scope) performs the
   * local append and reports back.
   */
  @Test
  fun hudUseAndRetakeAlsoRouteThroughTheSingleSessionOwnerNotADirectAppend() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    val stream = File(root, "stream/StreamViewModel.kt").readText()
    val screen = File(root, "ui/StreamScreen.kt").readText()

    assertTrue(controller.contains("onUseRequested: () -> Unit"))
    assertTrue(controller.contains("onRetakeRequested: () -> Unit"))
    assertTrue(controller.contains("fun onCaptureAccepted()"))
    assertFalse(controller.contains("appendLiveEvidence("))

    assertTrue(stream.contains("onUseRequested = { onHudUseRequested() }"))
    assertTrue(stream.contains("onRetakeRequested = { onHudRetakeRequested() }"))
    // StreamViewModel stages the request; it never calls appendLiveEvidence itself either - only
    // the Compose layer below, which actually holds a reference to the Investigation ViewModel.
    assertFalse(stream.contains("appendLiveEvidence("))

    assertTrue(screen.contains("investigationViewModel.appendLiveEvidence(evidence)"))
    assertTrue(screen.contains("streamViewModel.onHudCaptureAccepted("))
  }

  /**
   * Proven bug (physical logcat + DAT HeartbeatMonitor/DisplaySession logs): a transient DAT
   * Display sendContent() failure during capture left the HUD screen permanently stale. The fix
   * (renderCurrentStateWithOneRetry/retryOnceThenReport) resyncs the DISPLAY only - it must never
   * re-invoke the capture itself, or a Display hiccup would silently trigger a second real photo
   * capture the user never asked for.
   */
  @Test
  fun renderRetryNeverReinvokesCapture() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    val start = controller.indexOf("private suspend fun renderCurrentStateWithOneRetry")
    val end = controller.indexOf("private suspend fun sendCurrentState")
    assertTrue(start >= 0)
    assertTrue(end > start)
    val renderRetryBody = controller.substring(start, end)

    assertFalse(renderRetryBody.contains("onCaptureRequested"))
    assertFalse(renderRetryBody.contains("onHudCaptureRequested"))
    assertFalse(renderRetryBody.contains("capturePhoto("))
  }

  /**
   * Same request/report shape as Capture and Use/Retake for Analyze and the three trust actions -
   * the controller never runs an analysis or submits a trust decision itself; it asks, and the
   * owner (via StreamScreen) performs the existing Investigation analyze/trust-decision calls and
   * reports back. Also: no second Display attachment was added for this milestone either.
   */
  @Test
  fun hudAnalyzeAndTrustDecisionsRouteThroughTheSingleSessionOwnerNotADirectCall() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    val stream = File(root, "stream/StreamViewModel.kt").readText()
    val screen = File(root, "ui/StreamScreen.kt").readText()

    assertTrue(controller.contains("onAnalyzeRequested: () -> Unit"))
    assertTrue(controller.contains("onTrustDecisionRequested: (ProjectHudTrustAction, sessionId: String) -> Unit"))
    assertTrue(controller.contains("fun onAnalysisSucceeded()"))
    assertTrue(controller.contains("fun onAnalysisFailed(message: String)"))
    assertFalse(controller.contains("submitInvestigation("))
    assertFalse(controller.contains("submitTrustDecision("))
    // Still exactly the one pre-existing Display attachment.
    assertEquals(1, Regex("""\baddDisplay\(""").findAll(controller).count())

    assertTrue(stream.contains("onAnalyzeRequested = { onHudAnalyzeRequested() }"))
    assertTrue(stream.contains("onTrustDecisionRequested = { action, sessionId -> onHudTrustDecisionRequested(action, sessionId) }"))
    // StreamViewModel stages the requests; it never calls the Investigation APIs itself either -
    // only the Compose layer below, which actually holds a reference to that ViewModel.
    assertFalse(stream.contains("submitInvestigation("))
    assertFalse(stream.contains("submitTrustDecision("))

    assertTrue(screen.contains("investigationViewModel.submitInvestigation()"))
    assertTrue(screen.contains("investigationViewModel.submitTrustDecision(decision, request.sessionId)"))
    assertTrue(screen.contains("streamViewModel.onHudAnalyzeCompleted("))
    assertTrue(screen.contains("streamViewModel.onHudTrustDecisionCompleted("))
  }

  /**
   * The concise AI result the roadmap requires must come from the SAME canonical Project data
   * every other HUD screen reads from - never a second, HUD-local copy of the Investigation
   * result. Confirmed by mapOverview deriving pendingTrustReview from the existing
   * latestInvestigation field, and by the controller refreshing the canonical Project (not
   * fabricating content) once an Analyze/trust decision settles.
   */
  /**
   * Glasses UX hardening pass: the three trust-decision buttons, the AI-result framing, the
   * capture-failure message, and the Analyze button must all read as plain human language rather
   * than engineering shorthand - but only the LABELS/copy changed. This is a source-text check
   * (like [hudContainsNoForbiddenTechnicalOrCaptureSpikePresentation] above) rather than a
   * rendered-content assertion because the real DAT ContentScope's tree is not introspectable
   * outside the SDK (see ProjectContinuityHudTestHarness's class doc) - the harness's
   * pureCaptureFailureLeavesCaptureRetryableAndOtherActionsDispatchable test is what actually
   * exercises the recovery BEHAVIOR this wording sits on top of.
   */
  @Test
  fun hudTrustAndAnalyzeLabelsAreHumanFacingWithInternalSemanticsUnchanged() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()

    // New human-facing trust-action labels, each still routed to its unchanged internal action.
    assertTrue(controller.contains("button(\"Looks right\", onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.KEEP_AS_HYPOTHESIS) })"))
    assertTrue(controller.contains("button(\"Add more info\", onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.ADD_EVIDENCE) })"))
    assertTrue(controller.contains("button(\"Go back\", style = ButtonStyle.SECONDARY, onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.RETURN) })"))
    // The old engineering-facing labels are gone as button text specifically (not just anywhere
    // in the file, since a doc comment is allowed to mention the old wording in prose).
    assertFalse(controller.contains("button(\"Keep as hypothesis\""))
    assertFalse(controller.contains("button(\"Add evidence\""))
    assertFalse(controller.contains("button(\"Return\""))

    // AI-result framing stays clearly unconfirmed, just less like a system label.
    assertTrue(controller.contains("NOT CONFIRMED YET"))

    // Contextual Analyze wording - never a bare generic "Analyze" button once eligible.
    assertTrue(controller.contains("fun analyzeButtonLabel(hasPriorSuggestion: Boolean): String ="))
    assertTrue(controller.contains("\"Analyze project\""))
    assertTrue(controller.contains("\"Update suggestion\""))
    assertFalse(controller.contains("button(\"Analyze\","))

    // Capture-failure message is clearly human, not a bare technical prefix.
    assertTrue(controller.contains("Couldn't capture that photo:"))

    // Every analysisRow call site wires the REAL latestGuidance signal, not a stray hardcoded
    // value - this is what actually makes the contextual label correct, not just present.
    assertEquals(3, Regex("""hasPriorSuggestion = content\.latestGuidance != null""").findAll(controller).count())
    assertEquals(1, Regex("""hasPriorSuggestion = state\.content\.latestGuidance != null""").findAll(controller).count())
  }

  @Test
  fun pendingTrustReviewComesFromTheSameCanonicalProjectOverviewAsEverythingElse() {
    val state = File(root, "display/ProjectContinuityHudState.kt").readText()
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()

    assertTrue(state.contains("val investigation = overview.latestInvestigation"))
    assertTrue(state.contains("investigation?.takeIf { it.trustDecision == null }"))
    assertTrue(controller.contains("stateMachine.refresh()"))
  }
}
