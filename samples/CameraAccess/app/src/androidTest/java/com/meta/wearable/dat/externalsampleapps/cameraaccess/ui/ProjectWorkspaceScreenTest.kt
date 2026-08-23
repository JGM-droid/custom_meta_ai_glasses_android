/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the Workspace composer's voice-to-text behavior in isolation: a lightweight
 * createComposeRule() host (no MainActivity/AppRoot navigation, no real backend) with
 * MockProjectRepository standing in for the FastAPI backend and a scriptable
 * FakeSpeechRecognizerController injected through ProjectWorkspaceScreen's
 * speechControllerFactory test seam - real on-device SpeechRecognizer output cannot be reliably
 * driven from an automated test, which is exactly the problem that seam exists to solve.
 *
 * Ask Project correctness and Capture/Test Glasses reachability from a REAL Workspace (against
 * the real backend, through the full app) already have dedicated live coverage in
 * AppRootTest.kt; this file only proves the mic/composer's own behavior - listening state,
 * transcript merge, editability, no-auto-submit, error recovery, and cleanup - which is
 * orthogonal to backend correctness and is unaffected by which repository/backend is behind it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectWorkspaceScreenTest {

  // ProjectWorkspaceScreen's mic button always goes through the real
  // ActivityResultContracts.RequestPermission() flow first (see file header on
  // ProjectWorkspaceScreen.kt) - even with a fake speech controller injected, that permission
  // check still runs for real. createComposeRule() hosts content in a minimal Activity registered
  // under THIS test package (see src/androidTest/AndroidManifest.xml), not the main app's, so it
  // needs its OWN RECORD_AUDIO grant (see src/androidTest/AndroidManifest.xml) - independent of
  // whatever AppRootTest already grants to the real app package. GrantPermissionRule (not a
  // manual `pm grant` shell-out) grants it via UiAutomation.grantRuntimePermission() and is
  // ordered to run BEFORE composeTestRule launches any content, which is what actually avoids the
  // race a manual shell command has: launching the mic flow before an asynchronous grant has
  // propagated shows a REAL system permission dialog that nothing here dismisses, which then
  // breaks not just that test but every subsequent test in the same instrumentation run (the
  // dialog's Activity is left on top of the stack).
  @get:Rule(order = 0)
  val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  private val projectA =
      ProjectSummary(projectId = "upstairs-ac-repair", name = "Upstairs AC Repair", status = "active")
  private val projectB =
      ProjectSummary(projectId = "custom-meta-ai-glasses", name = "Custom Meta AI Glasses", status = "active")

  @Test
  fun micButtonIsEnabledInitially() {
    setWorkspaceContent(projectA, FakeSpeechRecognizerController())

    composeTestRule.onNodeWithTag("workspace_mic_button").assertIsEnabled()
  }

  @Test
  fun tappingMicEntersListeningStateThenTranscriptFillsEmptyComposer() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("Listening..."), timeoutMillis = 15_000L)
    // Can't start a second session while already listening.
    composeTestRule.onNodeWithTag("workspace_mic_button").assertIsNotEnabled()

    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Check voltage at the contactor."))
    }

    composeTestRule
        .onNodeWithTag("workspace_composer_input")
        .assertTextContains("Check voltage at the contactor.", substring = true)
    composeTestRule.onNodeWithTag("workspace_mic_button").assertIsEnabled()
  }

  @Test
  fun transcriptionAppendsToExistingDraftRatherThanReplacingIt() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_composer_input").performTextInput("Additional context:")
    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("The capacitor was replaced yesterday."))
    }

    val composer = composeTestRule.onNodeWithTag("workspace_composer_input")
    composer.assertTextContains("Additional context:", substring = true)
    composer.assertTextContains("The capacitor was replaced yesterday.", substring = true)
  }

  @Test
  fun transcriptionRemainsEditableAfterArriving() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Check voltage."))
    }
    composeTestRule.onNodeWithTag("workspace_composer_input").assertTextContains("Check voltage.", substring = true)

    // Proves the transcribed text is a normal, fully-editable value - not locked/read-only - by
    // correcting it, exactly how a user would fix a misheard word. (Deliberately a replacement,
    // not an appended performTextInput: where the cursor lands after an externally-set field
    // value is a Compose TextField implementation detail, not something this test should depend
    // on to prove editability.)
    composeTestRule.onNodeWithTag("workspace_composer_input").performTextReplacement("Check voltage at the contactor.")

    composeTestRule
        .onNodeWithTag("workspace_composer_input")
        .assertTextContains("Check voltage at the contactor.", substring = true)
  }

  @Test
  fun voiceCompletionNeverTriggersAskAutomatically() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Check voltage at the contactor."))
    }

    // The Ask button must still read its idle label (never a spinner) and no answer card can
    // exist - proving no submission happened on the user's behalf.
    composeTestRule.onNodeWithText("Ask Project").assertExists()
    composeTestRule.onNodeWithTag("workspace_ask_answer").assertDoesNotExist()
  }

  @Test
  fun failedRecognitionPreservesExistingComposerTextAndStaysRetryable() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_composer_input").performTextInput("Do not lose this.")
    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread { fakeController.completeWith(InvestigationSpeechEvent.NoMatch) }

    composeTestRule.onNodeWithTag("workspace_composer_input").assertTextContains("Do not lose this.", substring = true)
    composeTestRule.onNodeWithTag("workspace_mic_button").assertIsEnabled()
  }

  @Test
  fun repeatedVoiceSessionsWork() {
    val fakeController = FakeSpeechRecognizerController()
    setWorkspaceContent(projectA, fakeController)

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread { fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("First.")) }
    composeTestRule.onNodeWithTag("workspace_composer_input").assertTextContains("First.", substring = true)

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread { fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Second.")) }
    composeTestRule.onNodeWithTag("workspace_composer_input").assertTextContains("Second.", substring = true)

    assertEquals(2, fakeController.startListeningCallCount)
  }

  @Test
  fun leavingWorkspaceWhileListeningCleansUpTheRecognizer() {
    val fakeController = FakeSpeechRecognizerController()
    var showWorkspace by mutableStateOf(true)

    composeTestRule.setContent {
      if (showWorkspace) {
        val vm =
            remember {
              ProjectDetailViewModel(application = application, projectId = projectA.projectId, repository = MockProjectRepository())
            }
        ProjectWorkspaceScreen(
            project = projectA,
            onBack = {},
            onOpenCapture = {},
            viewModel = vm,
            speechControllerFactory = { fakeController },
        )
      }
    }

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("Listening..."), timeoutMillis = 15_000L)

    // Simulates navigating away entirely (AppRoot removing this screen from composition), not
    // just a project switch - the composable itself leaves composition.
    showWorkspace = false
    composeTestRule.waitForIdle()

    assertEquals(1, fakeController.destroyCallCount)
  }

  @Test
  fun projectSwitchDoesNotLeakTranscriptAndCleansUpThePreviousControllerSession() {
    val fakeControllerA = FakeSpeechRecognizerController()
    val fakeControllerB = FakeSpeechRecognizerController()
    var currentProject by mutableStateOf(projectA)

    composeTestRule.setContent {
      val vm =
          remember(currentProject.projectId) {
            ProjectDetailViewModel(application = application, projectId = currentProject.projectId, repository = MockProjectRepository())
          }
      ProjectWorkspaceScreen(
          project = currentProject,
          onBack = {},
          onOpenCapture = {},
          viewModel = vm,
          speechControllerFactory = { if (currentProject.projectId == projectA.projectId) fakeControllerA else fakeControllerB },
      )
    }

    composeTestRule.onNodeWithTag("workspace_mic_button").performClick()
    composeTestRule.runOnUiThread {
      fakeControllerA.completeWith(InvestigationSpeechEvent.FinalTranscript("Check voltage at contactor."))
    }
    composeTestRule
        .onNodeWithTag("workspace_composer_input")
        .assertTextContains("Check voltage at contactor.", substring = true)

    currentProject = projectB

    // A fresh composer for B - never A's transcript, and A's now-stale controller was cleaned up
    // (draftText/speechController are both remember(project.projectId)-scoped - see file header).
    composeTestRule.onNodeWithText("Ask your Project anything...").assertExists()
    composeTestRule.onNodeWithText("Check voltage at contactor.", substring = true).assertDoesNotExist()
    assertEquals(1, fakeControllerA.destroyCallCount)
  }

  private fun setWorkspaceContent(project: ProjectSummary, speechController: FakeSpeechRecognizerController) {
    composeTestRule.setContent {
      val vm =
          remember {
            ProjectDetailViewModel(application = application, projectId = project.projectId, repository = MockProjectRepository())
          }
      ProjectWorkspaceScreen(
          project = project,
          onBack = {},
          onOpenCapture = {},
          viewModel = vm,
          speechControllerFactory = { speechController },
      )
    }
  }
}

/**
 * Scriptable stand-in for the real on-device SpeechRecognizer wrapper. startListening() fires
 * StartRequested+Listening synchronously (so a test can observe the listening state) and then
 * waits for the test to explicitly call completeWith(...) - decoupling "session started" from
 * "result arrived" the way real (asynchronous) speech recognition does, without needing a real
 * recognizer or any timing/threading complexity in tests.
 */
internal class FakeSpeechRecognizerController : InvestigationSpeechRecognizerController {
  private var pendingCallback: ((InvestigationSpeechEvent) -> Unit)? = null

  var startListeningCallCount = 0
    private set

  var cancelCallCount = 0
    private set

  var destroyCallCount = 0
    private set

  override fun startListening(onEvent: (InvestigationSpeechEvent) -> Unit): Boolean {
    startListeningCallCount++
    pendingCallback = onEvent
    onEvent(InvestigationSpeechEvent.StartRequested)
    onEvent(InvestigationSpeechEvent.Listening)
    return true
  }

  override fun cancel() {
    cancelCallCount++
    pendingCallback?.invoke(InvestigationSpeechEvent.Cancelled)
    pendingCallback = null
  }

  override fun destroy() {
    destroyCallCount++
    pendingCallback = null
  }

  /** Test-only: simulates the recognizer finishing with the given terminal event. */
  fun completeWith(event: InvestigationSpeechEvent) {
    pendingCallback?.invoke(event)
    pendingCallback = null
  }
}
