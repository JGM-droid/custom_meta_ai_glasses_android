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
    assertTrue(appRoot.contains("focusReview = needsReview"))
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
}
