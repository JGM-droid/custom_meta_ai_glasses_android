/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectDetailScreen - Project Overview v1 (real backend data)
//
// Renders one project's identity, current checkpoint state, and recent activity, fetched from
// the real FastAPI Project Memory backend via ProjectDetailViewModel -> ProjectRepository. The
// project header (name/status) renders immediately from the ProjectSummary already fetched for
// Projects Home; checkpoint/activity load separately and show their own Loading/Error state so a
// slow or unreachable backend never blocks identifying which project is open.
//
// This is the mechanism that proves Project A state != Project B state: the same composable body
// renders different content purely because a different project.projectId (the backend's own
// canonical id) was passed in through explicit navigation state
// (AppRoot.TopLevelScreen.ProjectDetail) - never a hardcoded per-name branch.
//
// No OpenAI calls. No mutation of any project state; this screen only reads. Honest empty states
// ("No current work recorded.", etc.) are shown for genuinely-absent backend fields rather than
// fabricated text.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ActiveProjectActionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProposalActionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview

@Composable
fun ProjectDetailScreen(
    project: ProjectSummary,
    onBack: () -> Unit,
    onStartWorking: (ProjectSummary) -> Unit,
    onResumeInvestigation: (ProjectSummary, String) -> Unit,
    onContinueProject: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectDetailViewModel =
        viewModel(
            // Explicit per-project key (not Compose's default class-name-only key) - guarantees
            // a fresh ViewModel/fetch for every distinct project_id, even across two projects
            // opened back-to-back in the same Activity/ViewModelStore, rather than relying on
            // this composable's call-site position to happen to disambiguate them.
            key = project.projectId,
            factory =
                ProjectDetailViewModel.Factory(
                    application = LocalContext.current.applicationContext as Application,
                    projectId = project.projectId,
                ),
        ),
) {
  // Re-entering this destination after Capture/Investigation must refresh the existing keyed
  // ViewModel so the newly projected inferred RESULT Activity is visible immediately.
  LaunchedEffect(project.projectId) { viewModel.loadOverview() }
  val uiState by viewModel.uiState.collectAsState()
  val activeActionState by viewModel.activeActionState.collectAsState()
  val proposalActionState by viewModel.proposalActionState.collectAsState()

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .background(AppColor.Graphite)
              .systemBarsPadding()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 20.dp),
  ) {
    TextButton(
        onClick = onBack,
        colors = ButtonDefaults.textButtonColors(contentColor = AppColor.InkPrimary),
    ) {
      Text("‹ Projects")
    }

    // The project's identity is already known (it came from Projects Home) - always shown, even
    // while checkpoint/activity are still loading.
    Text(
        text = project.name,
        color = AppColor.InkPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    StatusPill(status = project.status)

    when (val state = uiState) {
      is ProjectDetailUiState.Loading ->
          Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColor.Accent)
          }
      is ProjectDetailUiState.Error ->
          Column(modifier = Modifier.padding(top = 24.dp)) {
            Text(
                text = "Couldn't load this project's state.",
                color = AppColor.InkPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.message, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(
                onClick = viewModel::loadOverview,
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
            ) {
              Text("Retry")
            }
          }
      is ProjectDetailUiState.Loaded -> {
        val overview = state.overview
        ActiveProjectControl(
            isActive = state.isActive,
            actionState = activeActionState,
            onWorkOnProject = viewModel::setActiveProject,
            onStopWorking = viewModel::clearActiveProject,
        )
        ProjectSection(
            title = "Where We Left Off",
            body = overview.checkpoint.whereWeLeftOff ?: "No current work recorded.",
        )

        overview.latestInvestigation?.let { investigation ->
          SavedInvestigationSection(
              investigation = investigation,
              onResumeFollowUp = { sessionId -> onResumeInvestigation(project, sessionId) },
          )
        }
        if (overview.pendingProposals.isNotEmpty()) {
          PendingProposalsSection(
              proposals = overview.pendingProposals,
              actionState = proposalActionState,
              onApply = viewModel::applyProposal,
              onReject = viewModel::rejectProposal,
          )
        }
        overview.investigationLoadError?.let {
          Text("Saved Investigation details unavailable: $it", color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 16.dp))
        }
        ProjectSection(
            title = "Next Action",
            body = overview.checkpoint.nextAction ?: "No next action recorded.",
        )

        Text(
            text = "RECENT ACTIVITY",
            color = AppColor.InkSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 24.dp),
        )
        RecentActivityList(overview)

        Button(
            onClick = { onStartWorking(project) },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = AppColor.Accent,
                    contentColor = AppColor.AccentInk,
                ),
        ) {
          Text("Start Working with Glasses", fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = { onContinueProject(project) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
        ) {
          Text("Continue Project", fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
private fun SavedInvestigationSection(
    investigation: SavedInvestigationReview,
    onResumeFollowUp: (String) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF24262B)).padding(16.dp),
  ) {
    Text("RECENT INVESTIGATION", color = AppColor.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text("Your Investigation was saved.", color = AppColor.Success, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text("Status: ${investigation.status}", color = AppColor.InkSecondary, fontSize = 12.sp)
    Text("Completed ${investigation.completedAtUtc}", color = AppColor.InkSecondary, fontSize = 12.sp)
    Text("Session ${investigation.sessionId}", color = AppColor.InkSecondary, fontSize = 11.sp)
    Text("${investigation.evidenceCount} evidence item(s) saved", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 8.dp))
    investigation.retainedImage?.let { bytes ->
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Retained Investigation evidence",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 10.dp).clip(RoundedCornerShape(12.dp)),
        )
      }
    } ?: Text("Saved image preview unavailable.", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 8.dp))
    investigation.explanation?.let {
      Text("Your explanation", color = AppColor.InkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
      Text(it, color = AppColor.InkPrimary)
    }
    Text("AI inference — unconfirmed", color = AppColor.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Text(investigation.hypothesis, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp))
    Text("Recommended next action", color = AppColor.InkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    Text(investigation.recommendedNextAction, color = AppColor.InkPrimary)
    Text("Decision: ${investigation.trustDecision ?: "Not decided"}", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 12.dp))
    investigation.followUpSessionId?.let { sessionId ->
      OutlinedButton(
          onClick = { onResumeFollowUp(sessionId) },
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) { Text("Resume More Evidence") }
    }

  }
}

@Composable
private fun PendingProposalsSection(
    proposals: List<com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.CheckpointProposalReview>,
    actionState: ProposalActionState,
    onApply: (String) -> Unit,
    onReject: (String) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF24262B)).padding(16.dp),
  ) {
    proposals.forEach { proposal ->
      Text("PENDING PROJECT UPDATE", color = AppColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
      Text("Pending approval", color = AppColor.InkSecondary)
      proposal.proposedFields.filterValues { value -> value != null }.forEach { (field, value) ->
        Text("${field.replace('_', ' ')} → $value", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 6.dp))
      }
      val busy = actionState is ProposalActionState.InProgress
      Button(
          onClick = { onApply(proposal.proposalId) },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) { Text("Apply update") }
      OutlinedButton(
          onClick = { onReject(proposal.proposalId) },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) { Text("Reject") }
    }
    when (actionState) {
      is ProposalActionState.Failed -> Text(actionState.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
      is ProposalActionState.Succeeded -> Text(actionState.message, color = AppColor.Success, modifier = Modifier.padding(top = 8.dp))
      else -> Unit
    }
  }
}

// Deliberately consumer-facing wording only ("Work on this Project" / "Stop Working on
// Project") - never exposes backend terminology like ActiveProjectPointer. isActive reflects
// whether THIS viewed project is the backend's one Active Project; it is never assumed true just
// because the user opened this screen (VIEWING a project never implies it is ACTIVE).
// Not private: reused as-is by ProjectWorkspaceScreen so Workspace's Active Project control can
// never drift from Project Detail's - see the Project Workspace v1 slice.
@Composable
internal fun ActiveProjectControl(
    isActive: Boolean,
    actionState: ActiveProjectActionState,
    onWorkOnProject: () -> Unit,
    onStopWorking: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val isInProgress = actionState is ActiveProjectActionState.InProgress

  Column(modifier = modifier.padding(top = 16.dp)) {
    if (isActive) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(AppColor.Success))
        Text(
            text = "Active Project",
            color = AppColor.Success,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
      }
    }

    OutlinedButton(
        onClick = if (isActive) onStopWorking else onWorkOnProject,
        enabled = !isInProgress,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = if (isActive) AppColor.InkSecondary else AppColor.Accent,
            ),
    ) {
      if (isInProgress) {
        CircularProgressIndicator(color = AppColor.Accent, modifier = Modifier.size(18.dp))
      } else {
        Text(
            text = if (isActive) "Stop Working on Project" else "Work on this Project",
            fontWeight = FontWeight.SemiBold,
        )
      }
    }

    val failure = actionState as? ActiveProjectActionState.Failed
    if (failure != null) {
      Text(
          text = failure.message,
          color = Color(0xFFFF9B9B),
          modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

@Composable
private fun RecentActivityList(overview: ProjectOverview) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
    if (overview.recentActivity.isEmpty()) {
      Text(text = "No recent activity.", color = AppColor.InkSecondary)
    } else {
      overview.recentActivity.forEach { entry ->
        Row(modifier = Modifier.fillMaxWidth()) {
          Box(
              modifier =
                  Modifier.padding(top = 7.dp).size(5.dp).clip(RoundedCornerShape(50)).background(AppColor.InkSecondary),
          )
          Text(
              text = entry.summary,
              color = AppColor.InkPrimary,
              modifier = Modifier.padding(start = 10.dp).weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun StatusPill(status: String, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .padding(top = 10.dp)
              .clip(RoundedCornerShape(50))
              .background(AppColor.Surface)
              .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Text(text = status, color = AppColor.InkSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
  }
}

// Not private: reused as-is by ProjectWorkspaceScreen for Where We Left Off / Next Action so the
// two screens can never render the same checkpoint fields with visually different treatment.
@Composable
internal fun ProjectSection(title: String, body: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(top = 20.dp)) {
    Text(
        text = title.uppercase(),
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    Text(
        text = body,
        color = AppColor.InkPrimary,
        modifier = Modifier.padding(top = 6.dp),
    )
  }
}
