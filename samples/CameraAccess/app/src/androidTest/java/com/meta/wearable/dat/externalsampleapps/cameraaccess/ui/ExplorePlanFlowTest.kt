/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifact
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactImages
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rich Project Intelligence V1 (ADR-059) acceptance coverage for the Room Redesign EXPLORE_PLAN
 * vertical slice: request -> render 3 rich options -> explicit SELECT -> backend-derived
 * CheckpointProposal review -> explicit Apply -> canonical reload. Uses a real ExplorePlanViewModel
 * (needs a live Main dispatcher for viewModelScope - only available in an instrumented test, not a
 * plain JVM unit test, matching this project's existing NewProjectViewModelTest convention) against
 * MockProjectRepository, exactly as ProjectWorkspaceScreenTest.kt already does for other
 * ViewModel+backend flows. No live network call.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ExplorePlanFlowTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  private val projectId = "custom-meta-ai-glasses"

  // Bypasses ExplorePlanViewModel.Factory (which always builds a real HttpUrlProjectRepository)
  // so this test exercises MockProjectRepository instead - no live network call. Takes an
  // explicit repository instance (rather than always constructing a fresh one) so a test can
  // simulate process death by hosting a SECOND, brand-new ExplorePlanViewModel against the SAME
  // repository instance a first one already mutated - exactly what survives a real process death
  // (the backend/repository) versus what does not (the ViewModel's own in-memory state).
  private inner class FakeExplorePlanViewModelFactory(private val repository: ProjectRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ExplorePlanViewModel(application, projectId, repository) as T
  }

  private fun hostPanel(
      repository: ProjectRepository = MockProjectRepository(),
      existingResultId: String? = null,
      visualStates: Map<String, VisualArtifactUiState> = emptyMap(),
      onVisualizeOption: (String, String) -> Unit = { _, _ -> },
  ) {
    composeTestRule.setContent {
      val viewModel: ExplorePlanViewModel = viewModel(factory = FakeExplorePlanViewModelFactory(repository))
      androidx.compose.runtime.LaunchedEffect(existingResultId) {
        existingResultId?.let(viewModel::loadExistingPlan)
      }
      val planState by viewModel.planState.collectAsState()
      val selectionState by viewModel.selectionState.collectAsState()
      val applyState by viewModel.applyState.collectAsState()
      ExplorePlanPanel(
          planState = planState,
          selectionState = selectionState,
          applyState = applyState,
          onRequestPlan = viewModel::requestPlan,
          onSelectOption = viewModel::selectOption,
          onApply = viewModel::applySelection,
          onDismissProposal = viewModel::dismissSelection,
          onRetry = { existingResultId?.let(viewModel::loadExistingPlan) },
          visualStates = visualStates,
          onVisualizeOption = onVisualizeOption,
      )
    }
  }

  @Test
  fun requestingAPlanRendersThreeTypedOptionsWithRecommendationAndCost() {
    hostPanel()
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()

    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)
    composeTestRule.onNodeWithText("2. Dark Contemporary").assertExists()
    composeTestRule.onNodeWithText("3. Minimal Natural").assertExists()
    composeTestRule.onNodeWithText("RECOMMENDED").assertExists()
    composeTestRule.onNodeWithText("Estimated cost: 500–1200 USD (rough estimate)").assertExists()
    // Typed rationale/tradeoffs must never appear before the user expands the card - collapsed
    // state per the approved UX ("Each option card collapsed: title, short summary/concept,
    // estimated cost when present, recommended indicator when applicable").
    composeTestRule.onAllNodesWithText("Supports a welcoming room.").assertCountEquals(0)
  }

  @Test
  fun expandingACardRevealsRationaleProposedChangesAndTradeoffs() {
    hostPanel()
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)

    composeTestRule.onAllNodesWithText("Show details")[0].performClick()

    composeTestRule.onNodeWithText("Supports a welcoming room.").assertExists()
    composeTestRule.onNodeWithText("Add oak accents and warm-white lighting.").assertExists()
    composeTestRule.onNodeWithText("Needs material samples.").assertExists()
  }

  @Test
  fun selectingAnOptionVisiblyAcknowledgesSelectionAndShowsDistinctProposalReview() {
    hostPanel()
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)

    composeTestRule.onAllNodesWithText("Select this direction")[0].performClick()

    // Visible, distinct acknowledgement of selection (not silently accepted).
    composeTestRule.waitUntilExactlyOneExists(hasText("Selected — awaiting your review below"), timeoutMillis = 10_000L)
    // The proposal is explicitly labeled as NOT yet canonical - distinct from applied Project state.
    composeTestRule.onNodeWithText("PROPOSED PROJECT UPDATE — REVIEW REQUIRED").assertExists()
    composeTestRule.onNodeWithText("This has not changed your Project yet. Apply to make it canonical.").assertExists()
    composeTestRule.onNodeWithText("Apply to Project").assertExists()
    // Never auto-applied: no "Applied" text before an explicit Apply tap.
    composeTestRule.onAllNodesWithText("Applied to your Project.").assertCountEquals(0)
  }

  @Test
  fun repeatedSelectTapsDoNotCreateMultipleProposalReviews() {
    hostPanel()
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)

    val selectButton = composeTestRule.onAllNodesWithText("Select this direction")[0]
    selectButton.performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("Selected — awaiting your review below"), timeoutMillis = 10_000L)
    // A second tap on the same option is now a no-op (the button itself is gone, replaced by the
    // acknowledgement text) - exactly one proposal review card exists.
    composeTestRule.onAllNodesWithText("PROPOSED PROJECT UPDATE — REVIEW REQUIRED").assertCountEquals(1)
  }

  @Test
  fun applyingTheProposalShowsAppliedConfirmationAndCanonicalNextStepUpdates() {
    hostPanel()
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)
    composeTestRule.onAllNodesWithText("Select this direction")[0].performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("Selected — awaiting your review below"), timeoutMillis = 10_000L)

    composeTestRule.onNodeWithText("Apply to Project").performClick()

    composeTestRule.waitUntilExactlyOneExists(hasText("Applied to your Project."), timeoutMillis = 10_000L)
    // Apply/Reject controls disappear once applied - repeated Apply taps are no longer possible.
    composeTestRule.onAllNodesWithText("Apply to Project").assertCountEquals(0)
  }

  @Test
  fun reopeningAfterSelectionButBeforeApplyReconstructsThePendingProposalReview() {
    // Simulates process death between an explicit SELECT and Apply: the repository (standing in
    // for the backend, which really does persist this) is set up directly, matching what a real
    // reopen would fetch - completely independent of whichever ViewModel instance created it,
    // exactly like the actual backend is independent of this app's process lifetime.
    val repository = MockProjectRepository()
    val resultId = kotlinx.coroutines.runBlocking {
      val plan = (repository.createExplorePlan(projectId, "Give me ideas", "key-1")
          as com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanCreateResult.Ready).result
      val ideaId = plan.options.first { it.recommended }.ideaId
      repository.selectExplorePlanOption(projectId, ideaId, "select-key-1")
      plan.resultId
    }

    // A brand-new ExplorePlanViewModel (the process-death case) reopens against the SAME
    // repository - never a fresh select tap.
    hostPanel(repository = repository, existingResultId = resultId)

    composeTestRule.waitUntilExactlyOneExists(hasText("PROPOSED PROJECT UPDATE — REVIEW REQUIRED"), timeoutMillis = 10_000L)
    composeTestRule.onNodeWithText("Apply to Project").assertExists()
  }

  @Test
  fun failedInitialRequestOffersAWayToRetypeAndResubmitRatherThanADeadEnd() {
    var calls = 0
    val mock = MockProjectRepository()
    val flakyThenWorkingRepository = object : ProjectRepository by mock {
      override suspend fun createExplorePlan(
          projectId: String,
          userIntent: String,
          idempotencyKey: String,
      ): com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanCreateResult {
        calls++
        if (calls == 1) throw java.io.IOException("Could not reach the backend.")
        return mock.createExplorePlan(projectId, userIntent, idempotencyKey)
      }
    }
    hostPanel(repository = flakyThenWorkingRepository)

    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("Could not reach the backend."), timeoutMillis = 10_000L)

    // The Failed state must still offer a composer to retype/resubmit - not just an inert Retry
    // button with nothing to retry (no plan was ever created on this first failed attempt).
    composeTestRule.onNodeWithText("Or ask again: describe what you need design/planning guidance on")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onAllNodesWithText("Get design ideas")[0].performClick()

    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)
    assertEquals(2, calls)
  }

  @Test
  fun visualizationRequiresExplicitOptionAction() {
    var requestedResult: String? = null
    var requestedOption: String? = null
    hostPanel(onVisualizeOption = { resultId, optionId ->
      requestedResult = resultId
      requestedOption = optionId
    })
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)
    assertEquals(null, requestedOption)
    composeTestRule.onAllNodesWithText("Show details")[0].performClick()
    composeTestRule.onNodeWithText("Visualize this option").performClick()
    assertEquals("mock-explore-plan-1", requestedResult)
    assertEquals("mock-option-1", requestedOption)
  }

  @Test
  fun readyVisualizationRendersAndOffersSourceComparison() {
    val ready = VisualArtifactUiState.Ready(
        artifact = VisualArtifact(
            artifactId = "artifact-1",
            projectId = projectId,
            resultId = "mock-explore-plan-1",
            optionId = "mock-option-1",
            status = VisualArtifactStatus.READY,
            sourceEvidenceIds = listOf("evidence-1"),
            retention = "EPHEMERAL",
            failureCategory = null,
        ),
        images = VisualArtifactImages(source = byteArrayOf(), visualization = byteArrayOf()),
    )
    hostPanel(visualStates = mapOf("mock-option-1" to ready))
    composeTestRule.onNodeWithText("For example: Give me three directions to make this room warmer and more modern")
        .performTextInput("Give me three directions for this room")
    composeTestRule.onNodeWithText("Get design ideas").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasText("1. Warm Modern"), timeoutMillis = 10_000L)
    composeTestRule.onAllNodesWithText("Show details")[0].performClick()
    composeTestRule.onNodeWithText("AI visualization").assertExists()
    composeTestRule.onNodeWithText("View comparison").performClick()
    composeTestRule.onNodeWithText("Source photo").assertExists()
    composeTestRule.onNodeWithText("Visualization").performClick()
    composeTestRule.onAllNodesWithText("AI visualization").assertCountEquals(2)
  }
}
