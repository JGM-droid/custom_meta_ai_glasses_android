/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.FakeInvestigationSessionApi
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-060: the unified "Get guidance" natural flow, replacing the removed explicit
 * "Or explore design/planning ideas instead" mode-selector button. Reproduces the exact physical
 * sequence Continue-on-phone already establishes (see ActiveInvestigationHandoffTest.kt) -
 * evidence already present in the SAME investigationViewModelKey-resolved
 * InvestigationSessionDebugViewModel StreamScreen's HUD-driven Capture/Use would have populated -
 * then proves the SAME single action routes to whichever result the backend's Response Planner
 * selects, never a user-picked mode.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectGuidanceFlowTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  // A fresh, uniquely-identified project per test method (rather than one shared fixed id) so
  // no two tests in this class can ever collide on the SAME investigationViewModelKey/ViewModel
  // Store entry - guidance/session state genuinely starts empty every time, matching what a real
  // Continue-on-phone landing on a never-before-seen Project would see.
  private lateinit var project: ProjectSummary

  private fun freshProject(repository: MockProjectRepository, name: String = "Room Redesign"): ProjectSummary =
      kotlinx.coroutines.runBlocking {
        repository.createProject(
            com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest(
                name = "$name-${System.nanoTime()}",
                goal = "Make the room warmer and modern",
            ),
        )
      }

  /**
   * ModalBottomSheet reports its content with permanently zero-size boundsInRoot when hosted bare
   * via createComposeRule() (no real Activity/Scaffold window) - the same quirk
   * ContinueOnPhoneReachesExplorePlanTest.kt (deleted; superseded by this file) and AppRootTest.kt
   * already document for sheet-hosted buttons. A coordinate-based performClick() therefore taps
   * nowhere and silently no-ops - proven on a real device to be genuinely clickable - so every
   * click on a node inside BackendInvestigationPanel's sheet must invoke the semantics click
   * action directly instead of simulating a screen tap.
   */
  private fun SemanticsNodeInteraction.clickInSheet() {
    performSemanticsAction(SemanticsActions.OnClick) { it() }
  }

  // --- Test 1: natural EXPLORE_PLAN flow, no mode-selection button ---

  @Test
  fun roomRedesignNaturalFlowReachesExplorePlanWithoutAnyModeSelectionButton() {
    val repository = MockProjectRepository()
    setProjectDetailContent(repository = repository)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    // No mode-selection button of any kind exists anymore.
    composeTestRule.onNodeWithText("Or explore design/planning ideas instead").assertDoesNotExist()

    composeTestRule.onNodeWithText("Explanation").performTextInput("Give me ideas for making this warmer and more modern")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithText("1. Warm Modern").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onAllNodesWithText("2. Dark Contemporary").onFirst().assertExists()
    // Physical regression: the EXPLORE_PLAN branch used to replace the diagnostic panel's sole
    // scroll owner with a plain Column. Semantics could see options 2/3 in the composition, but a
    // human could not scroll the ModalBottomSheet viewport far enough to reach them. Scroll to the
    // last option and its action, then expand it to prove rich content remains reachable too.
    composeTestRule.onAllNodesWithText("3. Minimal Natural").onFirst().performScrollTo().assertExists()
    composeTestRule.onAllNodesWithText("Select this direction")[2].performScrollTo().assertExists()
    // The mock's first option carries the rich optional fields; scroll back to that card and
    // expand it after proving the third option's Select action is reachable. Select -> Proposal
    // -> Apply behavior itself remains covered by ExplorePlanFlowTest; this regression owns only
    // reachability inside the unified guidance ModalBottomSheet.
    composeTestRule.onAllNodesWithText("Show details")[0].performScrollTo().clickInSheet()
    composeTestRule.onNodeWithText("TRADEOFFS").performScrollTo().assertExists()
    composeTestRule.onAllNodesWithText("‹ Back to diagnostic view").onFirst().assertExists()
  }

  // --- Test 2: same Project, a diagnostic request routes to TROUBLESHOOT instead ---

  @Test
  fun sameRoomRedesignProjectRoutesADiagnosticRequestToTroubleshoot() {
    // Evidence is already present (seeded below, matching the natural Continue-on-phone case) -
    // the multimodal bridge stages it before Get Guidance, so the backend correctly receives an
    // investigation_session_id and this resolves as evidence-backed TROUBLESHOOT, closing the
    // sheet back onto Project Detail (see ContinueInvestigationSection's dispatch), never the
    // rich EXPLORE_PLAN renderer for a diagnostic request on the same Project.
    val repository = MockProjectRepository()
    setProjectDetailContent(repository = repository)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Explanation").performTextInput("This dresser drawer keeps sticking. What should I check?")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithText("INVESTIGATION").fetchSemanticsNodes().isEmpty()
    }
    composeTestRule.onNodeWithText("1. Warm Modern").assertDoesNotExist()
  }

  @Test
  fun sameRoomRedesignProjectWithNoEvidenceYetRoutesADiagnosticRequestToTextOnlyTroubleshoot() {
    // The genuinely-no-evidence-yet variant of the test above: same Project, same diagnostic
    // text, but nothing has been captured/staged - resolves as text-only TROUBLESHOOT instead.
    val repository = MockProjectRepository()
    setProjectDetailContent(
        repository = repository,
        seedExplanationToSatisfyActiveInvestigationGate = true,
        seededExplanationText = "This dresser drawer keeps sticking. What should I check?",
    )
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(hasText("Diagnosis — unconfirmed"), timeoutMillis = 10_000L)
    composeTestRule.onNodeWithText("Mock diagnosis for: This dresser drawer keeps sticking. What should I check?").assertExists()
    composeTestRule.onNodeWithText("1. Warm Modern").assertDoesNotExist()
  }

  // --- Test 3: text-only troubleshoot with NO photo/session ---

  @Test
  fun textOnlyTroubleshootSucceedsWithNoPhotoOrSession() {
    val repository = MockProjectRepository()
    // Seeds the real request text directly (rather than typing it via the field) - zero evidence,
    // zero session, satisfying hasActiveInvestigation through explanationText alone, exactly the
    // "no photo/session" scenario this test proves.
    setProjectDetailContent(
        repository = repository,
        seedExplanationToSatisfyActiveInvestigationGate = true,
        seededExplanationText = "My condenser won't start. What should I check first?",
    )
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)
    composeTestRule.onNodeWithText("0 photos added").assertExists()

    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(hasText("Diagnosis — unconfirmed"), timeoutMillis = 10_000L)
    composeTestRule.onNodeWithText("Suggested next step").assertExists()
    composeTestRule.onNodeWithText("Mock recommended next action.").assertExists()
  }

  // --- Test 4: GENERAL_GUIDANCE follow-up ---

  @Test
  fun generalGuidanceFollowUpRendersTheAnswer() {
    val repository = MockProjectRepository()
    setProjectDetailContent(repository = repository)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Explanation").performTextInput("Why did you recommend that?")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(
        hasText("Mock general guidance answer for: Why did you recommend that?"),
        timeoutMillis = 10_000L,
    )
    // No diagnosis/next-step framing for a general answer.
    composeTestRule.onNodeWithText("Diagnosis — unconfirmed").assertDoesNotExist()
  }

  // --- Test 5: clarification-needed keeps the composer editable and resubmit works ---

  @Test
  fun clarificationNeededKeepsComposerEditableAndResubmitWorks() {
    val repository = MockProjectRepository()
    repository.guidanceRouter = { _, _, _ ->
      ProjectGuidanceOutcome.NeedsClarification("Do you want design ideas, or is something not working?")
    }
    setProjectDetailContent(repository = repository)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Explanation").performTextInput("hmm")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(
        hasText("Do you want design ideas, or is something not working?"),
        timeoutMillis = 10_000L,
    )
    // No internal routing terminology is ever shown.
    composeTestRule.onNodeWithText("TROUBLESHOOT").assertDoesNotExist()
    composeTestRule.onNodeWithText("EXPLORE_PLAN").assertDoesNotExist()
    composeTestRule.onNodeWithText("GENERAL_GUIDANCE").assertDoesNotExist()

    // The SAME composer remains editable - answer the question and resubmit through Get guidance.
    repository.guidanceRouter = { _, _, _ ->
      ProjectGuidanceOutcome.Ready(
          ProjectGuidanceResult.GeneralGuidance(answer = "Understood - here is a general answer.", uncertain = false),
      )
    }
    composeTestRule.onNodeWithText("Explanation").performTextClearance()
    composeTestRule.onNodeWithText("Explanation").performTextInput("Just tell me where we left off")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(hasText("Understood - here is a general answer."), timeoutMillis = 10_000L)
  }

  // --- Test 6: routing/provider failure is retryable and preserves the typed request ---

  @Test
  fun routingFailureShowsRetryableErrorAndPreservesTypedRequest() {
    val repository = MockProjectRepository()
    var calls = 0
    repository.guidanceRouter = { _, _, _ ->
      calls++
      if (calls == 1) throw java.io.IOException("Response routing is unavailable.")
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Recovered answer.", uncertain = false))
    }
    setProjectDetailContent(repository = repository)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    val typedRequest = "Give me ideas for making this warmer and more modern"
    composeTestRule.onNodeWithText("Explanation").performTextInput(typedRequest)
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()

    composeTestRule.waitUntilExactlyOneExists(hasText("Response routing is unavailable."), timeoutMillis = 10_000L)
    // Typed request preserved - still visible in the same field, never silently discarded.
    composeTestRule.onNodeWithText(typedRequest).assertExists()
    // Never silently substituted with a different family's fabricated result.
    composeTestRule.onNodeWithText("1. Warm Modern").assertDoesNotExist()

    composeTestRule.onNodeWithText("Retry").clickInSheet()
    composeTestRule.waitUntilExactlyOneExists(hasText("Recovered answer."), timeoutMillis = 10_000L)
    assertEquals(2, calls)
  }

  // --- Test 7 & 8: investigation_session_id passed through unchanged / never manufactured ---

  @Test
  fun explicitActiveInvestigationSessionIdIsSentThroughUnchanged() {
    val repository = MockProjectRepository()
    var receivedSessionId: String? = "not-called"
    repository.guidanceRouter = { _, _, sessionId ->
      receivedSessionId = sessionId
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Answer.", uncertain = false))
    }
    val activeSessionId = "11111111-1111-1111-1111-111111111111"
    setProjectDetailContent(repository = repository, continuationSessionId = activeSessionId)
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Explanation").performTextInput("Why did you recommend that?")
    composeTestRule.onNodeWithText("Get guidance").clickInSheet()
    composeTestRule.waitUntilExactlyOneExists(hasText("Answer."), timeoutMillis = 10_000L)

    assertEquals(activeSessionId, receivedSessionId)
  }

  @Test
  fun androidNeverManufacturesASessionIdWhenNoneExists() {
    val repository = MockProjectRepository()
    var receivedSessionId: String? = "not-called"
    repository.guidanceRouter = { _, _, sessionId ->
      receivedSessionId = sessionId
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Answer.", uncertain = false))
    }
    // Genuinely nothing yet - no evidence, no session (the default setProjectDetailContent seeds
    // one live-glasses evidence item, which would legitimately stage a session; that case is
    // exactly what explicitActiveInvestigationSessionIdIsSentThroughUnchanged and the staging
    // tests below already cover).
    setProjectDetailContent(
        repository = repository,
        seedExplanationToSatisfyActiveInvestigationGate = true,
        seededExplanationText = "Why did you recommend that?",
    )
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)

    composeTestRule.onNodeWithText("Get guidance").clickInSheet()
    composeTestRule.waitUntilExactlyOneExists(hasText("Answer."), timeoutMillis = 10_000L)

    assertEquals(null, receivedSessionId)
  }

  // --- ADR-060 multimodal bridge: ProjectGuidanceViewModel's own staging-then-guidance sequencing ---
  // (session-level upload behavior/ordering/dedup-reliance is covered separately and more directly
  // in InvestigationSessionRepositoryTest.kt's stageEvidence tests - these exercise the SEQUENCING
  // contract getGuidance itself owns, with a trivial fake stageEvidence lambda.)

  @Test
  fun stagingFailureBlocksTheGuidanceCallAndSurfacesAsARetryableFailure() {
    val repository = MockProjectRepository()
    project = freshProject(repository)
    var guidanceCalls = 0
    repository.guidanceRouter = { _, _, _ ->
      guidanceCalls++
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Should never be reached.", uncertain = false))
    }
    lateinit var viewModel: ProjectGuidanceViewModel
    composeTestRule.setContent {
      viewModel = remember { ProjectGuidanceViewModel(application, project.projectId, repository) }
    }

    viewModel.getGuidance("Give me ideas for this room.") { throw java.io.IOException("Could not stage evidence.") }

    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      viewModel.uiState.value is ProjectGuidanceUiState.Failed
    }
    assertEquals(0, guidanceCalls)
    assertEquals(
        "Could not stage evidence.",
        (viewModel.uiState.value as ProjectGuidanceUiState.Failed).message,
    )
  }

  @Test
  fun exactlyOneReasoningCallOccursAndStagingAlwaysRunsBeforeIt() {
    val repository = MockProjectRepository()
    project = freshProject(repository)
    val callOrder = mutableListOf<String>()
    var guidanceCalls = 0
    repository.guidanceRouter = { _, _, sessionId ->
      guidanceCalls++
      callOrder += "guidance:$sessionId"
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Answer.", uncertain = false))
    }
    lateinit var viewModel: ProjectGuidanceViewModel
    composeTestRule.setContent {
      viewModel = remember { ProjectGuidanceViewModel(application, project.projectId, repository) }
    }

    viewModel.getGuidance("Give me ideas for this room.") {
      callOrder += "stage"
      "staged-session-id"
    }

    composeTestRule.waitUntil(timeoutMillis = 10_000L) { viewModel.uiState.value is ProjectGuidanceUiState.Ready }
    // Staging happened, exactly once, strictly before the single reasoning call - and that
    // reasoning call received the session id staging produced.
    assertEquals(listOf("stage", "guidance:staged-session-id"), callOrder)
    assertEquals(1, guidanceCalls)
  }

  @Test
  fun stagingSucceedsButGuidanceFailsThenRetryReStagesAndReusesTheSameSessionId() {
    val repository = MockProjectRepository()
    project = freshProject(repository)
    var guidanceCalls = 0
    var lastReceivedSessionId: String? = null
    repository.guidanceRouter = { _, _, sessionId ->
      guidanceCalls++
      lastReceivedSessionId = sessionId
      if (guidanceCalls == 1) throw java.io.IOException("Response routing is unavailable.")
      ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.GeneralGuidance(answer = "Recovered.", uncertain = false))
    }
    var stageCalls = 0
    lateinit var viewModel: ProjectGuidanceViewModel
    composeTestRule.setContent {
      viewModel = remember { ProjectGuidanceViewModel(application, project.projectId, repository) }
    }
    val stageEvidence: suspend () -> String? = {
      stageCalls++
      "staged-session-id"
    }

    viewModel.getGuidance("Give me ideas for this room.", stageEvidence)
    composeTestRule.waitUntil(timeoutMillis = 10_000L) { viewModel.uiState.value is ProjectGuidanceUiState.Failed }

    viewModel.getGuidance("Give me ideas for this room.", stageEvidence)
    composeTestRule.waitUntil(timeoutMillis = 10_000L) { viewModel.uiState.value is ProjectGuidanceUiState.Ready }

    assertEquals(2, stageCalls)
    assertEquals(2, guidanceCalls)
    assertEquals("staged-session-id", lastReceivedSessionId)
  }

  private fun setProjectDetailContent(
      repository: MockProjectRepository,
      continuationSessionId: String? = null,
      seedExplanationToSatisfyActiveInvestigationGate: Boolean = false,
      seededExplanationText: String = "placeholder",
  ) {
    project = freshProject(repository)
    composeTestRule.setContent {
      val detailVm =
          remember {
            ProjectDetailViewModel(application = application, projectId = project.projectId, repository = repository)
          }
      // ADR-060: injects FakeInvestigationSessionApi (bypassing the public factory(), which
      // always builds a real HttpUrlInvestigationSessionApi) so stagePendingEvidenceForGuidance -
      // now invoked by every "Get guidance" tap below that has evidence or a session - never
      // makes a real network call to the Investigation backend in these tests.
      val investigationViewModel: InvestigationSessionDebugViewModel =
          viewModel(
              key = investigationViewModelKey(project.projectId, continuationSessionId),
              factory =
                  object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        InvestigationSessionDebugViewModel(
                            application = application,
                            sourceProjectId = project.projectId,
                            initialContinuationSessionId = continuationSessionId,
                            repository = InvestigationSessionRepository(api = FakeInvestigationSessionApi()),
                        ) as T
                  },
          )
      // Pre-creates the SAME keys ProjectDetailScreen's hoisted ViewModels will resolve to
      // (Compose's viewModel(key=...) only ever calls a factory when no entry for that key exists
      // yet) - the only way to get MockProjectRepository into them instead of the production
      // default (a real HttpUrlProjectRepository, which would otherwise attempt a live network
      // call this test must never make).
      viewModel<ExplorePlanViewModel>(
          key = "explore-plan:${project.projectId}",
          factory =
              object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ExplorePlanViewModel(application, project.projectId, repository) as T
              },
      )
      viewModel<ProjectGuidanceViewModel>(
          key = "project-guidance:${project.projectId}",
          factory =
              object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ProjectGuidanceViewModel(application, project.projectId, repository) as T
              },
      )
      LaunchedEffect(Unit) {
        if (continuationSessionId == null && seedExplanationToSatisfyActiveInvestigationGate) {
          // Simulates a restored typed draft with zero evidence and no session - the smallest
          // signal that satisfies ContinueInvestigationSection's existing hasActiveInvestigation
          // gate (pre-existing, unrelated to ADR-060) without capturing any evidence.
          investigationViewModel.setExplanationText(seededExplanationText)
        } else if (continuationSessionId == null) {
          investigationViewModel.appendLiveEvidence(
              InvestigationEvidenceInput(
                  slotIndex = 0,
                  filename = "room.jpg",
                  mimeType = "image/jpeg",
                  bytes = byteArrayOf(1, 2, 3),
                  source = InvestigationEvidenceSource.LIVE_GLASSES,
              ),
          )
        }
      }
      ProjectDetailScreen(
          project = project,
          onBack = {},
          onStartWorking = {},
          onResumeInvestigation = { _, _ -> },
          onContinueProject = {},
          focusPendingReview = false,
          focusActiveInvestigation = true,
          investigationContinuationSessionId = continuationSessionId,
          viewModel = detailVm,
      )
    }
  }
}
