/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ExplorePlanViewModel - Rich Project Intelligence V1 (ADR-059) phone-side orchestration
//
// Owns only transient UI-session state for one Project's EXPLORE_PLAN flow - never a parallel
// Project Memory store. Every state transition either reflects a fresh typed backend response or
// a fresh canonical reload (refreshCurrentPlan/repository.getProjectOverview via the caller); this
// class never derives proposed_checkpoint_patch or any other canonical-state semantics itself -
// see ProjectRepository.createExplorePlan/getExplorePlan/selectExplorePlanOption, all of which
// return exactly what the backend's ProjectAIResult/CheckpointProposal already computed.
//
// SELECT -> Proposal -> Apply mirrors ProjectDetailViewModel's applyProposal/rejectProposal
// exactly (same generic backend endpoints), kept in a separate ViewModel scoped per-Project the
// same way InvestigationSessionDebugViewModel is - so Explore Plan's own Requesting/Selecting/
// Applying state can never bleed into, or be bled into by, unrelated Project Detail state.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface ExplorePlanUiState {
  data object Idle : ExplorePlanUiState
  data object Requesting : ExplorePlanUiState
  data class Ready(val result: ExplorePlanResult) : ExplorePlanUiState
  data class InformationRequested(val prompt: String) : ExplorePlanUiState
  data class Failed(val message: String) : ExplorePlanUiState
}

/** Tracks one explicit SELECT tap through to the backend-derived CheckpointProposal, if any. */
sealed interface ExplorePlanSelectionState {
  data object Idle : ExplorePlanSelectionState
  data class Selecting(val ideaId: String) : ExplorePlanSelectionState
  data class ProposalReady(val ideaId: String, val proposal: CheckpointProposalReview) : ExplorePlanSelectionState
  data class Failed(val ideaId: String, val message: String) : ExplorePlanSelectionState
}

sealed interface ExplorePlanApplyState {
  data object Idle : ExplorePlanApplyState
  data object Applying : ExplorePlanApplyState
  data object Applied : ExplorePlanApplyState
  data class Failed(val message: String) : ExplorePlanApplyState
}

class ExplorePlanViewModel(
    application: Application,
    private val projectId: String,
    private val repository: ProjectRepository = HttpUrlProjectRepository(),
) : AndroidViewModel(application) {

  class Factory(
      private val application: Application,
      private val projectId: String,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(ExplorePlanViewModel::class.java)) {
        return ExplorePlanViewModel(application, projectId) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }

  private val _planState = MutableStateFlow<ExplorePlanUiState>(ExplorePlanUiState.Idle)
  val planState: StateFlow<ExplorePlanUiState> = _planState.asStateFlow()

  private val _selectionState = MutableStateFlow<ExplorePlanSelectionState>(ExplorePlanSelectionState.Idle)
  val selectionState: StateFlow<ExplorePlanSelectionState> = _selectionState.asStateFlow()

  private val _applyState = MutableStateFlow<ExplorePlanApplyState>(ExplorePlanApplyState.Idle)
  val applyState: StateFlow<ExplorePlanApplyState> = _applyState.asStateFlow()

  // Reused across a retry of the SAME logical request so the backend's own idempotency converges
  // rather than creating a second interaction/decision - a fresh key only when a genuinely new
  // request starts (a new intent, or a different idea).
  private var pendingIntentKey: String? = null
  private val pendingSelectKeys = mutableMapOf<String, String>()

  /** Reopening the Project: reconstructs a known EXPLORE_PLAN from canonical state - no new provider call. */
  fun loadExistingPlan(resultId: String) {
    if (_planState.value is ExplorePlanUiState.Requesting) return
    _planState.value = ExplorePlanUiState.Requesting
    viewModelScope.launch {
      try {
        val result = withContext(Dispatchers.IO) { repository.getExplorePlan(projectId, resultId) }
        _planState.value = ExplorePlanUiState.Ready(result)
        reconcileSelectionFromCanonicalState(result)
      } catch (exc: Exception) {
        _planState.value = ExplorePlanUiState.Failed(exc.message ?: "Could not load design ideas.")
      }
    }
  }

  /**
   * Reconstructs a still-pending selection proposal from canonical backend state - the case this
   * exists for is process death/Activity recreation between an explicit SELECT and Apply: this
   * ViewModel's own in-memory selectionState is gone, but the backend's Decision + Proposal are
   * still there. Never overwrites selectionState that already exists THIS session (an in-flight
   * Selecting, or a ProposalReady/Failed this session's own selectOption() already produced) -
   * only fills in genuinely unknown state, so a fresh selection is never clobbered by a slower
   * canonical read racing behind it.
   */
  private suspend fun reconcileSelectionFromCanonicalState(plan: ExplorePlanResult) {
    if (_selectionState.value !is ExplorePlanSelectionState.Idle) return
    val selectedOption = plan.options.firstOrNull { it.disposition == "select" } ?: return
    try {
      val overview = withContext(Dispatchers.IO) { repository.getProjectOverview(projectId) }
      val proposal = overview.pendingProposals.firstOrNull { candidate ->
        candidate.status == "pending" && selectedOption.ideaId in candidate.sourceActivityIds
      }
      if (proposal != null && _selectionState.value is ExplorePlanSelectionState.Idle) {
        _selectionState.value = ExplorePlanSelectionState.ProposalReady(selectedOption.ideaId, proposal)
      }
    } catch (_: Exception) {
      // Best-effort only: the option card still shows its selected disposition either way, and
      // the generic pending-proposals section on Project Detail remains a safety net if this
      // reconstruction fails - never silently drop the fact that a selection was already made.
    }
  }

  /** Requests a new EXPLORE_PLAN interaction. Duplicate taps while already requesting are ignored. */
  fun requestPlan(userIntent: String) {
    val trimmed = userIntent.trim()
    if (trimmed.isEmpty() || _planState.value is ExplorePlanUiState.Requesting) return
    val key = pendingIntentKey ?: UUID.randomUUID().toString().also { pendingIntentKey = it }
    _planState.value = ExplorePlanUiState.Requesting
    viewModelScope.launch {
      try {
        when (val result = withContext(Dispatchers.IO) { repository.createExplorePlan(projectId, trimmed, key) }) {
          is ExplorePlanCreateResult.Ready -> {
            pendingIntentKey = null
            _planState.value = ExplorePlanUiState.Ready(result.result)
          }
          is ExplorePlanCreateResult.InformationRequest -> {
            pendingIntentKey = null
            _planState.value = ExplorePlanUiState.InformationRequested(result.prompt)
          }
        }
      } catch (exc: Exception) {
        // The key is intentionally kept: a retry of the same intent must reuse it so the backend
        // converges on one interaction rather than creating a second if the first attempt actually
        // succeeded server-side but the response was lost.
        _planState.value = ExplorePlanUiState.Failed(exc.message ?: "Could not get design ideas.")
      }
    }
  }

  /**
   * Explicit SELECT. Backend-owned: this never constructs proposed_checkpoint_patch itself - see
   * ProjectRepository.selectExplorePlanOption. A duplicate tap on an idea that already has a
   * ProposalReady result, or any tap while a selection is already in flight, is a no-op.
   */
  fun selectOption(ideaId: String) {
    if (_selectionState.value is ExplorePlanSelectionState.Selecting) return
    val already = _selectionState.value as? ExplorePlanSelectionState.ProposalReady
    if (already != null && already.ideaId == ideaId) return
    val key = pendingSelectKeys.getOrPut(ideaId) { UUID.randomUUID().toString() }
    _selectionState.value = ExplorePlanSelectionState.Selecting(ideaId)
    viewModelScope.launch {
      try {
        val selection = withContext(Dispatchers.IO) { repository.selectExplorePlanOption(projectId, ideaId, key) }
        pendingSelectKeys.remove(ideaId)
        val proposal = selection.checkpointProposal
        _selectionState.value =
            if (proposal != null) ExplorePlanSelectionState.ProposalReady(ideaId, proposal)
            else ExplorePlanSelectionState.Failed(ideaId, "Selection was recorded, but no Project change was proposed.")
        refreshCurrentPlan()
      } catch (exc: Exception) {
        _selectionState.value = ExplorePlanSelectionState.Failed(ideaId, exc.message ?: "Could not record that selection.")
      }
    }
  }

  /** Explicit Apply on the currently reviewed proposal. Duplicate taps while in flight are ignored. */
  fun applySelection() {
    val ready = _selectionState.value as? ExplorePlanSelectionState.ProposalReady ?: return
    if (_applyState.value is ExplorePlanApplyState.Applying) return
    _applyState.value = ExplorePlanApplyState.Applying
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { repository.applyCheckpointProposal(projectId, ready.proposal.proposalId) }
        _applyState.value = ExplorePlanApplyState.Applied
        refreshCurrentPlan()
      } catch (exc: Exception) {
        _applyState.value = ExplorePlanApplyState.Failed(exc.message ?: "Could not apply this Project change.")
      }
    }
  }

  /** Explicit reject: keeps the selection and Explore history, discards only the proposed change. */
  fun dismissSelection() {
    val ready = _selectionState.value as? ExplorePlanSelectionState.ProposalReady ?: return
    if (_applyState.value is ExplorePlanApplyState.Applying) return
    _applyState.value = ExplorePlanApplyState.Applying
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { repository.rejectCheckpointProposal(projectId, ready.proposal.proposalId) }
        _applyState.value = ExplorePlanApplyState.Idle
        _selectionState.value = ExplorePlanSelectionState.Idle
        refreshCurrentPlan()
      } catch (exc: Exception) {
        _applyState.value = ExplorePlanApplyState.Failed(exc.message ?: "Could not reject this Project change.")
      }
    }
  }

  /** Retries loading the currently open plan after a failure, without discarding selection/apply state. */
  fun retry(resultId: String) = loadExistingPlan(resultId)

  /**
   * ADR-060: seeds this ViewModel directly from a result the unified Get Guidance endpoint
   * (ProjectGuidanceViewModel) already produced - no second network call. Used when the backend's
   * Response Planner selects EXPLORE_PLAN for the user's natural request; SELECT -> Proposal ->
   * Apply below remains entirely this ViewModel's own, unchanged path from here. Mirrors
   * requestPlan's own re-entrancy guard exactly (blocks only while already Requesting) so a
   * second natural "get ideas" round trip through Get Guidance can still replace an earlier plan,
   * the same way calling requestPlan a second time already can.
   */
  fun adoptExternallyProducedPlan(result: ExplorePlanResult) {
    if (_planState.value is ExplorePlanUiState.Requesting) return
    _planState.value = ExplorePlanUiState.Ready(result)
  }

  private fun refreshCurrentPlan() {
    val resultId = (_planState.value as? ExplorePlanUiState.Ready)?.result?.resultId ?: return
    viewModelScope.launch {
      try {
        val result = withContext(Dispatchers.IO) { repository.getExplorePlan(projectId, resultId) }
        _planState.value = ExplorePlanUiState.Ready(result)
      } catch (_: Exception) {
        // Keep showing the last good plan; the selection/apply state already reports its own error.
      }
    }
  }
}
