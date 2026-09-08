/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AppRoot - Project Assistant top-level product shell
//
// Owns the top-level navigation state this slice introduces: Projects Home (the new default),
// a specific project's Project Overview (ProjectDetailScreen), that project's placeholder
// workspace (ProjectWorkspaceScreen, reached via "Continue Project"), a "New Project"
// placeholder, and Capture (the existing, untouched Meta camera/capture flow, entered
// intentionally from Projects Home). This mirrors the same simple state-driven `when` pattern
// CameraAccessScaffold already uses internally - no Navigation Compose dependency needed for
// five destinations.
//
// CameraAccessScaffold, HomeScreen, NonStreamScreen, StreamScreen, and the DAT SDK state machine
// inside them are rendered completely unchanged when TopLevelScreen.Capture is active; this file
// does not modify their behavior. Selecting a project is explicit (the tapped card's data -
// carrying the backend's own canonical project_id via ProjectSummary, see the `projects`
// package - is carried in TopLevelScreen.ProjectDetail). This file itself makes no backend
// calls and never mutates Project Memory; ProjectsHomeScreen/ProjectDetailScreen's own
// ViewModels perform only read-only GET requests.
//
// Project-Scoped Glasses Capture: TopLevelScreen.Capture now optionally carries a
// `sourceProject: ProjectSummary?` - the EXPLICIT Project context Capture was entered from, if
// any. Entering Capture from a Project Workspace passes that Workspace's own project (screen.
// project, never a lookup); entering from the existing global "Capture / Test Glasses" on
// Projects Home passes null, preserving that entry point's existing unscoped/Active-Project-
// fallback behavior exactly (see docs/PROJECT_MEMORY_ARCHITECTURE.md ADR-037, which the backend
// already implements and this slice only threads the explicit id into). Carrying a project here
// does NOT call setActiveProject() - viewing/capturing for a Project and that Project being
// Active remain deliberately separate concepts, exactly as established for Workspace's own
// Active Project control.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// Aliased: this file's AppRoot() already has a parameter named `viewModel` (the WearablesViewModel
// - see below), which would otherwise shadow this composable function of the same name.
import androidx.lifecycle.viewmodel.compose.viewModel as rememberViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudPhoneDestination
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

private const val TAG = "CameraAccess:AppRoot"

private sealed interface TopLevelScreen {
  data object ProjectsHome : TopLevelScreen

  data object NewProject : TopLevelScreen

  data class ProjectDetail(
      val project: ProjectSummary,
      val focusReview: Boolean = false,
      // Set when this screen is the "Continue on phone" landing for an active, pre-Analyze
      // investigation (ProjectHudPhoneDestination.ACTIVE_INVESTIGATION - see
      // ProjectContinuityHudState.kt's phoneHandoff()) - tells ProjectDetailScreen to scroll
      // straight to its ContinueInvestigationSection instead of leaving the user to find it.
      val focusActiveInvestigation: Boolean = false,
      // The exact continuationSessionId StreamScreen was already using when Continue on phone
      // fired (null for a fresh, not-yet-submitted investigation) - reused so
      // ContinueInvestigationSection resolves the SAME investigationViewModelKey instance rather
      // than a fresh, empty one. See ProjectDetailScreen.kt's ContinueInvestigationSection doc.
      val activeInvestigationSessionId: String? = null,
  ) : TopLevelScreen

  data class ProjectWorkspace(val project: ProjectSummary) : TopLevelScreen

  data class ProjectConversation(
      val project: ProjectSummary,
      val glassesEvidencePending: Boolean = false,
      val glassesContinuationSessionId: String? = null,
  ) : TopLevelScreen

  // sourceProject: the explicit Project Capture was entered from (Workspace), or null for the
  // existing global entry point (Projects Home) - see file header.
  data class Capture(
      val sourceProject: ProjectSummary? = null,
      val continuationSessionId: String? = null,
      val returnToConversation: Boolean = false,
  ) : TopLevelScreen
}

private val TopLevelScreenSaver = listSaver<TopLevelScreen, String>(
    save = { screen ->
      when (screen) {
        TopLevelScreen.ProjectsHome -> listOf("home", "", "", "")
        TopLevelScreen.NewProject -> listOf("new", "", "", "")
        is TopLevelScreen.ProjectDetail ->
            listOf(
                "detail",
                screen.project.projectId,
                screen.project.name,
                screen.project.status,
                screen.focusReview.toString(),
                screen.focusActiveInvestigation.toString(),
                screen.activeInvestigationSessionId.orEmpty(),
            )
        is TopLevelScreen.ProjectWorkspace -> listOf("workspace", screen.project.projectId, screen.project.name, screen.project.status)
        is TopLevelScreen.ProjectConversation -> listOf(
            "conversation",
            screen.project.projectId,
            screen.project.name,
            screen.project.status,
            screen.glassesEvidencePending.toString(),
            screen.glassesContinuationSessionId.orEmpty(),
        )
        is TopLevelScreen.Capture -> listOf(
            "capture",
            screen.sourceProject?.projectId.orEmpty(),
            screen.sourceProject?.name.orEmpty(),
            screen.sourceProject?.status.orEmpty(),
            screen.continuationSessionId.orEmpty(),
            screen.returnToConversation.toString(),
        )
      }
    },
    restore = { saved ->
      val project = saved.getOrNull(1)?.takeIf(String::isNotBlank)?.let {
        ProjectSummary(it, saved.getOrElse(2) { "" }, saved.getOrElse(3) { "active" })
      }
      when (saved.firstOrNull()) {
        "new" -> TopLevelScreen.NewProject
        "detail" ->
            project?.let {
              TopLevelScreen.ProjectDetail(
                  it,
                  focusReview = saved.getOrNull(4).toBoolean(),
                  focusActiveInvestigation = saved.getOrNull(5).toBoolean(),
                  activeInvestigationSessionId = saved.getOrNull(6)?.takeIf(String::isNotBlank),
              )
            } ?: TopLevelScreen.ProjectsHome
        "workspace" -> project?.let(TopLevelScreen::ProjectWorkspace) ?: TopLevelScreen.ProjectsHome
        "conversation" -> project?.let {
          TopLevelScreen.ProjectConversation(
              it,
              glassesEvidencePending = saved.getOrNull(4).toBoolean(),
              glassesContinuationSessionId = saved.getOrNull(5)?.takeIf(String::isNotBlank),
          )
        } ?: TopLevelScreen.ProjectsHome
        "capture" -> TopLevelScreen.Capture(
            project,
            saved.getOrNull(4)?.takeIf(String::isNotBlank),
            returnToConversation = saved.getOrNull(5).toBoolean(),
        )
        else -> TopLevelScreen.ProjectsHome
      }
    },
)

@Composable
fun AppRoot(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
  var topLevelScreen by rememberSaveable(stateSaver = TopLevelScreenSaver) {
    mutableStateOf<TopLevelScreen>(TopLevelScreen.ProjectsHome)
  }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Same "don't strand an active stream" rule as the visible back controls below, applied to the
  // Android system Back button/gesture too. One step back per screen - Workspace steps to that
  // same Project's Detail (never straight home), matching its own visible "‹ Overview" control,
  // so the system gesture and the on-screen button always agree.
  val canGoBack =
      when (topLevelScreen) {
        TopLevelScreen.ProjectsHome -> false
        is TopLevelScreen.Capture -> !uiState.isStreaming
        TopLevelScreen.NewProject -> true
        is TopLevelScreen.ProjectDetail -> true
        is TopLevelScreen.ProjectWorkspace -> true
        is TopLevelScreen.ProjectConversation -> true
      }
  BackHandler(enabled = canGoBack) {
    topLevelScreen =
        when (val screen = topLevelScreen) {
          is TopLevelScreen.ProjectWorkspace -> TopLevelScreen.ProjectDetail(screen.project)
          is TopLevelScreen.ProjectConversation -> TopLevelScreen.ProjectsHome
          // Return toward the same Project context Capture was entered from, where practical
          // (Phase 10) - the unscoped global entry point (sourceProject == null) keeps its
          // existing "back to Projects Home" behavior exactly.
          is TopLevelScreen.Capture ->
              screen.sourceProject?.let {
                if (screen.returnToConversation) TopLevelScreen.ProjectConversation(it)
                else TopLevelScreen.ProjectDetail(it)
              } ?: TopLevelScreen.ProjectsHome
          else -> TopLevelScreen.ProjectsHome
        }
  }

  when (val screen = topLevelScreen) {
    TopLevelScreen.ProjectsHome ->
        ProjectsHomeScreen(
            // Quick capture is only offered for the canonical Active Project and that identity
            // is carried explicitly; capture never silently relies on backend fallback.
            onOpenCapture = { project ->
              topLevelScreen = TopLevelScreen.Capture(sourceProject = project)
            },
            onOpenProject = { project -> topLevelScreen = TopLevelScreen.ProjectConversation(project) },
            onNewProject = { topLevelScreen = TopLevelScreen.NewProject },
            modifier = modifier,
        )
    is TopLevelScreen.ProjectDetail ->
        ProjectDetailScreen(
            project = screen.project,
            onBack = { topLevelScreen = TopLevelScreen.ProjectsHome },
            // Directly enter the already-working DAT Capture/Investigation flow with THIS
            // Project as explicit context. This is navigation only: it never calls
            // setActiveProject(), so explicit Project attribution remains independent from the
            // backend's global Active Project pointer.
            onStartWorking = {
              project -> topLevelScreen = TopLevelScreen.Capture(sourceProject = project)
            },
            onResumeInvestigation = { project, sessionId ->
              topLevelScreen = TopLevelScreen.Capture(project, sessionId)
            },
            onContinueProject = { project -> topLevelScreen = TopLevelScreen.ProjectWorkspace(project) },
            focusPendingReview = screen.focusReview,
            focusActiveInvestigation = screen.focusActiveInvestigation,
            investigationContinuationSessionId = screen.activeInvestigationSessionId,
            modifier = modifier,
        )
    is TopLevelScreen.ProjectWorkspace ->
        ProjectWorkspaceScreen(
            project = screen.project,
            // Steps back to THIS SAME Project's Detail/Overview, not straight to Projects Home -
            // matches the system Back gesture above and the product flow's own "Back should
            // return appropriately" requirement. screen.project (not a lookup) keeps identity
            // explicit, so this can never land on the wrong Project's Detail.
            onBack = { topLevelScreen = TopLevelScreen.ProjectDetail(screen.project) },
            // Explicit Project context travels with the workflow (CORE SAFETY RULE): this
            // Workspace's own canonical project - never a lookup, never the Active Project -
            // becomes Capture's sourceProject, so it wins over Active Project no matter what is
            // currently marked Active.
            onOpenCapture = { topLevelScreen = TopLevelScreen.Capture(sourceProject = screen.project) },
            modifier = modifier,
        )
    is TopLevelScreen.ProjectConversation -> {
      // ADR-061 conversation handoff (Blocker 2): only constructed when there is actually
      // glasses evidence to adopt this composition - StreamViewModel is Activity-scoped (see its
      // class doc) and resolves to the SAME instance StreamScreen itself uses (no explicit key on
      // either side - both rely on the default class-name key), so this never creates a second,
      // competing instance. Never touched for the common case (opening a conversation with no
      // pending glasses evidence at all).
      val application = LocalContext.current.applicationContext as Application
      val streamViewModel: StreamViewModel? =
          if (screen.glassesEvidencePending) {
            rememberViewModel(factory = StreamViewModel.Factory(application, viewModel))
          } else {
            null
          }
      ProjectConversationScreen(
          project = screen.project,
          onBack = { topLevelScreen = TopLevelScreen.ProjectsHome },
          onProjectDetails = { topLevelScreen = TopLevelScreen.ProjectDetail(screen.project) },
          onUseGlasses = {
            Log.d(TAG, "onUseGlasses: entering Capture(returnToConversation=true) for project=${screen.project.projectId}")
            topLevelScreen = TopLevelScreen.Capture(
                sourceProject = screen.project,
                returnToConversation = true,
            )
          },
          glassesConnected = uiState.hasActiveDevice,
          glassesEvidencePending = screen.glassesEvidencePending,
          glassesContinuationSessionId = screen.glassesContinuationSessionId,
          onGlassesEvidenceAdopted = {
            Log.d(TAG, "onGlassesEvidenceAdopted: evidence adopted into conversation for project=${screen.project.projectId}")
            // Tells the HUD control has moved to the phone (see StreamViewModel's doc) BEFORE
            // resetting glassesEvidencePending back to false - a defensive second trigger for the
            // same neutral handoff dispatchPhone() already renders on a Continue-on-phone tap
            // (see ProjectContinuityHudController.dispatchPhone's doc), never a replacement for it.
            streamViewModel?.acknowledgePhoneControl()
            topLevelScreen = TopLevelScreen.ProjectConversation(screen.project)
          },
          modifier = modifier,
      )
    }
    TopLevelScreen.NewProject ->
        NewProjectScreen(
            onBack = { topLevelScreen = TopLevelScreen.ProjectsHome },
            // Navigate straight to the backend-created Project's real Project Detail - never
            // back to Projects Home first. ProjectsHomeScreen refreshes itself from the backend
            // whenever it re-enters composition, so the new Project appears there too without
            // this screen needing to inject it into any shared/cached list.
            onCreated = { project -> topLevelScreen = TopLevelScreen.ProjectConversation(project) },
            modifier = modifier,
        )
    is TopLevelScreen.Capture ->
        Box(modifier = modifier.fillMaxSize()) {
          CameraAccessScaffold(
              viewModel = viewModel,
              onRequestWearablesPermission = onRequestWearablesPermission,
              sourceProjectId = screen.sourceProject?.projectId,
              sourceProjectName = screen.sourceProject?.name,
              continuationSessionId = screen.continuationSessionId,
              returnToConversation = screen.returnToConversation,
              onReturnToSourceProject =
                  screen.sourceProject?.let { project ->
                    {
                      topLevelScreen =
                          if (screen.returnToConversation) TopLevelScreen.ProjectConversation(project)
                          else TopLevelScreen.ProjectDetail(project)
                    }
                  },
              onProjectHudPhoneHandoff =
                  screen.sourceProject?.let { project ->
                    { destination, continuationSessionId ->
                      Log.d(TAG, "onProjectHudPhoneHandoff: destination=$destination continuationSessionId=$continuationSessionId returnToConversation=${screen.returnToConversation} project=${project.projectId}")
                      topLevelScreen = if (screen.returnToConversation) {
                        TopLevelScreen.ProjectConversation(
                            project,
                            glassesEvidencePending = true,
                            glassesContinuationSessionId = continuationSessionId,
                        )
                      } else {
                        TopLevelScreen.ProjectDetail(
                            project,
                            focusReview = destination == ProjectHudPhoneDestination.PROJECT_REVIEW,
                            focusActiveInvestigation = destination == ProjectHudPhoneDestination.ACTIVE_INVESTIGATION,
                            activeInvestigationSessionId = continuationSessionId,
                        )
                      }
                    }
                  },
          )

          // Only offer a way back while not actively streaming. Compose removing
          // CameraAccessScaffold from composition does not stop an in-progress stream
          // (StreamViewModel is Activity-scoped, not composition-scoped), and StreamScreen's own
          // "Stop streaming" button is the existing, correct way to end a stream. Reusing
          // uiState.isStreaming (already observed by CameraAccessScaffold itself) lets this stay
          // safe without touching any protected file.
          if (!uiState.isStreaming) {
            Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
              TextButton(
                  onClick = {
                    // Return toward the same Project context Capture was entered from (Phase 10),
                    // matching the system Back gesture above.
                    topLevelScreen =
                        screen.sourceProject?.let { TopLevelScreen.ProjectDetail(it) }
                            ?: TopLevelScreen.ProjectsHome
                  },
                  colors = ButtonDefaults.textButtonColors(contentColor = AppColor.InkPrimary),
              ) {
                Text(if (screen.sourceProject != null) "‹ ${screen.sourceProject.name}" else "‹ Projects")
              }

              // Subtle capture-context indicator (Phase 6) - AppRoot-level overlay only, never
              // touches CameraAccessScaffold/NonStreamScreen/StreamScreen, and never covers
              // camera controls (only shown alongside the back control above, which is itself
              // already hidden while streaming).
              screen.sourceProject?.let { project ->
                Text(
                    text = "Capturing for ${project.name}",
                    color = AppColor.InkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
              }
            }
          }
        }
  }
}
