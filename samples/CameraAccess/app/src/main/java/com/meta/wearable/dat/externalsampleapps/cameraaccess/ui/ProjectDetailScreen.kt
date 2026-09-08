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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.deriveInvestigationProductState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.hasActiveInvestigation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationReopenAffordanceLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ActiveProjectActionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanApplyState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectGuidanceViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProposalActionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactViewModel
import kotlinx.coroutines.launch

internal enum class ProjectPrimaryAction { ADD_EVIDENCE, REVIEW_CHANGES, USE_GLASSES }

internal fun projectPrimaryAction(overview: ProjectOverview): ProjectPrimaryAction =
    when {
      overview.latestInvestigation?.followUpSessionId != null -> ProjectPrimaryAction.ADD_EVIDENCE
      overview.pendingProposals.isNotEmpty() -> ProjectPrimaryAction.REVIEW_CHANGES
      else -> ProjectPrimaryAction.USE_GLASSES
    }

@Composable
fun ProjectDetailScreen(
    project: ProjectSummary,
    onBack: () -> Unit,
    onStartWorking: (ProjectSummary) -> Unit,
    // Nullable sessionId: the pre-Analyze "Continue on phone -> add context -> Resume on glasses"
    // handoff (see ContinueInvestigationSection below) has no backend session yet at this point in
    // the flow - unlike the post-trust-decision "Add evidence" follow-up below, which always has a
    // real followUpSessionId. Both resolve to the same TopLevelScreen.Capture(project,
    // continuationSessionId) navigation either way (AppRoot.kt), which already accepts null.
    onResumeInvestigation: (ProjectSummary, String?) -> Unit,
    onContinueProject: (ProjectSummary) -> Unit,
    focusPendingReview: Boolean = false,
    // The "Continue on phone" landing for an ACTIVE_INVESTIGATION handoff (see
    // ProjectContinuityHudState.kt's phoneHandoff()) - proven physical gap: without this, the
    // phone opened generic Project Detail and the user had to hunt for their own just-captured
    // photos. See ContinueInvestigationSection below.
    focusActiveInvestigation: Boolean = false,
    // The exact continuationSessionId the glasses side (StreamScreen) was already using - reused
    // so ContinueInvestigationSection resolves the SAME investigationViewModelKey instance. Null
    // for a fresh, not-yet-submitted investigation, which is the common case for this handoff.
    investigationContinuationSessionId: String? = null,
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
        val primaryAction = projectPrimaryAction(overview)
        val proposalRequester = remember { BringIntoViewRequester() }
        val scope = rememberCoroutineScope()
        // Hoisted here (not inside ExplorePlanSection) so ContinueInvestigationSection's panel can
        // trigger the SAME instance via ADR-060's automatic guidance routing (adoptExternallyProducedPlan,
        // dispatched from the guidanceUiState LaunchedEffect below - never an explicit mode-selector
        // button, which ADR-060 removed) - one Explore-plan session per Project, reachable from either
        // surface.
        val explorePlanApplication = LocalContext.current.applicationContext as Application
        val explorePlanViewModel: ExplorePlanViewModel =
            viewModel(
                key = "explore-plan:${project.projectId}",
                factory = ExplorePlanViewModel.Factory(application = explorePlanApplication, projectId = project.projectId),
            )
        // ADR-060: hoisted here (not inside ContinueInvestigationSection) for the same reason
        // explorePlanViewModel is - a fresh instance per distinct Project, not recreated on every
        // sheet open/close, so its Requesting/ClarificationNeeded/Failed state survives exactly as
        // long as the user is still working the SAME guidance request.
        val guidanceViewModel: ProjectGuidanceViewModel =
            viewModel(
                key = "project-guidance:${project.projectId}",
                factory = ProjectGuidanceViewModel.Factory(application = explorePlanApplication, projectId = project.projectId),
            )
        val visualArtifactViewModel: VisualArtifactViewModel =
            viewModel(
                key = "visual-artifacts:${project.projectId}",
                factory = VisualArtifactViewModel.Factory(explorePlanApplication, project.projectId),
            )
        LaunchedEffect(focusPendingReview, overview.pendingProposals.size) {
          if (focusPendingReview && overview.pendingProposals.isNotEmpty()) {
            proposalRequester.bringIntoView()
          }
        }
        ActiveProjectControl(
            isActive = state.isActive,
            actionState = activeActionState,
            onWorkOnProject = viewModel::setActiveProject,
            onStopWorking = viewModel::clearActiveProject,
        )
        Text("RESUME / NEEDS ATTENTION", color = AppColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
        ProjectSection(title = "Where you left off", body = overview.checkpoint.whereWeLeftOff ?: "No current work recorded.")
        ProjectSection(title = "Next action", body = overview.checkpoint.nextAction ?: "No next action recorded.")
        if (overview.pendingProposals.isNotEmpty()) {
          Text(
              "Investigation saved to ${project.name} — ${overview.pendingProposals.size} suggested change${if (overview.pendingProposals.size == 1) "" else "s"} need review.",
              color = AppColor.Success,
              modifier = Modifier.padding(top = 12.dp),
          )
        } else if (overview.latestInvestigation != null) {
          Text("Investigation saved to ${project.name}.", color = AppColor.Success, modifier = Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = {
              when (primaryAction) {
                ProjectPrimaryAction.ADD_EVIDENCE -> overview.latestInvestigation?.followUpSessionId?.let { onResumeInvestigation(project, it) }
                ProjectPrimaryAction.REVIEW_CHANGES -> scope.launch { proposalRequester.bringIntoView() }
                ProjectPrimaryAction.USE_GLASSES -> onStartWorking(project)
              }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
        ) {
          Text(
              when (primaryAction) {
                ProjectPrimaryAction.ADD_EVIDENCE -> "Add more evidence"
                ProjectPrimaryAction.REVIEW_CHANGES -> "Review ${overview.pendingProposals.size} suggested change${if (overview.pendingProposals.size == 1) "" else "s"}"
                ProjectPrimaryAction.USE_GLASSES -> "Use glasses for this Project"
              },
              fontWeight = FontWeight.SemiBold,
          )
        }

        ContinueInvestigationSection(
            project = project,
            continuationSessionId = investigationContinuationSessionId,
            focusActiveInvestigation = focusActiveInvestigation,
            onResumeOnGlasses = { sessionId -> onResumeInvestigation(project, sessionId) },
            // "Looks right"/"Not quite" only ever change the CANONICAL Project's latestInvestigation
            // (SavedInvestigationSection below reads it) - reload so returning from the sheet shows
            // it fresh rather than whatever this screen's own overview fetch last saw.
            onInvestigationDecided = viewModel::loadOverview,
            explorePlanViewModel = explorePlanViewModel,
            guidanceViewModel = guidanceViewModel,
            visualArtifactViewModel = visualArtifactViewModel,
        )

        ExplorePlanSection(
            explorePlanViewModel = explorePlanViewModel,
            visualArtifactViewModel = visualArtifactViewModel,
            existingResultId = overview.latestExplorePlan?.resultId,
            // Apply/Reject changes the CANONICAL checkpoint/pendingProposals list this screen also
            // reads below - reload so both this section's own state and the generic pending-changes
            // safety net (PendingProposalsSection) reflect it, same reasoning as onInvestigationDecided.
            onProjectChanged = viewModel::loadOverview,
        )

        // Deliberately NOT filtered to exclude Explore-selection proposals: ExplorePlanSection
        // above reviews/applies those inline once ITS OWN ViewModel has reconstructed them (see
        // ExplorePlanViewModel.reconcileSelectionFromCanonicalState), but that reconstruction is
        // best-effort and can legitimately fail or not have run yet (e.g. right after process
        // death). This generic, always-on safety net guarantees a pending proposal remains
        // reachable and Apply-able even then - occasionally showing the same proposal in both
        // places is an accepted minor redundancy, never an unreachable one.
        if (overview.pendingProposals.isNotEmpty()) {
          PendingProposalsSection(
              proposals = overview.pendingProposals,
              actionState = proposalActionState,
              onApply = viewModel::applyProposal,
              onReject = viewModel::rejectProposal,
              modifier = Modifier.bringIntoViewRequester(proposalRequester),
          )
        }
        overview.latestInvestigation?.let { investigation ->
          SavedInvestigationSection(
              investigation = investigation,
              onResumeFollowUp = { sessionId -> onResumeInvestigation(project, sessionId) },
          )
        }
        overview.investigationLoadError?.let {
          Text("Saved Investigation details unavailable: $it", color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 16.dp))
        }
        Text(
            text = "PROJECT HISTORY",
            color = AppColor.InkSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 24.dp),
        )
        RecentActivityList(overview)

        OutlinedButton(
            onClick = { onContinueProject(project) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
        ) {
          Text("Open Project tools", fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

/**
 * Option B closed loop's phone-side "add context, then Resume on glasses" landing (see
 * docs/ROADMAP.md's glasses-native closed loop and AGENTS.md's Continue-on-phone handoff design
 * decision). Renders only while a LOCAL, pre-Analyze investigation is in progress for this Project
 * (evidence captured on glasses, not yet submitted) - genuinely absent otherwise, matching this
 * screen's existing honest-empty-state convention.
 *
 * Proven physical gap this closes (second pass): the first version reimplemented a subset of the
 * investigation UI here (a bare evidence count + explanation field) instead of reusing the SAME
 * "blue tab" panel StreamScreen already opens on the glasses-Capture screen -
 * [BackendInvestigationPanel] behind [hasActiveInvestigation]/[investigationReopenAffordanceLabel]
 * and a [ModalBottomSheet], exactly as StreamScreen.kt's own showInvestigationReopenAffordance ->
 * showInvestigationPanel() -> ModalBottomSheet { BackendInvestigationPanel(...) } already does.
 * This is that SAME mechanism, reused unmodified, just hosted on this screen instead - not a new
 * screen, not a second investigation UI.
 *
 * Reuses [InvestigationSessionDebugViewModel] itself, keyed via [investigationViewModelKey] using
 * [continuationSessionId] - the EXACT value StreamScreen was already using for this Capture entry
 * (see ProjectDetailScreen's own doc on that param) - not a hardcoded null. This app has no
 * NavHost, so the Activity's shared ViewModelStore hands back the SAME instance StreamScreen's
 * HUD-driven Capture/Use already populated, carrying its evidence (in-memory) and explanation
 * (SavedStateHandle-backed) across the Continue-on-phone/Resume-on-glasses round trip with no new
 * persistence - whether this is a fresh investigation (null, the common case) or a resumed
 * follow-up round (a real id, reused rather than dropped).
 *
 * [focusActiveInvestigation] (true only for a ProjectHudPhoneDestination.ACTIVE_INVESTIGATION
 * handoff - see phoneHandoff()'s doc) auto-opens the sheet the moment it has evidence to show -
 * the proven physical gap: without it, the user had to notice and tap a reopen affordance
 * themselves instead of landing directly in the panel. `onCaptureAnotherView = null` here (unlike
 * StreamScreen's own usage) since this phone-only landing has no live glasses stream to capture
 * another view from - [BackendInvestigationPanel] already hides that specific button when null,
 * an existing conditional in that same component, not a new special case. "Resume on glasses" is
 * exactly [onResumeOnGlasses]'s existing Capture(project, continuationSessionId) navigation
 * (AppRoot.kt) - the same primitive the post-trust-decision "Add evidence" follow-up already uses
 * below - added here specifically because BackendInvestigationPanel itself has no notion of
 * "glasses" to resume to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueInvestigationSection(
    project: ProjectSummary,
    continuationSessionId: String?,
    focusActiveInvestigation: Boolean,
    onResumeOnGlasses: (String?) -> Unit,
    onInvestigationDecided: () -> Unit = {},
    // Rich Project Intelligence V1 (ADR-059) Bug 2 fix: the SAME hoisted instance
    // ExplorePlanSection uses (see ProjectDetailScreen's Loaded branch) - never a second,
    // independent Explore-plan session for this Project.
    explorePlanViewModel: ExplorePlanViewModel,
    // ADR-060: the SAME hoisted instance for this Project - one Get Guidance request lifecycle,
    // never a second competing one.
    guidanceViewModel: ProjectGuidanceViewModel,
    visualArtifactViewModel: VisualArtifactViewModel,
    modifier: Modifier = Modifier,
) {
  val application = LocalContext.current.applicationContext as Application
  val investigationViewModel: InvestigationSessionDebugViewModel =
      viewModel(
          key = investigationViewModelKey(project.projectId, continuationSessionId),
          factory =
              InvestigationSessionDebugViewModel.factory(
                  application = application,
                  sourceProjectId = project.projectId,
                  initialContinuationSessionId = continuationSessionId,
              ),
      )
  val investigationUiState by investigationViewModel.uiState.collectAsState()
  if (!hasActiveInvestigation(investigationUiState)) return

  var isPanelVisible by remember { mutableStateOf(false) }
  // ADR-060: no longer an explicit, user-picked toggle - it now follows the backend's own Response
  // Planner decision (guidanceUiState below) automatically. Reset per sheet-open so reopening this
  // sheet later always starts back on the single guidance composer.
  var showingExplorePlan by remember { mutableStateOf(false) }
  val guidanceUiState by guidanceViewModel.uiState.collectAsState()
  LaunchedEffect(focusActiveInvestigation) {
    if (focusActiveInvestigation) isPanelVisible = true
  }

  // ADR-060: dispatches a Ready guidance result to wherever it belongs, automatically - the user
  // never chooses TROUBLESHOOT/EXPLORE_PLAN/GENERAL_GUIDANCE themselves.
  LaunchedEffect(guidanceUiState) {
    val ready = guidanceUiState as? ProjectGuidanceUiState.Ready ?: return@LaunchedEffect
    when (val result = ready.result) {
      is ProjectGuidanceResult.ExplorePlan -> {
        // Hands off to the existing, unchanged rich 3-option panel/state machine - Select ->
        // Proposal -> Apply remains entirely explorePlanViewModel's own from here.
        explorePlanViewModel.adoptExternallyProducedPlan(result.result)
        showingExplorePlan = true
        guidanceViewModel.reset()
      }
      is ProjectGuidanceResult.TroubleshootEvidence -> {
        // The backend already ran the existing evidence-backed Investigation analyze pipeline for
        // this request. Close back onto the plain Project Detail page and reload so the existing
        // SavedInvestigationSection/trust UI (Looks right / Add more info / Not quite) - already
        // driven by ProjectOverview.latestInvestigation - picks it up exactly as it already does
        // for any completed Investigation. Never a second, duplicate trust surface here.
        isPanelVisible = false
        guidanceViewModel.reset()
        onInvestigationDecided()
      }
      // TroubleshootText/GeneralGuidance render inline within BackendInvestigationPanel's own
      // guidance param below - nothing to dispatch here.
      else -> Unit
    }
  }

  OutlinedButton(
      onClick = { isPanelVisible = true },
      modifier = modifier.fillMaxWidth().padding(top = 24.dp),
      shape = RoundedCornerShape(16.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
  ) {
    Text(investigationReopenAffordanceLabel(investigationUiState), fontWeight = FontWeight.SemiBold)
  }

  if (isPanelVisible) {
    ModalBottomSheet(
        onDismissRequest = {
          isPanelVisible = false
          showingExplorePlan = false
          guidanceViewModel.reset()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  // BackendInvestigationPanel owns its scroll while the composer/diagnostic
                  // surface is showing. EXPLORE_PLAN replaces that panel entirely, so this
                  // sheet branch must become the sole vertical scroll owner or options below
                  // the first viewport (including expanded details and Proposal/Apply) are
                  // unreachable on a physical phone.
                  .then(
                      if (showingExplorePlan) Modifier.verticalScroll(rememberScrollState())
                      else Modifier,
                  )
                  .padding(horizontal = 4.dp),
      ) {
        if (showingExplorePlan) {
          TextButton(onClick = { showingExplorePlan = false }) {
            Text("‹ Back to diagnostic view")
          }
          val planState by explorePlanViewModel.planState.collectAsState()
          val selectionState by explorePlanViewModel.selectionState.collectAsState()
          val applyState by explorePlanViewModel.applyState.collectAsState()
          val visualStates by visualArtifactViewModel.states.collectAsState()
          val existingResultId = (planState as? ExplorePlanUiState.Ready)?.result?.resultId
          LaunchedEffect(applyState) {
            if (applyState is ExplorePlanApplyState.Applied) onInvestigationDecided()
          }
          ExplorePlanPanel(
              planState = planState,
              selectionState = selectionState,
              applyState = applyState,
              onRequestPlan = explorePlanViewModel::requestPlan,
              onSelectOption = explorePlanViewModel::selectOption,
              onApply = explorePlanViewModel::applySelection,
              onDismissProposal = explorePlanViewModel::dismissSelection,
              onRetry = { existingResultId?.let(explorePlanViewModel::retry) },
              visualStates = visualStates,
              onVisualizeOption = visualArtifactViewModel::visualize,
              modifier = Modifier.fillMaxWidth(),
          )
          return@Column
        }
        BackendInvestigationPanel(
            modifier = Modifier.fillMaxWidth(),
            viewModel = investigationViewModel,
            sourceProjectName = project.name,
            onCaptureAnotherView = null,
            // "Immediate visible confirmation... return to the normal Project/Project Navigator
            // flow" (glasses<->phone UX simplification): closes this sheet back onto the plain
            // Project Detail page underneath, after reloading it so the just-decided result is
            // actually visible there, not stale.
            onReturnToProject = {
              isPanelVisible = false
              onInvestigationDecided()
            },
            // ADR-060: ONE natural guidance action, wired to the shared guidanceViewModel - see
            // this file's dispatch LaunchedEffect above and BackendInvestigationPanel's own doc.
            // stageEvidence runs first (multimodal bridge - see ProjectGuidanceViewModel.getGuidance's
            // doc and InvestigationSessionDebugViewModel.stagePendingEvidenceForGuidance) so any
            // accepted pending photo is backend-available before the single routed reasoning call.
            guidance = ProjectGuidanceComposerState(
                uiState = guidanceUiState,
                onGetGuidance = {
                  guidanceViewModel.getGuidance(
                      userRequest = investigationUiState.explanationText,
                      stageEvidence = investigationViewModel::stagePendingEvidenceForGuidance,
                  )
                },
            ),
        )
        val productState = remember(investigationUiState) { deriveInvestigationProductState(investigationUiState) }
        if (productState.canAnalyze) {
          Button(
              onClick = {
                isPanelVisible = false
                onResumeOnGlasses(investigationUiState.continuationSessionId)
              },
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
              shape = RoundedCornerShape(16.dp),
              colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
          ) {
            Text("Resume on glasses", fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}

/**
 * Rich Project Intelligence V1 (ADR-059) phone entry point: "request design/planning guidance ->
 * render 3 rich options -> SELECT -> proposal review -> Apply -> canonical reload". Reuses
 * ExplorePlanViewModel keyed per-Project the same way ContinueInvestigationSection above keys its
 * own InvestigationSessionDebugViewModel - a fresh instance per distinct project.projectId, never
 * shared across Projects.
 *
 * [existingResultId] (from ProjectOverview.latestExplorePlan, itself derived from the backend's
 * existing canonical Explore read projection - see ProjectBackendClient.loadLatestExplorePlanSummary)
 * reconstructs a previously-generated plan on reopen rather than requiring the user to ask again -
 * "prefer canonical backend reload over local duplication".
 *
 * [explorePlanViewModel] is hoisted by the caller (ProjectDetailScreen), NOT created here - it is
 * the SAME instance ContinueInvestigationSection's panel can also trigger via ADR-060's automatic
 * guidance routing (never an explicit mode-selector button, which ADR-060 removed - see
 * ProjectGuidanceViewModel), so a plan requested from either surface is immediately visible from
 * the other, with exactly one Explore-plan session per Project rather than two independent,
 * possibly-diverging ones.
 */
@Composable
private fun ExplorePlanSection(
    explorePlanViewModel: ExplorePlanViewModel,
    visualArtifactViewModel: VisualArtifactViewModel,
    existingResultId: String?,
    onProjectChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val planState by explorePlanViewModel.planState.collectAsState()
  val selectionState by explorePlanViewModel.selectionState.collectAsState()
  val applyState by explorePlanViewModel.applyState.collectAsState()
  val visualStates by visualArtifactViewModel.states.collectAsState()

  LaunchedEffect(existingResultId) {
    if (existingResultId != null && planState is ExplorePlanUiState.Idle) {
      explorePlanViewModel.loadExistingPlan(existingResultId)
    }
  }
  // Apply/Reject changed canonical Project state - refresh the outer screen (checkpoint,
  // pendingProposals) exactly once per completed action, not on every recomposition.
  LaunchedEffect(applyState) {
    if (applyState is ExplorePlanApplyState.Applied) {
      onProjectChanged()
    }
  }

  ExplorePlanPanel(
      planState = planState,
      selectionState = selectionState,
      applyState = applyState,
      onRequestPlan = explorePlanViewModel::requestPlan,
      onSelectOption = explorePlanViewModel::selectOption,
      onApply = explorePlanViewModel::applySelection,
      onDismissProposal = explorePlanViewModel::dismissSelection,
      onRetry = { existingResultId?.let(explorePlanViewModel::retry) },
      visualStates = visualStates,
      onVisualizeOption = visualArtifactViewModel::visualize,
      modifier = modifier,
  )
}

@Composable
private fun SavedInvestigationSection(
    investigation: SavedInvestigationReview,
    onResumeFollowUp: (String) -> Unit,
) {
  var showTechnicalDetails by remember { mutableStateOf(false) }
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF24262B)).padding(16.dp),
  ) {
    Text("RECENT INVESTIGATION", color = AppColor.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text("Investigation saved", color = AppColor.Success, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
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
    Text("AI suggestion — unconfirmed", color = AppColor.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Text(investigation.hypothesis, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp))
    Text("Recommended next action", color = AppColor.InkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    Text(investigation.recommendedNextAction, color = AppColor.InkPrimary)
    Text("Your assessment: ${trustDecisionLabel(investigation.trustDecision)}", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 12.dp))
    investigation.followUpSessionId?.let { sessionId ->
      OutlinedButton(
          onClick = { onResumeFollowUp(sessionId) },
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) { Text("Add more evidence") }
    }
    TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
      Text(if (showTechnicalDetails) "Hide technical details" else "Technical details")
    }
    if (showTechnicalDetails) {
      Text("Status: ${investigation.status}", color = AppColor.InkSecondary, fontSize = 12.sp)
      Text("Completed ${investigation.completedAtUtc}", color = AppColor.InkSecondary, fontSize = 12.sp)
      Text("Session ${investigation.sessionId}", color = AppColor.InkSecondary, fontSize = 11.sp)
    }
  }
}

// Same simple terminology as the trust action itself, wherever it's shown - see
// BackendInvestigationPanel's/ProjectContinuityHudController's own "Looks right"/"Add more
// info"/"Not quite" labels for the glasses<->phone trust UX simplification pass.
internal fun trustDecisionLabel(decision: String?): String =
    when (decision) {
      "continue" -> "Looks right"
      "disagree" -> "Not quite"
      "more_evidence" -> "Add more info"
      else -> "Not decided"
    }

@Composable
private fun PendingProposalsSection(
    proposals: List<com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.CheckpointProposalReview>,
    actionState: ProposalActionState,
    onApply: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF24262B)).padding(16.dp),
  ) {
    proposals.forEachIndexed { index, proposal ->
      Text(
          "SUGGESTED PROJECT CHANGE — REVIEW REQUIRED · ${index + 1} OF ${proposals.size}",
          color = AppColor.Accent,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 20.dp),
      )
      Text("Pending approval", color = AppColor.InkSecondary)
      Text(
          "Proposal ${proposal.proposalId.take(8)}",
          color = AppColor.InkSecondary,
          fontSize = 11.sp,
          modifier = Modifier.padding(top = 4.dp),
      )
      Text(proposal.reason, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 8.dp))
      proposal.proposedFields.filterValues { value -> value != null }.forEach { (field, value) ->
        Text("${field.replace('_', ' ')} → $value", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 6.dp))
      }
      val busy = actionState is ProposalActionState.InProgress
      Button(
          onClick = { onApply(proposal.proposalId) },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) { Text("Apply to Project") }
      OutlinedButton(
          onClick = { onReject(proposal.proposalId) },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) { Text("Reject change") }
      Text("Rejecting keeps the Investigation and evidence in Project history.", color = AppColor.InkSecondary, fontSize = 12.sp)
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
            text = "Current Project for quick capture",
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
            text = if (isActive) "Stop using for quick capture" else "Make this my current Project",
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
