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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces the exact post-Use state (evidence already present in the SAME
 * investigationViewModelKey-resolved InvestigationSessionDebugViewModel StreamScreen's HUD-driven
 * Capture/Use would have populated) and drives ProjectDetailScreen/ContinueInvestigationSection
 * directly, without any real DAT capturePhoto() - a lightweight createComposeRule() host (no
 * MainActivity/AppRoot navigation, no real backend), the exact same pattern
 * ProjectWorkspaceScreenTest.kt already establishes.
 *
 * This is what actually proves the Continue-on-phone -> ACTIVE_INVESTIGATION handoff opens the
 * real BackendInvestigationPanel end to end - the JVM-only ProjectContinuityHudAcceptanceHarnessTest
 * proves the HUD controller/state-machine side (phoneHandoff() emitting ACTIVE_INVESTIGATION) but
 * deliberately never touches Compose/ViewModel/Activity (see that harness's own class doc), so it
 * cannot exercise ContinueInvestigationSection's actual rendering. This file is what closes that
 * gap, independent of DAT hardware/reliability entirely.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ActiveInvestigationHandoffTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  private val project =
      ProjectSummary(projectId = "upstairs-ac-repair", name = "Upstairs AC Repair", status = "active")

  @Test
  fun activeInvestigationHandoffAutoOpensPanelWithPrepopulatedEvidence() {
    setProjectDetailContent(focusActiveInvestigation = true)

    // Proves the full handoff end to end: the panel auto-opened (no tap performed) and shows the
    // pre-populated evidence - the SAME BackendInvestigationPanel/ModalBottomSheet mechanism as
    // StreamScreen's own blue tab.
    composeTestRule.waitUntilExactlyOneExists(hasText("INVESTIGATION"), timeoutMillis = 15_000L)
    composeTestRule.onNodeWithText("1 photo added").assertExists()
  }

  /**
   * The auto-open is conditional on focusActiveInvestigation, not merely on evidence existing -
   * without it, the same evidence is still reachable (the reopen-affordance chip), but the sheet
   * does not open itself.
   */
  @Test
  fun panelDoesNotAutoOpenWithoutTheActiveInvestigationHandoffFlag() {
    setProjectDetailContent(focusActiveInvestigation = false)

    composeTestRule.waitUntilExactlyOneExists(hasText("Investigation · 1 view"), timeoutMillis = 15_000L)
    composeTestRule.onNodeWithText("INVESTIGATION").assertDoesNotExist()
  }

  private fun setProjectDetailContent(focusActiveInvestigation: Boolean) {
    composeTestRule.setContent {
      val detailVm =
          remember {
            ProjectDetailViewModel(
                application = application,
                projectId = project.projectId,
                repository = MockProjectRepository(),
            )
          }
      // Same key formula StreamScreen and ContinueInvestigationSection both use
      // (investigationViewModelKey(project.projectId, null)) - if Compose's ViewModelStore
      // resolves THIS call and ContinueInvestigationSection's own internal call to the SAME
      // instance, evidence appended here must be visible there too.
      val investigationViewModel: InvestigationSessionDebugViewModel =
          viewModel(
              key = investigationViewModelKey(project.projectId, null),
              factory =
                  InvestigationSessionDebugViewModel.factory(
                      application = application,
                      sourceProjectId = project.projectId,
                      initialContinuationSessionId = null,
                  ),
          )
      LaunchedEffect(Unit) {
        investigationViewModel.appendLiveEvidence(
            InvestigationEvidenceInput(
                slotIndex = 0,
                filename = "test.jpg",
                mimeType = "image/jpeg",
                bytes = byteArrayOf(1, 2, 3),
                source = InvestigationEvidenceSource.LIVE_GLASSES,
            ),
        )
      }
      ProjectDetailScreen(
          project = project,
          onBack = {},
          onStartWorking = {},
          onResumeInvestigation = { _, _ -> },
          onContinueProject = {},
          focusPendingReview = false,
          focusActiveInvestigation = focusActiveInvestigation,
          investigationContinuationSessionId = null,
          viewModel = detailVm,
      )
    }
  }
}
