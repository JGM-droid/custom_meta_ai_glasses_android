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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

private sealed interface TopLevelScreen {
  data object ProjectsHome : TopLevelScreen

  data object NewProject : TopLevelScreen

  data class ProjectDetail(val project: ProjectSummary) : TopLevelScreen

  data class ProjectWorkspace(val project: ProjectSummary) : TopLevelScreen

  data object Capture : TopLevelScreen
}

@Composable
fun AppRoot(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
  var topLevelScreen by remember { mutableStateOf<TopLevelScreen>(TopLevelScreen.ProjectsHome) }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Same "don't strand an active stream" rule as the visible back controls below, applied to the
  // Android system Back button/gesture too. One step back per screen - Workspace steps to that
  // same Project's Detail (never straight home), matching its own visible "‹ Overview" control,
  // so the system gesture and the on-screen button always agree.
  val canGoBack =
      when (topLevelScreen) {
        TopLevelScreen.ProjectsHome -> false
        TopLevelScreen.Capture -> !uiState.isStreaming
        TopLevelScreen.NewProject -> true
        is TopLevelScreen.ProjectDetail -> true
        is TopLevelScreen.ProjectWorkspace -> true
      }
  BackHandler(enabled = canGoBack) {
    topLevelScreen =
        when (val screen = topLevelScreen) {
          is TopLevelScreen.ProjectWorkspace -> TopLevelScreen.ProjectDetail(screen.project)
          else -> TopLevelScreen.ProjectsHome
        }
  }

  when (val screen = topLevelScreen) {
    TopLevelScreen.ProjectsHome ->
        ProjectsHomeScreen(
            onOpenCapture = { topLevelScreen = TopLevelScreen.Capture },
            onOpenProject = { project -> topLevelScreen = TopLevelScreen.ProjectDetail(project) },
            onNewProject = { topLevelScreen = TopLevelScreen.NewProject },
            modifier = modifier,
        )
    is TopLevelScreen.ProjectDetail ->
        ProjectDetailScreen(
            project = screen.project,
            onBack = { topLevelScreen = TopLevelScreen.ProjectsHome },
            onContinueProject = { project -> topLevelScreen = TopLevelScreen.ProjectWorkspace(project) },
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
            onOpenCapture = { topLevelScreen = TopLevelScreen.Capture },
            modifier = modifier,
        )
    TopLevelScreen.NewProject ->
        NewProjectScreen(
            onBack = { topLevelScreen = TopLevelScreen.ProjectsHome },
            // Navigate straight to the backend-created Project's real Project Detail - never
            // back to Projects Home first. ProjectsHomeScreen refreshes itself from the backend
            // whenever it re-enters composition, so the new Project appears there too without
            // this screen needing to inject it into any shared/cached list.
            onCreated = { project -> topLevelScreen = TopLevelScreen.ProjectDetail(project) },
            modifier = modifier,
        )
    TopLevelScreen.Capture ->
        Box(modifier = modifier.fillMaxSize()) {
          CameraAccessScaffold(
              viewModel = viewModel,
              onRequestWearablesPermission = onRequestWearablesPermission,
          )

          // Only offer a way back to Projects Home while not actively streaming. Compose
          // removing CameraAccessScaffold from composition does not stop an in-progress stream
          // (StreamViewModel is Activity-scoped, not composition-scoped), and StreamScreen's own
          // "Stop streaming" button is the existing, correct way to end a stream. Reusing
          // uiState.isStreaming (already observed by CameraAccessScaffold itself) lets this stay
          // safe without touching any protected file.
          if (!uiState.isStreaming) {
            TextButton(
                onClick = { topLevelScreen = TopLevelScreen.ProjectsHome },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = AppColor.InkPrimary),
            ) {
              Text("‹ Projects")
            }
          }
        }
  }
}
