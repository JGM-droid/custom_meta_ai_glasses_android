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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
 * - "Continue Project" carries the selected (real) project into the workspace placeholder.
 * - "+ New Project" opens a real creation form; back returns to Projects Home.
 * - required-field validation fails without a backend round trip, and never leaves the Create
 *   button stuck disabled - a failed attempt can be retried with the same button.
 * - a successful creation calls the real POST /projects, navigates straight to that project's
 *   own Project Detail (honest empty state, since no checkpoint fields were supplied), and the
 *   new project is visible after returning to a freshly-refreshed Projects Home alongside the
 *   projects that already existed.
 * - "Capture / Test Glasses" still reaches the existing, unmodified Meta camera/capture flow.
 *
 * Tests that create a Project use a timestamped unique name (see uniqueProjectName) so repeated
 * physical-device runs never collide/confuse each other in the shared dev backend.
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
  fun continueProjectCarriesTheSelectedRealProjectIntoTheWorkspacePlaceholder() {
    waitForTag("project_row_0")
    val name = firstTextOf(composeTestRule.onNodeWithTag("project_row_0"))

    composeTestRule.onNodeWithTag("project_row_0").performClick()
    waitFor("Continue Project").performClick()

    waitForSubstring("Project Workspace")
    waitForSubstring(name)

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
