/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Drives a REAL [ProjectContinuityHudController]/[ProjectContinuityHudStateMachine] - the exact
 * production classes, unmodified in behavior - against a [FakeDisplay] and [FakeProjectRepository]
 * instead of physical hardware and a live backend. Never calls a live backend service and never
 * mutates a real Project ([FakeProjectRepository] is purely in-memory).
 *
 * What this harness deliberately does NOT do: construct `StreamViewModel`/`StreamScreen`/
 * `InvestigationSessionDebugViewModel`. Those extend `AndroidViewModel`/are Compose functions -
 * Android-framework-coupled types this plain-JVM test module has no way to instantiate (no
 * Robolectric dependency, and adding one is exactly the kind of second production/test
 * architecture this harness is scoped to avoid). Instead, the `on*Requested` callbacks a real
 * button tap would eventually reach StreamViewModel through are recorded here directly - proving
 * the controller asked the right thing, with the right arguments, at the right point - and the
 * harness's `complete*` methods let a test script the outcome StreamViewModel would eventually
 * report back (a successful capture, a Display timeout, a failed trust decision, ...) without
 * needing that real ViewModel to produce it.
 *
 * Dispatch happens through the controller's own internal dispatch* functions (widened to
 * `internal` only for this reason - see their doc) - the exact functions a real button's onClick
 * closure calls, always at the state machine's own current [ProjectContinuityHudStateMachine.renderGeneration],
 * so stale/duplicate-tap protection is exercised for real, not assumed.
 */
internal class ProjectContinuityHudTestHarness(initialOverview: ProjectOverview) : AutoCloseable {
  // Deliberately NOT the calling test's own runBlocking scope: the controller's Display.state
  // collector (attachDisplay() below) runs for as long as this scope is alive, by design - the
  // same as production's viewModelScope, which stays alive until the ViewModel is cleared. Using
  // the test's own runBlocking scope here would mean runBlocking could never return, since it
  // waits for every child job to finish and this one never does on its own. close() cancels it.
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

  val repository = FakeProjectRepository(initialOverview)
  val display = FakeDisplay(initialState = DisplayState.STARTED)

  val phoneHandoffs = mutableListOf<ProjectHudPhoneHandoff>()
  val displayErrors = mutableListOf<String>()
  var captureRequestedCount = 0
    private set
  var useRequestedCount = 0
    private set
  var retakeRequestedCount = 0
    private set
  var analyzeRequestedCount = 0
    private set
  val trustDecisionRequests = mutableListOf<Pair<ProjectHudTrustAction, String>>()

  val controller =
      ProjectContinuityHudController(
          scope = scope,
          repository = repository,
          onPhoneHandoff = { phoneHandoffs.add(it) },
          onDisplayError = { displayErrors.add(it) },
          onCaptureRequested = { captureRequestedCount++ },
          onUseRequested = { useRequestedCount++ },
          onRetakeRequested = { retakeRequestedCount++ },
          onAnalyzeRequested = { analyzeRequestedCount++ },
          onTrustDecisionRequested = { action, sessionId -> trustDecisionRequests.add(action to sessionId) },
      )

  /** The real state machine's own current snapshot - the same one production rendering reads. */
  val stateMachine: ProjectContinuityHudStateMachine
    get() = controller.stateMachine

  suspend fun openProject(projectId: String, projectName: String) {
    controller.selectProject(projectId, projectName)
    settle()
  }

  /** Attaches [display], already STARTED by default - mirrors the glasses stream reaching STARTED. */
  suspend fun attachDisplay() {
    controller.attachDisplayForTesting(display)
    settle()
  }

  suspend fun disconnect() {
    display.setState(DisplayState.STOPPED)
    settle()
  }

  suspend fun tapCapture() {
    controller.dispatchCapture(stateMachine.renderGeneration)
    settle()
  }

  suspend fun tapUse() {
    controller.dispatchUse(stateMachine.renderGeneration)
    settle()
  }

  suspend fun tapRetake() {
    controller.dispatchRetake(stateMachine.renderGeneration)
    settle()
  }

  suspend fun tapRefresh() {
    controller.dispatchRefresh(stateMachine.renderGeneration)
    settle()
  }

  suspend fun tapAnalyze() {
    controller.dispatchAnalyze(stateMachine.renderGeneration)
    settle()
  }

  suspend fun tapTrustDecision(action: ProjectHudTrustAction) {
    controller.dispatchTrustDecision(stateMachine.renderGeneration, action)
    settle()
  }

  /** "Continue on phone" - records the handoff into [phoneHandoffs], same as a real tap. */
  suspend fun tapPhone() {
    controller.dispatchPhone(stateMachine.renderGeneration)
    settle()
  }

  // Below: stand in for what StreamViewModel would eventually report back to the controller -
  // see class doc. Each is exactly the call StreamViewModel itself makes in production.

  suspend fun completeCaptureSuccess() {
    controller.onCaptureSucceeded()
    settle()
  }

  suspend fun completeCaptureFailure(message: String) {
    controller.onCaptureFailed(message)
    settle()
  }

  suspend fun completeUseAccepted() {
    controller.onCaptureAccepted()
    settle()
  }

  suspend fun completeUseFailed(message: String) {
    controller.onCaptureFailed(message)
    settle()
  }

  suspend fun completeAnalysisSucceeded() {
    controller.onAnalysisSucceeded()
    settle()
  }

  suspend fun completeAnalysisFailed(message: String) {
    controller.onAnalysisFailed(message)
    settle()
  }

  fun pushAnalysisEligibility(eligibility: ProjectHudAnalysisEligibility) {
    controller.onAnalysisEligibilityChanged(eligibility)
  }

  /**
   * Lets render()/load() coroutines the controller launched on [scope] actually run before a test
   * asserts. 50ms is generous for this harness's purely in-memory fakes; only scenarios exercising
   * the controller's real bounded-retry delays (render-send failure, canonical-load failure - both
   * hardcoded production constants, not test-injectable, by design: they exist to be real) need a
   * longer explicit wait - see those tests' own settle() calls.
   */
  suspend fun settle(millis: Long = 200) {
    delay(millis)
  }

  /** Stops this harness's own scope - see its doc. Use `.use { harness -> ... }` in a test. */
  override fun close() {
    scope.cancel()
  }
}
