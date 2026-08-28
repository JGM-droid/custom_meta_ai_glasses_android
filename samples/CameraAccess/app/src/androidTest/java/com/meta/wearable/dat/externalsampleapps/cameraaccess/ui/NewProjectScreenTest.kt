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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests NewProjectScreen's Create Project gating and voice-to-text behavior in isolation: a
 * lightweight createComposeRule() host (no MainActivity/AppRoot navigation) with a fake
 * ProjectRepository standing in for the FastAPI backend and a scriptable
 * FakeSpeechRecognizerController (reused from ProjectWorkspaceScreenTest.kt - same package, same
 * seam) injected through NewProjectScreen's speechControllerFactory test seam.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NewProjectScreenTest {

  // See ProjectWorkspaceScreenTest's identical rule for why this is needed even with a fake
  // speech controller injected: the real RECORD_AUDIO permission flow still runs first.
  @get:Rule(order = 0)
  val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  private val createdProject =
      ProjectSummary(projectId = "backend-assigned-id", name = "Garage Door Sensor", status = "active")

  @Test
  fun createButtonStartsDisabledAndStaysDisabledUntilBothRequiredFieldsAreFilled() {
    setNewProjectContent(SucceedingProjectRepository { createdProject })

    composeTestRule.onNodeWithTag("new_project_create_button").assertIsNotEnabled()

    composeTestRule.onNodeWithTag("new_project_name").performTextInput("Garage Door Sensor")
    composeTestRule.onNodeWithTag("new_project_create_button").assertIsNotEnabled()

    composeTestRule.onNodeWithTag("new_project_goal").performTextInput("Stop it slamming shut")
    composeTestRule.onNodeWithTag("new_project_create_button").assertIsEnabled()
  }

  @Test
  fun whitespaceOnlyRequiredFieldsDoNotEnableTheButton() {
    setNewProjectContent(SucceedingProjectRepository { createdProject })

    composeTestRule.onNodeWithTag("new_project_name").performTextInput("   ")
    composeTestRule.onNodeWithTag("new_project_goal").performTextInput("   ")

    composeTestRule.onNodeWithTag("new_project_create_button").assertIsNotEnabled()
  }

  @Test
  fun successfulCreationInvokesOnCreatedWithTheBackendAssignedProjectId() {
    var created: ProjectSummary? = null
    setNewProjectContent(
        SucceedingProjectRepository { createdProject },
        onCreated = { created = it },
    )

    composeTestRule.onNodeWithTag("new_project_name").performTextInput("Garage Door Sensor")
    composeTestRule.onNodeWithTag("new_project_goal").performTextInput("Stop it slamming shut")
    composeTestRule.onNodeWithTag("new_project_create_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) { created != null }
    assertEquals("backend-assigned-id", created?.projectId)
  }

  @Test
  fun failedCreationShowsErrorAndPreservesTypedTextForRetry() {
    setNewProjectContent(FailingProjectRepository(RuntimeException("Could not reach the backend.")))

    composeTestRule.onNodeWithTag("new_project_name").performTextInput("Garage Door Sensor")
    composeTestRule.onNodeWithTag("new_project_goal").performTextInput("Stop it slamming shut")
    composeTestRule.onNodeWithTag("new_project_create_button").performClick()

    composeTestRule.waitUntilExactlyOneExists(hasTestTag("new_project_error"), timeoutMillis = 5_000L)
    composeTestRule.onNodeWithTag("new_project_name").assertTextContains("Garage Door Sensor", substring = true)
    composeTestRule.onNodeWithTag("new_project_goal").assertTextContains("Stop it slamming shut", substring = true)
    // Retryable, not stuck: the button re-enables once the form is valid again.
    composeTestRule.onNodeWithTag("new_project_create_button").assertIsEnabled()
  }

  @Test
  fun tappingASpecificFieldMicEntersTextOnlyIntoThatFieldAndNowhereElse() {
    val fakeController = FakeSpeechRecognizerController()
    setNewProjectContent(SucceedingProjectRepository { createdProject }, speechController = fakeController)

    composeTestRule.onNodeWithTag("new_project_name_mic").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Garage Door Sensor"))
    }

    composeTestRule.onNodeWithTag("new_project_name").assertTextContains("Garage Door Sensor", substring = true)
    // Must appear exactly once across the whole screen - proving it landed only in Project Name
    // and was not also written into Goal, Current Objective, or Next Action.
    composeTestRule.onAllNodesWithText("Garage Door Sensor", substring = true).assertCountEquals(1)
  }

  @Test
  fun voiceIntoOneFieldNeverEntersAnotherField() {
    val fakeController = FakeSpeechRecognizerController()
    setNewProjectContent(SucceedingProjectRepository { createdProject }, speechController = fakeController)

    composeTestRule.onNodeWithTag("new_project_goal_mic").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Stop it slamming shut."))
    }

    composeTestRule.onNodeWithTag("new_project_goal").assertTextContains("Stop it slamming shut.", substring = true)
    composeTestRule.onAllNodesWithText("Stop it slamming shut.", substring = true).assertCountEquals(1)
  }

  @Test
  fun cancellingVoiceCaptureNeverErasesAlreadyTypedText() {
    val fakeController = FakeSpeechRecognizerController()
    setNewProjectContent(SucceedingProjectRepository { createdProject }, speechController = fakeController)

    composeTestRule.onNodeWithTag("new_project_name").performTextInput("Typed before speaking.")
    composeTestRule.onNodeWithTag("new_project_name_mic").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("new_project_name_mic_cancel"), timeoutMillis = 15_000L)
    composeTestRule.onNodeWithTag("new_project_name_mic_cancel").performClick()

    composeTestRule.onNodeWithTag("new_project_name").assertTextContains("Typed before speaking.", substring = true)
  }

  @Test
  fun failedRecognitionPreservesTypedTextAndStaysRetryable() {
    val fakeController = FakeSpeechRecognizerController()
    setNewProjectContent(SucceedingProjectRepository { createdProject }, speechController = fakeController)

    composeTestRule.onNodeWithTag("new_project_goal").performTextInput("Do not lose this.")
    composeTestRule.onNodeWithTag("new_project_goal_mic").performClick()
    composeTestRule.runOnUiThread { fakeController.completeWith(InvestigationSpeechEvent.NoMatch) }

    composeTestRule.onNodeWithTag("new_project_goal").assertTextContains("Do not lose this.", substring = true)
    composeTestRule.onNodeWithTag("new_project_goal_mic").assertIsEnabled()
  }

  @Test
  fun voiceCompletionNeverTriggersCreateAutomatically() {
    val fakeController = FakeSpeechRecognizerController()
    var created: ProjectSummary? = null
    setNewProjectContent(
        SucceedingProjectRepository { createdProject },
        onCreated = { created = it },
        speechController = fakeController,
    )

    composeTestRule.onNodeWithTag("new_project_name_mic").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Garage Door Sensor"))
    }
    composeTestRule.onNodeWithTag("new_project_goal_mic").performClick()
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Stop it slamming shut."))
    }

    // Both required fields now hold spoken text, but nothing was submitted on the user's behalf.
    assertNull(created)
  }

  @Test
  fun leavingTheScreenWhileListeningCleansUpTheRecognizerAndDropsTheLateTranscript() {
    val fakeController = FakeSpeechRecognizerController()
    var showScreen by mutableStateOf(true)
    var created: ProjectSummary? = null

    composeTestRule.setContent {
      if (showScreen) {
        val vm = remember { NewProjectViewModel(application, SucceedingProjectRepository { createdProject }) }
        NewProjectScreen(
            onBack = {},
            onCreated = { created = it },
            viewModel = vm,
            speechControllerFactory = { fakeController },
        )
      }
    }

    composeTestRule.onNodeWithTag("new_project_name_mic").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("new_project_name_mic_cancel"), timeoutMillis = 15_000L)

    // Simulates navigating away (Back, or AppRoot moving on) while a voice session is still live.
    showScreen = false
    composeTestRule.waitForIdle()

    assertEquals(1, fakeController.destroyCallCount)

    // A transcript that lands after the screen is gone must not surface anywhere or trigger
    // creation - there is nothing left mounted for it to write into.
    composeTestRule.runOnUiThread {
      fakeController.completeWith(InvestigationSpeechEvent.FinalTranscript("Stale transcript."))
    }
    assertNull(created)
  }

  private fun setNewProjectContent(
      repository: ProjectRepository,
      onCreated: (ProjectSummary) -> Unit = {},
      speechController: FakeSpeechRecognizerController = FakeSpeechRecognizerController(),
  ) {
    composeTestRule.setContent {
      val vm = remember { NewProjectViewModel(application, repository) }
      NewProjectScreen(
          onBack = {},
          onCreated = onCreated,
          viewModel = vm,
          speechControllerFactory = { speechController },
      )
    }
  }
}

/** Test-only success stub: reuses MockProjectRepository's other methods via delegation and never
 * hits the network - createProject returns whatever the test supplies. */
private class SucceedingProjectRepository(
    private val onCreate: (NewProjectRequest) -> ProjectSummary,
) : ProjectRepository by MockProjectRepository() {
  override suspend fun createProject(request: NewProjectRequest): ProjectSummary = onCreate(request)
}

/** Test-only failure stub: simulates a backend/network error from createProject. */
private class FailingProjectRepository(
    private val error: Throwable,
) : ProjectRepository by MockProjectRepository() {
  override suspend fun createProject(request: NewProjectRequest): ProjectSummary = throw error
}
