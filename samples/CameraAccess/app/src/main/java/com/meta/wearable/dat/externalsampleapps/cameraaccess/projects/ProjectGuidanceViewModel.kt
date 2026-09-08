/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectGuidanceViewModel - ADR-060 unified, planner-selected Project guidance
//
// Owns exactly one request/loading/error/result lifecycle for the "Get guidance" action: Project
// + current evidence + a typed/spoken request -> one backend call -> a typed ProjectAIResult the
// caller renders by result_type. Never chooses a response family itself and never exposes
// TROUBLESHOOT/EXPLORE_PLAN/GENERAL_GUIDANCE to the caller as anything but a rendering decision.
//
// Deliberately does NOT own the request text itself: the caller already has a single text field
// for this (InvestigationSessionDebugViewModel.uiState.explanationText, the same "Context/
// Explanation" field BackendInvestigationPanel already shows) - evolving that existing state
// rather than introducing a second, parallel piece of text state is what keeps "typed request
// preserved" across a retry or a clarification round-trip true for free: this ViewModel never
// touches or clears it.
//
// EXPLORE_PLAN results are NOT rendered by this ViewModel - the caller adopts them into the
// existing, unchanged ExplorePlanViewModel (see its adoptExternallyProducedPlan) so Select ->
// Proposal -> Apply remains entirely that ViewModel's own, proven state machine. This ViewModel
// only carries the routing decision far enough to hand off; it never duplicates Explore's state.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProjectGuidanceUiState {
  data object Idle : ProjectGuidanceUiState
  data object Requesting : ProjectGuidanceUiState
  /** Not an error - the backend genuinely could not determine a response need and asks one
   * concise question instead of guessing a family. The composer/request text stays exactly as
   * the user left it; they answer/resubmit through the same Get Guidance action. */
  data class ClarificationNeeded(val question: String) : ProjectGuidanceUiState
  data class Ready(val result: ProjectGuidanceResult) : ProjectGuidanceUiState
  data class Failed(val message: String) : ProjectGuidanceUiState
}

class ProjectGuidanceViewModel(
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
      if (modelClass.isAssignableFrom(ProjectGuidanceViewModel::class.java)) {
        return ProjectGuidanceViewModel(application, projectId) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }

  private val _uiState = MutableStateFlow<ProjectGuidanceUiState>(ProjectGuidanceUiState.Idle)
  val uiState: StateFlow<ProjectGuidanceUiState> = _uiState.asStateFlow()

  // Reused across a retry of the SAME logical request so the backend's own idempotency converges
  // rather than creating a second interaction if an earlier attempt actually succeeded server-side
  // but the response was lost - a fresh key only when a genuinely new request starts.
  private var pendingKey: String? = null

  // Bumped on every new call so a slow prior request can never overwrite a newer one's result or
  // error - "Do not let a stale prior result appear as the response to a new request."
  private var activeRequestId = 0

  /**
   * ADR-060 multimodal bridge: [stageEvidence] runs FIRST and its result becomes the
   * investigation_session_id sent to the unified endpoint - never a caller-supplied id passed
   * alongside a separate, possibly-stale one. This guarantees any evidence the user already
   * accepted (glasses or phone) is backend-available before the single routed reasoning call,
   * without ever making a second reasoning call or fabricating a session: [stageEvidence] itself
   * (see InvestigationSessionDebugViewModel.stagePendingEvidenceForGuidance) returns null when
   * there is genuinely nothing to stage and no existing session to reuse, and Get Guidance then
   * proceeds text-only exactly as before this bridge existed.
   *
   * If [stageEvidence] throws, this call stops right there - the guidance request is never sent
   * (never a second, redundant reasoning attempt over unstaged evidence), and the failure surfaces
   * through the SAME [ProjectGuidanceUiState.Failed] a routing failure would, so the caller offers
   * one consistent Retry action either way. A retry re-invokes [stageEvidence], which is safe to
   * repeat (session reuse + the backend's own evidence content-hash dedup - see that function's
   * doc - guarantee no duplicate session or duplicate upload).
   */
  fun getGuidance(userRequest: String, stageEvidence: suspend () -> String?) {
    val trimmed = userRequest.trim()
    if (trimmed.isEmpty() || _uiState.value is ProjectGuidanceUiState.Requesting) return
    val key = pendingKey ?: UUID.randomUUID().toString().also { pendingKey = it }
    val requestId = ++activeRequestId
    _uiState.value = ProjectGuidanceUiState.Requesting
    viewModelScope.launch {
      try {
        val investigationSessionId = withContext(Dispatchers.IO) { stageEvidence() }
        if (requestId != activeRequestId) return@launch
        val outcome = withContext(Dispatchers.IO) {
          repository.getProjectGuidance(projectId, trimmed, investigationSessionId, key)
        }
        if (requestId != activeRequestId) return@launch
        pendingKey = null
        _uiState.value = when (outcome) {
          is ProjectGuidanceOutcome.Ready -> ProjectGuidanceUiState.Ready(outcome.result)
          is ProjectGuidanceOutcome.NeedsClarification -> ProjectGuidanceUiState.ClarificationNeeded(outcome.question)
        }
      } catch (exc: Exception) {
        if (requestId != activeRequestId) return@launch
        // The key is intentionally kept: a retry of the same request must reuse it so the backend
        // converges on one interaction rather than creating a second if the first attempt actually
        // succeeded server-side but the response was lost.
        _uiState.value = ProjectGuidanceUiState.Failed(exc.message ?: "Could not get guidance for this Project.")
      }
    }
  }

  /** Returns to Idle without discarding the caller's own request text (that text lives outside
   * this ViewModel - see class doc). Used after a Ready result has been fully handled (e.g.
   * adopted into ExplorePlanViewModel, or the Investigation trust UI has taken over). */
  fun reset() {
    _uiState.value = ProjectGuidanceUiState.Idle
    pendingKey = null
  }
}
