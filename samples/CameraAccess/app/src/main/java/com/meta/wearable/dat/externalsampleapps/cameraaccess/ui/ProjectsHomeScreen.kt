/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectsHomeScreen - Project Assistant product shell entry point
//
// This is the app's new top-level launch screen (see AppRoot.kt), replacing the Meta
// CameraAccess state machine as the default thing the user sees on open. It establishes the
// approved Project Assistant visual direction: graphite background, dark elevated project rows,
// off-white/muted text, a restrained indigo accent, and a green "Active" indicator.
//
// Real backend data: the project list comes from ProjectsViewModel -> ProjectRepository -> the
// real FastAPI Project Memory API (GET /projects). ProjectSummary.projectId is the backend's own
// canonical project_id - the same identity value carried through AppRoot navigation into
// ProjectDetailScreen, with no separate Android-only id anywhere.
//
// "+ New Project" performs real Project creation (POST /projects) via NewProjectScreen, reached
// through AppRoot's TopLevelScreen.NewProject route.
// "Capture / Test Glasses" hands off to AppRoot to show the existing, unmodified Meta
// camera/capture flow.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectsHomeUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectsViewModel

@Composable
fun ProjectsHomeScreen(
    onOpenCapture: (ProjectSummary) -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel =
        viewModel(
            factory =
                ProjectsViewModel.factory(
                    LocalContext.current.applicationContext as Application,
                ),
        ),
) {
  val uiState by viewModel.uiState.collectAsState()

  // ProjectsViewModel is Activity-scoped (viewModel() retains it across AppRoot navigation), so
  // its cached list would otherwise go stale after creating a Project elsewhere and returning
  // here. Re-entering composition - which happens every time AppRoot switches back to this
  // screen - is exactly the "returning to Projects Home" signal, so refresh from the backend
  // then rather than trusting the old snapshot.
  LaunchedEffect(Unit) { viewModel.loadProjects() }

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .background(AppColor.Graphite)
              .systemBarsPadding()
              .padding(horizontal = 20.dp),
  ) {
    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = "Project Assistant",
        color = AppColor.InkPrimary,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Your projects, always in context.",
        color = AppColor.InkSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onNewProject,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = AppColor.Accent,
                contentColor = AppColor.AccentInk,
            ),
    ) {
      Text("+ New Project", fontWeight = FontWeight.SemiBold)
    }

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = "YOUR PROJECTS",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )

    Spacer(modifier = Modifier.height(10.dp))

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (val state = uiState) {
        is ProjectsHomeUiState.Loading ->
            CircularProgressIndicator(
                color = AppColor.Accent,
                modifier = Modifier.align(Alignment.Center),
            )
        is ProjectsHomeUiState.Error ->
            ProjectsHomeMessage(
                title = "Couldn't load your projects.",
                detail = state.message,
                actionLabel = "Retry",
                onAction = viewModel::loadProjects,
            )
        is ProjectsHomeUiState.Loaded ->
            if (state.projects.isEmpty()) {
              ProjectsHomeMessage(
                  title = "No projects yet.",
                  detail = "Projects you create will show up here.",
              )
            } else {
              LazyColumn(
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                  contentPadding = PaddingValues(bottom = 16.dp),
              ) {
                itemsIndexed(state.projects, key = { _, project -> project.projectId }) { index, project ->
                  ProjectRow(
                      project,
                      isActive = project.projectId == state.activeProjectId,
                      onClick = { onOpenProject(project) },
                      // Index-based, not content-based: lets instrumented tests select "the
                      // first/second row" against real backend data without hardcoding names.
                      modifier = Modifier.testTag("project_row_$index"),
                  )
                }
              }
            }
      }
    }

    val loaded = uiState as? ProjectsHomeUiState.Loaded
    val activeProject = loaded?.projects?.firstOrNull { it.projectId == loaded.activeProjectId }
    OutlinedButton(
        onClick = { activeProject?.let(onOpenCapture) },
        enabled = activeProject != null,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = AppColor.InkSecondary,
            ),
    ) {
      Text(activeProject?.let { "Use glasses for ${it.name}" } ?: "Choose a Project to use glasses")
    }
    Text(
        text = activeProject?.let { "Quick capture will be saved to ${it.name}." }
            ?: "Open a Project and make it current before using quick capture.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
    )
  }
}

@Composable
private fun ProjectsHomeMessage(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(top = 24.dp)) {
    Text(text = title, color = AppColor.InkPrimary, fontWeight = FontWeight.SemiBold)
    Text(
        text = detail,
        color = AppColor.InkSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (actionLabel != null && onAction != null) {
      OutlinedButton(
          onClick = onAction,
          modifier = Modifier.padding(top = 16.dp),
          shape = RoundedCornerShape(50),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
      ) {
        Text(actionLabel)
      }
    }
  }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Card(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = AppColor.Surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = project.name,
            color = AppColor.InkPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        // Driven by the backend's Active Project pointer (state.activeProjectId), never by
        // project.status - a Project's own lifecycle status can be "active" without it being THE
        // Active Project the user is currently working on.
        if (isActive) {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(top = 4.dp),
          ) {
            Box(
                modifier =
                    Modifier.size(7.dp).clip(CircleShape).background(AppColor.Success),
            )
            Text(
                text = "Active",
                color = AppColor.Success,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
      }
    }
  }
}
