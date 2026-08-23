/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Project Assistant top-level shell (AppRoot) against the REAL backend Project
 * Memory API (see projects/ProjectRepository.kt -> HttpUrlProjectRepository). These tests
 * intentionally do not assume specific project names/content, since the backend's real project
 * list changes over time (see docs/PROJECT_MEMORY_ARCHITECTURE.md) - they select rows by the
 * index-based `project_row_N` testTag (see ProjectsHomeScreen.kt) and compare whatever real
 * content is actually rendered, rather than hardcoding expected text.
 *
 * Covered:
 * - the app launches into Projects Home by default, not the Meta CameraAccess flow.
 * - Projects Home is backed by the real repository (Loading, then either real project rows or an
 *   explicit empty/error state - never the old hardcoded four-project placeholder list).
 * - two distinct real backend projects show distinct identity in Project Overview, and opening
 *   one never shows another's identity (no cross-project leakage).
 * - "Continue Project" carries the selected (real) project into the real Project Workspace.
 * - "+ New Project" opens a real creation form; back returns to Projects Home.
 * - required-field validation fails without a backend round trip, and never leaves the Create
 *   button stuck disabled - a failed attempt can be retried with the same button.
 * - a successful creation calls the real POST /projects, navigates straight to that project's
 *   own Project Detail (honest empty state, since no checkpoint fields were supplied), and the
 *   new project is visible after returning to a freshly-refreshed Projects Home alongside the
 *   projects that already existed.
 * - "Capture / Test Glasses" still reaches the existing, unmodified Meta camera/capture flow.
 *
 * Active Project (real PUT/GET/DELETE /projects/active against the backend's one global Active
 * Project pointer - see docs/PROJECT_MEMORY_ARCHITECTURE.md):
 * - a newly-created Project is never automatically Active ("Work on this Project" shows, not
 *   "Active Project").
 * - pressing "Work on this Project" makes it Active (Project Detail switches to "Active Project"
 *   / "Stop Working on Project"), Projects Home then shows its Active indicator, and pressing
 *   "Stop Working on Project" clears it end to end.
 * - VIEWING a Project is never the same as it being ACTIVE: opening/viewing a second Project
 *   never calls setActiveProject - the first Project remains Active (on both its own Detail
 *   screen, if revisited, and on Projects Home) until the user explicitly presses "Work on this
 *   Project" on the second one, at which point the backend's single Active Project pointer moves
 *   and the first Project visibly loses its Active status everywhere.
 *
 * Because the backend's Active Project pointer is a single global value (not per-test-run), these
 * tests always drive every Active-state transition they depend on explicitly through the UI
 * (never assuming "nothing is Active" as a starting condition) and identify projects/rows by the
 * unique name each test itself created, never by an assumption about the ambient global state.
 *
 * Project Workspace v1 ("Continue Project" -> the real Workspace, not the old placeholder):
 * - Continue Project opens Workspace for the exact canonical project_id that was tapped, and
 *   Workspace's own real Where We Left Off / Next Action / Active Project / Recent Activity all
 *   come from that same project - covered together with the required-empty-state and
 *   real-next_action-rendering proof in workspaceOpensForCorrectProjectWithRealDataAndBackReturnsToOverview.
 * - Draft composer text is proven NOT to reach the backend by reading the Project's raw
 *   checkpoint straight from the backend (a plain GET, via fetchProjectJson below) after typing
 *   into the composer and making the Project Active from Workspace - see
 *   workingOnProjectFromWorkspaceAndDraftTextNeverMutatesBackendCheckpoint.
 * - Project isolation and reaching Capture from Workspace (a new entry point this slice adds) are
 *   covered in workspaceProjectIsolationAndCaptureReachableFromWorkspace.
 * - "Real Where We Left Off (checkpoint.current_work) renders" is NOT independently
 *   live-instrumented here: Android has no reachable path to set current_work (the create form
 *   only ever sets current_objective/next_action - see NewProjectRequest), and Workspace renders
 *   it via the exact same ProjectSection composable and ProjectDetailViewModel/overview.checkpoint
 *   field Project Detail already uses. Non-null current_work -> whereWeLeftOff mapping is already
 *   directly proven by ProjectBackendClientTest.kt's JVM tests (e.g.
 *   twoDifferentProjectsProduceDistinctOverviews); this file proves the honest-empty-state case
 *   live instead, which real Workspace usage will exercise for every brand-new Project.
 *
 * Tests that create Projects are skipped unless the instrumentation run explicitly supplies
 * `allow_project_backend_mutation=true`. This prevents ordinary test runs from writing disposable
 * Projects into the configured persistent backend. Opted-in runs still use timestamped unique
 * names (see uniqueProjectName) so their test records remain distinguishable.
 *
 * Uses waitUntilExactlyOneExists(..., timeoutMillis=...) rather than bare assertExists() after a
 * click - the same pattern InstrumentationTest.kt already relies on - since immediate asserts
 * right after a click proved flaky on real hardware in this project. Backend calls add real
 * network latency, so timeouts here are longer than the pre-existing UI-only tests use.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppRootTest {

  companion object {
    private const val BACKEND_TIMEOUT_MS = 20_000L

    // A real Ask Project request round-trips through the backend's context retriever AND an
    // actual OpenAI call - meaningfully slower than the plain CRUD calls BACKEND_TIMEOUT_MS is
    // sized for.
    private const val ASK_TIMEOUT_MS = 45_000L
  }

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun setup() {
    grantPermissions()
  }

  @Test
  fun appLaunchesToProjectsHomeNotCameraAccessFlow() {
    waitFor("Project Assistant")
    waitFor("+ New Project")

    // Confirm we did NOT land directly in the Meta camera flow's device-setup screen.
    composeTestRule
        .onNodeWithText(
            composeTestRule.activity.getString(R.string.non_stream_screen_title),
        )
        .assertDoesNotExist()
  }

  @Test
  fun projectsHomeShowsRealBackendDataNotTheOldPlaceholderList() {
    // Either real projects loaded, or an explicit empty/error state - resolved out of Loading.
    waitForAnyOf(hasTestTag("project_row_0"), hasText("No projects yet."), hasText("Couldn't load your projects."))

    // The old hardcoded four-project placeholder list must be gone regardless of which case
    // above applies - none of these were ever real backend project names.
    composeTestRule.onNodeWithText("Lanyard Website", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Garage Door Repair", substring = true).assertDoesNotExist()
  }

  @Test
  fun tappingCaptureLeavesProjectsHomeAndEntersCameraAccessFlow() {
    waitFor("Capture / Test Glasses").performClick()

    // The existing CameraAccessScaffold state machine should now be rendering one of its own
    // screens - either still-unregistered (HomeScreen) or already-registered (NonStreamScreen) -
    // without this test asserting which, since that depends on the device's DAT registration
    // state rather than anything this slice changed.
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))

    // Projects Home content should be gone once inside Capture.
    composeTestRule.onNodeWithText("+ New Project").assertDoesNotExist()
  }

  @Test
  fun twoDistinctRealProjectsShowDistinctIdentityWithNoCrossLeakage() {
    waitForTag("project_row_0")
    waitForTag("project_row_1")

    val firstName = firstTextOf(composeTestRule.onNodeWithTag("project_row_0"))
    val secondName = firstTextOf(composeTestRule.onNodeWithTag("project_row_1"))
    assertTrue("Expected a non-blank project name", firstName.isNotBlank())
    assertTrue("Expected a non-blank project name", secondName.isNotBlank())
    assertNotEquals("Expected two distinct real projects", firstName, secondName)

    // Open the first project - its own name shows, "+ New Project" (Projects Home) is gone.
    composeTestRule.onNodeWithTag("project_row_0").performClick()
    waitForSubstring(firstName)
    composeTestRule.onNodeWithText("+ New Project").assertDoesNotExist()
    assertSubstringAbsent(secondName)

    waitFor("‹ Projects").performClick()
    waitForTag("project_row_1")

    // Open the second project - its own name shows, the first project's name does not leak in.
    composeTestRule.onNodeWithTag("project_row_1").performClick()
    waitForSubstring(secondName)
    assertSubstringAbsent(firstName)
  }

  @Test
  fun continueProjectCarriesTheSelectedRealProjectIntoWorkspace() {
    waitForTag("project_row_0")
    val name = firstTextOf(composeTestRule.onNodeWithTag("project_row_0"))

    composeTestRule.onNodeWithTag("project_row_0").performClick()
    waitFor("Continue Project").performClick()

    // Real Workspace (Project Workspace v1), not the old placeholder - shows this Project's own
    // identity via the composer/back control, never a hardcoded "Project Workspace" label.
    waitForSubstring(name)
    waitFor("‹ Overview")
    composeTestRule.onNodeWithText("Continue Project").assertDoesNotExist()

    // Back steps to this Project's own Detail/Overview first - not straight to Projects Home.
    waitFor("‹ Overview").performClick()
    waitForSubstring(name)
    waitFor("Continue Project")

    waitFor("‹ Projects").performClick()
    waitFor("Project Assistant")
  }

  @Test
  fun tappingNewProjectOpensFormAndBackReturnsHome() {
    waitFor("+ New Project").performClick()

    waitFor("Create New Project")

    waitFor("‹ Projects").performClick()
    waitFor("Project Assistant")
  }

  @Test
  fun requiredFieldValidationKeepsFormEditableForRetry() {
    waitFor("+ New Project").performClick()
    waitFor("Create New Project")

    // Tap Create with everything blank - client-side validation, no backend round trip needed.
    waitFor("Create Project").performClick()
    waitForSubstring("required")

    // The Create button must not be stuck disabled after a failed attempt - fill in the
    // required fields and successfully retry with the SAME button.
    val uniqueName = uniqueProjectName("Retry")
    fillField("e.g. Garage Door Sensor", uniqueName)
    fillField("What are you trying to accomplish?", "Prove the Create button survives a failed attempt.")
    requireExplicitBackendMutationOptIn()
    waitFor("Create Project").performClick()

    waitForSubstring(uniqueName)
  }

  @Test
  fun successfulCreationNavigatesToProjectDetailAndAppearsBackOnProjectsHome() {
    waitForTag("project_row_0")
    val previousTopProject = firstTextOf(composeTestRule.onNodeWithTag("project_row_0"))

    val uniqueName = uniqueProjectName("Create")
    val uniqueGoal = "Verify real project creation end to end from AppRootTest."

    waitFor("+ New Project").performClick()
    waitFor("Create New Project")
    fillField("e.g. Garage Door Sensor", uniqueName)
    fillField("What are you trying to accomplish?", uniqueGoal)
    requireExplicitBackendMutationOptIn()
    waitFor("Create Project").performClick()

    // Navigates straight to the new (real, backend-created) Project's own Project Detail -
    // never a placeholder, never another project's identity. No checkpoint fields were
    // supplied, so the empty state must be honest, not fabricated.
    waitForSubstring(uniqueName)
    waitForSubstring("No current work recorded.")
    waitForSubstring("No next action recorded.")
    waitForSubstring("No recent activity.")

    waitFor("‹ Projects").performClick()

    // Projects Home refreshed from the backend: the new project is visible, and the
    // previously-top project is still there too (existing Projects remain available).
    waitForSubstring(uniqueName)
    waitForSubstring(previousTopProject)
  }

  @Test
  fun newlyCreatedProjectIsNotActiveButCanBeWorkedOnThenStopped() {
    val uniqueName = uniqueProjectName("Active")
    createProjectFromProjectsHome(uniqueName, "Prove Active Project set/clear from Project Detail.")

    // Just created - never automatically Active (Phase 9).
    waitFor("Work on this Project")
    composeTestRule.onNodeWithText("Active Project").assertDoesNotExist()

    waitFor("Work on this Project").performClick()
    waitFor("Active Project")
    waitFor("Stop Working on Project")

    // Projects Home reflects it too - this project was just created, so it's project_row_0.
    waitFor("‹ Projects").performClick()
    waitForTag("project_row_0")
    assertTrue(
        "Expected the just-created, just-activated project's row to show the Active indicator",
        allTextOf(composeTestRule.onNodeWithTag("project_row_0")).contains("Active"),
    )

    // Re-open it - the Active state came from a fresh backend fetch, not a locally-cached flag.
    composeTestRule.onNodeWithTag("project_row_0").performClick()
    waitForSubstring(uniqueName)
    waitFor("Active Project")

    waitFor("Stop Working on Project").performClick()
    waitFor("Work on this Project")
    composeTestRule.onNodeWithText("Active Project").assertDoesNotExist()

    waitFor("‹ Projects").performClick()
    waitForTag("project_row_0")
    assertTrue(
        "Expected no Active indicator anywhere on Projects Home after Stop Working",
        allTextOf(composeTestRule.onNodeWithTag("project_row_0")).none { it == "Active" },
    )
  }

  @Test
  fun viewingAnotherProjectNeverChangesBackendActiveProjectUntilExplicitlyWorkedOn() {
    val nameA = uniqueProjectName("SwitchA")
    val nameB = uniqueProjectName("SwitchB")

    createProjectFromProjectsHome(nameA, "Project A for the viewing-vs-active proof.")
    waitFor("Work on this Project").performClick()
    waitFor("Active Project")

    waitFor("‹ Projects").performClick()
    val rowA = waitForRowIndexOfProject(nameA)
    assertTrue(
        "Expected Project A's row to show Active right after activating it",
        allTextOf(composeTestRule.onNodeWithTag("project_row_$rowA")).contains("Active"),
    )

    // Creating and opening Project B must not touch the backend Active Project.
    waitFor("+ New Project").performClick()
    waitFor("Create New Project")
    fillField("e.g. Garage Door Sensor", nameB)
    fillField("What are you trying to accomplish?", "Project B for the viewing-vs-active proof.")
    waitFor("Create Project").performClick()
    waitForSubstring(nameB)
    // B is merely being viewed - not Active, and A is not visible from here to leak into it.
    waitFor("Work on this Project")
    composeTestRule.onNodeWithText("Active Project").assertDoesNotExist()

    waitFor("‹ Projects").performClick()
    val rowB = waitForRowIndexOfProject(nameB)
    assertTrue(
        "Expected Project B's row to NOT show Active - it was only viewed, never worked on",
        allTextOf(composeTestRule.onNodeWithTag("project_row_$rowB")).none { it == "Active" },
    )
    val rowAAfterViewingB = waitForRowIndexOfProject(nameA)
    assertTrue(
        "Expected Project A to remain Active on Projects Home after merely viewing Project B",
        allTextOf(composeTestRule.onNodeWithTag("project_row_$rowAAfterViewingB")).contains("Active"),
    )

    // Now explicitly work on B - the backend's single Active Project pointer moves A -> B.
    composeTestRule.onNodeWithTag("project_row_$rowB").performClick()
    waitForSubstring(nameB)
    waitFor("Work on this Project").performClick()
    waitFor("Active Project")

    waitFor("‹ Projects").performClick()
    val rowBAfterSwitch = waitForRowIndexOfProject(nameB)
    assertTrue(
        "Expected Project B to be Active on Projects Home after switching",
        allTextOf(composeTestRule.onNodeWithTag("project_row_$rowBAfterSwitch")).contains("Active"),
    )
    val rowAAfterSwitch = waitForRowIndexOfProject(nameA)
    assertTrue(
        "Expected Project A to have lost Active status once B became Active",
        allTextOf(composeTestRule.onNodeWithTag("project_row_$rowAAfterSwitch")).none { it == "Active" },
    )
  }

  @Test
  fun workspaceOpensForCorrectProjectWithRealDataAndBackReturnsToOverview() {
    val name = uniqueProjectName("Workspace")
    val nextAction = "Verify $name's real next_action renders in Workspace."
    createProjectFromProjectsHome(name, "Prove Project Workspace v1 shows this Project's real state.", nextAction = nextAction)

    // Landed on this Project's own Detail (existing creation flow) - Continue into Workspace.
    waitForSubstring(name)
    waitFor("Continue Project").performClick()

    // Same canonical Project, real data, no fabricated example state.
    waitForSubstring(name)
    waitForSubstring(nextAction)
    waitForSubstring("No current work recorded.")
    waitForSubstring("No recent activity.")
    // Brand new - never automatically Active (reuses the existing Active Project implementation).
    waitFor("Work on this Project")
    composeTestRule.onNodeWithText("Active Project").assertDoesNotExist()

    // The composer accepts and EDITS draft text (two inputs, not just one write).
    val composer = composeTestRule.onNodeWithTag("workspace_composer_input")
    composer.performTextInput("Checking the ")
    composer.performTextInput("wiring harness.")
    composer.assertTextContains("Checking the wiring harness.", substring = true)

    // Back returns to THIS Project's own Overview/Detail - not straight to Projects Home.
    waitFor("‹ Overview").performClick()
    waitForSubstring(name)
    waitFor("Continue Project")
    composeTestRule.onNodeWithText("‹ Overview").assertDoesNotExist()
  }

  @Test
  fun workingOnProjectFromWorkspaceAndDraftTextNeverMutatesBackendCheckpoint() {
    val name = uniqueProjectName("WorkspaceActive")
    createProjectFromProjectsHome(name, "Prove Active Project and draft text both behave correctly from Workspace.")
    val projectId = mostRecentlyCreatedProjectId()

    waitFor("Continue Project").performClick()
    waitForSubstring(name)

    // Reuses the exact same Active Project control Project Detail uses - proven live from
    // Workspace specifically, not just by code inspection.
    waitFor("Work on this Project").performClick()
    waitFor("Active Project")
    waitFor("Stop Working on Project")

    // Type draft text but never submit it anywhere - there is nothing to submit to yet.
    composeTestRule.onNodeWithTag("workspace_composer_input").performTextInput("Draft note that must never reach the backend.")

    // Read the Project straight from the backend: Active is real, but checkpoint.current_work
    // (the only field this draft text could possibly have mutated) is still exactly what it was
    // when the Project was created - null. Draft text truly never left this screen.
    val backendProject = fetchProjectJson(projectId)
    assertEquals(projectId, fetchActiveProjectId())
    val checkpoint = backendProject.getJSONObject("checkpoint")
    assertNull(
        "Draft composer text must never mutate checkpoint.current_work",
        checkpoint.opt("current_work")?.takeIf { it != JSONObject.NULL },
    )
  }

  @Test
  fun workspaceProjectIsolationAndCaptureReachableFromWorkspace() {
    val nameA = uniqueProjectName("WorkspaceA")
    val nameB = uniqueProjectName("WorkspaceB")
    val nextActionB = "$nameB's own next action."

    createProjectFromProjectsHome(nameA, "Project A for Workspace isolation.")
    waitFor("Continue Project").performClick()
    waitForSubstring(nameA)
    waitFor("‹ Overview").performClick()
    waitFor("‹ Projects").performClick()

    createProjectFromProjectsHome(nameB, "Project B for Workspace isolation.", nextAction = nextActionB)
    waitFor("Continue Project").performClick()

    // B's own Workspace shows only B's identity/state - never A's.
    waitForSubstring(nameB)
    waitForSubstring(nextActionB)
    composeTestRule.onNodeWithText(nameA, substring = true).assertDoesNotExist()

    // Capture / Test Glasses is a Workspace entry point - prove it still reaches the real,
    // unmodified Meta camera/capture flow, same as from Projects Home, AND that it now carries
    // B's own explicit Project context (Project-Scoped Glasses Capture slice) - never A's.
    waitFor("Capture / Test Glasses").performClick()
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))
    composeTestRule.onNodeWithText("Capture / Test Glasses").assertDoesNotExist()
    waitForSubstring("Capturing for $nameB")
    composeTestRule.onNodeWithText(nameA, substring = true).assertDoesNotExist()
  }

  @Test
  fun captureFromWorkspaceBackNavigationReturnsToSourceProjectDetail() {
    val name = uniqueProjectName("CaptureBack")
    createProjectFromProjectsHome(name, "Prove Capture back-navigation returns to the source Project.")
    waitFor("Continue Project").performClick()
    waitForSubstring(name)

    waitFor("Capture / Test Glasses").performClick()
    waitForSubstring("Capturing for $name")

    // The back control is labeled with the source Project (not the generic "‹ Projects" the
    // unscoped global entry point uses) and returns to THAT Project's own Detail/Overview -
    // never straight to Projects Home (Phase 10: preserve source Project context on return).
    waitFor("‹ $name").performClick()
    waitForSubstring(name)
    waitFor("Continue Project")
    composeTestRule.onNodeWithText("+ New Project").assertDoesNotExist()
  }

  @Test
  fun directStartWorkingCarriesProjectWithoutChangingActiveAndBackReturnsDetail() {
    val activeBefore = fetchActiveProjectId()
    val name = uniqueProjectName("DirectWork")
    createProjectFromProjectsHome(name, "Prove direct Project work keeps explicit attribution separate from Active Project.")

    waitFor("Start Working with Glasses").performScrollTo().performClick()
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))
    waitForSubstring("Capturing for $name")

    // Starting Project-scoped Capture is navigation/context propagation only. It must not call
    // PUT /projects/active or otherwise change the backend's independent global Active Project.
    assertEquals(activeBefore, fetchActiveProjectId())

    waitFor("‹ $name").performClick()
    waitForSubstring(name)
    waitFor("Start Working with Glasses")
    waitFor("Continue Project")
    composeTestRule.onNodeWithText("+ New Project").assertDoesNotExist()
  }

  @Test
  fun globalCaptureFromProjectsHomeShowsNoProjectContextAndReturnsHome() {
    waitFor("Capture / Test Glasses").performClick()
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))

    // The existing global entry point carries no explicit Project - no capture-context
    // indicator, and the back control keeps its original, unchanged label/destination.
    composeTestRule.onNodeWithText("Capturing for", substring = true).assertDoesNotExist()
    waitFor("‹ Projects").performClick()
    waitFor("Project Assistant")
  }

  @Test
  fun captureProjectContextDoesNotLeakBetweenProjects() {
    val nameA = uniqueProjectName("CaptureCtxA")
    val nameB = uniqueProjectName("CaptureCtxB")

    createProjectFromProjectsHome(nameA, "Project A for capture-context isolation.")
    waitFor("Start Working with Glasses").performScrollTo().performClick()
    waitForSubstring("Capturing for $nameA")

    // Back to Projects Home (via the unscoped-equivalent path is not available here - go via
    // the source-Project back control, then Overview, then Projects Home).
    waitFor("‹ $nameA").performClick()
    waitFor("‹ Projects").performClick()

    createProjectFromProjectsHome(nameB, "Project B for capture-context isolation.")
    waitFor("Start Working with Glasses").performScrollTo().performClick()

    // B's own Capture context - never A's, even though A's Capture context was shown moments
    // earlier in the same app session.
    waitForSubstring("Capturing for $nameB")
    composeTestRule.onNodeWithText(nameA, substring = true).assertDoesNotExist()
    waitFor("‹ $nameB").performClick()
    waitForSubstring(nameB)
    waitFor("Start Working with Glasses")
    waitFor("Continue Project")
  }

  @Test
  fun debugMenuFromCaptureOpensMockDeviceKitWithoutCrashing() {
    // Regression test for a Compose crash: MockDeviceKitScreen's own root
    // Column(Modifier.verticalScroll(...)) used to wrap BackendInvestigationPanel, which has its
    // OWN internal Column(Modifier.verticalScroll(...)) - a scrollable nested inside another
    // scrollable. verticalScroll always measures its child with an infinite max-height
    // constraint, so the inner one threw "Vertically scrollable component was measured with an
    // infinity maximum height constraints" the instant this bottom sheet opened, taking the
    // whole app down. See CameraAccessScaffold.kt/MockDeviceKitScreen.kt for the fix - this test
    // proves the debug menu now opens and renders both sections without crashing.
    waitFor("Capture / Test Glasses").performClick()
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))

    composeTestRule.onNodeWithContentDescription("Debug Menu").performClick()

    waitFor("Mock Device Kit")
    waitFor("INVESTIGATION")
  }

  @Test
  fun askProjectRequiresNonBlankInputAndDoesNotChangeCanonicalProjectState() {
    val name = uniqueProjectName("Ask")
    val distinctiveNextAction = "Inspect the QRX7 capacitor housing."
    createProjectFromProjectsHome(name, "Prove Project-Aware Ask end to end.", nextAction = distinctiveNextAction)
    val projectId = mostRecentlyCreatedProjectId()
    val activeBeforeAsk = fetchActiveProjectId()

    waitFor("Continue Project").performClick()
    waitForSubstring(name)

    // Blank/whitespace-only input can never submit - the button stays disabled either way.
    val askButton = composeTestRule.onNodeWithTag("workspace_ask_button")
    askButton.assertIsNotEnabled()
    val composer = composeTestRule.onNodeWithTag("workspace_composer_input")
    composer.performTextInput("   ")
    askButton.assertIsNotEnabled()
    composer.performTextClearance()

    // Real end-to-end question against the real backend /ask route.
    val question = "What should I check next?"
    composer.performTextInput(question)
    askButton.assertIsEnabled()
    askButton.performClick()

    composeTestRule.waitUntilExactlyOneExists(hasTestTag("workspace_ask_answer"), timeoutMillis = ASK_TIMEOUT_MS)
    waitForSubstring(question)
    // Grounded in THIS Project's own distinctive next_action, not a fabricated/generic answer.
    waitForSubstring("QRX7")

    // Absolutely no memory mutation: checkpoint/revision/Active Project all unchanged by the ask.
    val projectAfterAsk = fetchProjectJson(projectId)
    assertEquals(0, projectAfterAsk.getInt("revision"))
    assertNull(
        "Ask must never write checkpoint.current_work",
        projectAfterAsk.getJSONObject("checkpoint").opt("current_work")?.takeIf { it != JSONObject.NULL },
    )
    assertEquals(activeBeforeAsk, fetchActiveProjectId())
  }

  @Test
  fun askProjectAnswersAreIsolatedPerProjectWorkspaceAndFailedAskAllowsRetry() {
    val nameA = uniqueProjectName("AskA")
    val nameB = uniqueProjectName("AskB")
    createProjectFromProjectsHome(nameA, "Project A for Ask isolation.", nextAction = "Inspect the QRX7 capacitor housing.")
    waitFor("Continue Project").performClick()
    waitForSubstring(nameA)

    val question = "What should I check next?"
    fillField("Ask your Project anything...", question)
    composeTestRule.onNodeWithTag("workspace_ask_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("workspace_ask_answer"), timeoutMillis = ASK_TIMEOUT_MS)
    waitForSubstring("QRX7")

    waitFor("‹ Overview").performClick()
    waitFor("‹ Projects").performClick()

    createProjectFromProjectsHome(nameB, "Project B for Ask isolation.", nextAction = "Verify the ZKP9 login callback.")
    waitFor("Continue Project").performClick()
    waitForSubstring(nameB)

    // A fresh Workspace for a different Project starts with NO answer at all - A's answer never
    // leaks in just from navigating here, before B has ever been asked anything.
    composeTestRule.onNodeWithTag("workspace_ask_answer").assertDoesNotExist()
    composeTestRule.onNodeWithText("QRX7", substring = true).assertDoesNotExist()

    // A failed Ask (backend validation_error - question over the backend's 1000-char limit,
    // never reaches the model, so this costs no model call) must leave the question editable
    // for retry rather than clearing it.
    // performTextReplacement (not performTextInput) - sets the value in one action rather than
    // simulating 1000+ individual keystrokes. Uses real words with spaces (not one giant
    // unbroken token) so Compose's text layout has normal line-break points.
    val tooLong = "word ".repeat(201)
    val composerB = composeTestRule.onNodeWithTag("workspace_composer_input")
    composerB.performTextReplacement(tooLong)
    composeTestRule.onNodeWithTag("workspace_ask_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("workspace_ask_error"), timeoutMillis = ASK_TIMEOUT_MS)
    composeTestRule.onNodeWithTag("workspace_composer_input").assertTextContains("word word word", substring = true)
    composeTestRule.onNodeWithTag("workspace_ask_button").assertIsEnabled()

    // Retry with a valid question - proves the failed attempt didn't lock the composer, and this
    // is B's own real Ask, isolated from A: grounded in B's own distinctive next_action.
    composeTestRule.onNodeWithTag("workspace_composer_input").performTextReplacement(question)
    composeTestRule.onNodeWithTag("workspace_ask_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(hasTestTag("workspace_ask_answer"), timeoutMillis = ASK_TIMEOUT_MS)
    waitForSubstring("ZKP9")
    composeTestRule.onNodeWithText("QRX7", substring = true).assertDoesNotExist()

    // Capture / Test Glasses remains reachable from an Ask-active Workspace - the answer card
    // above pushes it further down the scrollable screen than usual, so scroll it into view
    // first (performClick() alone can miss a node that's currently off-screen).
    val captureButton = waitFor("Capture / Test Glasses")
    captureButton.performScrollTo()
    captureButton.performClick()
    val registerLabel = composeTestRule.activity.getString(R.string.register_button_title)
    val streamTitle = composeTestRule.activity.getString(R.string.non_stream_screen_title)
    waitForAnyOf(hasText(registerLabel), hasText(streamTitle))
  }

  /** Drives the full "+ New Project" form from Projects Home; leaves the caller on the new Project's Detail screen. */
  private fun createProjectFromProjectsHome(name: String, goal: String, nextAction: String? = null) {
    requireExplicitBackendMutationOptIn()
    waitFor("+ New Project").performClick()
    waitFor("Create New Project")
    fillField("e.g. Garage Door Sensor", name)
    fillField("What are you trying to accomplish?", goal)
    if (nextAction != null) {
      fillField("What's the next concrete step?", nextAction)
    }
    waitFor("Create Project").performClick()
    waitForSubstring(name)
  }

  private fun requireExplicitBackendMutationOptIn() {
    val allowed =
        InstrumentationRegistry.getArguments().getString("allow_project_backend_mutation") == "true"
    assumeTrue(
        "Project-creating instrumentation tests require an isolated backend and explicit " +
            "-e allow_project_backend_mutation true opt-in.",
        allowed,
    )
  }

  /** The project_id of the most recently created/updated backend Project (see ProjectStore.list_projects sort order) - called immediately after createProjectFromProjectsHome, before any other Project is touched. */
  private fun mostRecentlyCreatedProjectId(): String {
    val projectsJson = fetchJsonArray("${resolvedBackendBaseUrl()}/projects")
    return projectsJson.getJSONObject(0).getString("project_id")
  }

  private fun fetchActiveProjectId(): String? {
    val connection = openTestConnection("${resolvedBackendBaseUrl()}/projects/active")
    if (connection.responseCode == 404) return null
    return JSONObject(connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }).getString("project_id")
  }

  private fun fetchProjectJson(projectId: String): JSONObject =
      JSONObject(openTestConnection("${resolvedBackendBaseUrl()}/projects/$projectId").inputStream.use {
        it.readBytes().toString(StandardCharsets.UTF_8)
      })

  private fun fetchJsonArray(url: String) =
      org.json.JSONArray(openTestConnection(url).inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) })

  private fun openTestConnection(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    return connection
  }

  /** The same base URL resolution the app itself uses (BuildConfig.INVESTIGATION_BACKEND_BASE_URL, trimmed, with the app's own emulator-default fallback) - read-only verification calls only, never used to seed data. */
  private fun resolvedBackendBaseUrl(): String {
    val raw = BuildConfig.INVESTIGATION_BACKEND_BASE_URL.trim()
    return raw.ifEmpty { "http://10.0.2.2:8001" }
  }

  /** Scans project_row_0..N on the CURRENT Projects Home composition for the row whose name matches. */
  private fun waitForRowIndexOfProject(name: String, maxRows: Int = 20): Int {
    waitForTag("project_row_0")
    for (index in 0 until maxRows) {
      val tag = "project_row_$index"
      if (composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) break
      if (firstTextOf(composeTestRule.onNodeWithTag(tag)) == name) return index
    }
    fail("Could not find a Projects Home row for \"$name\" within the first $maxRows rows")
    error("unreachable")
  }

  private fun fillField(placeholder: String, value: String) {
    waitFor(placeholder).performTextInput(value)
  }

  private fun uniqueProjectName(label: String): String =
      "AppRootTest $label ${System.currentTimeMillis()}"

  private fun waitFor(text: String) =
      composeTestRule
          .apply { waitUntilExactlyOneExists(hasText(text), timeoutMillis = BACKEND_TIMEOUT_MS) }
          .onNodeWithText(text)

  private fun waitForTag(tag: String) {
    composeTestRule.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = BACKEND_TIMEOUT_MS)
  }

  // Uses "at least one" rather than "exactly one" - some check phrases legitimately match more
  // than one node (e.g. a word appearing in both the checkpoint text and an activity entry).
  private fun waitForSubstring(text: String) {
    if (text.isBlank()) return
    composeTestRule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = BACKEND_TIMEOUT_MS)
  }

  private fun waitForAnyOf(vararg matchers: androidx.compose.ui.test.SemanticsMatcher) {
    val combined = matchers.reduce { acc, matcher -> acc.or(matcher) }
    composeTestRule.waitUntilAtLeastOneExists(combined, timeoutMillis = BACKEND_TIMEOUT_MS)
  }

  private fun assertSubstringAbsent(text: String) {
    if (text.isBlank()) return
    composeTestRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)
  }

  /** The first merged text string on a node - for a project row/title, this is the project name
   * itself, ahead of any appended status text like "Active". */
  private fun firstTextOf(interaction: SemanticsNodeInteraction): String {
    val node = interaction.fetchSemanticsNode()
    val textList = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
    return textList.firstOrNull()?.text.orEmpty()
  }

  /** All merged text strings on a node - e.g. a project row shows [name, "Active"] when that
   * row's project is the backend's Active Project, or just [name] otherwise. */
  private fun allTextOf(interaction: SemanticsNodeInteraction): List<String> {
    val node = interaction.fetchSemanticsNode()
    return node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
  }

  private fun grantPermissions() {
    grantPermission("android.permission.BLUETOOTH")
    grantPermission("android.permission.BLUETOOTH_CONNECT")
    grantPermission("android.permission.CAMERA")
    grantPermission("android.permission.INTERNET")
  }

  private fun grantPermission(permission: String) {
    val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    try {
      InstrumentationRegistry.getInstrumentation()
          .uiAutomation
          .executeShellCommand("pm grant $packageName $permission")
    } catch (e: IOException) {
      // Best-effort, same as RegistrationButtonTest.
    }
  }
}
