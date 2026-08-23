/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectDetailViewModel - loads one Project's real Overview for ProjectDetailScreen
//
// Scoped to a single projectId (passed explicitly through AppRoot navigation state, never
// inferred/guessed). Same Loading/Loaded/Error shape as ProjectsViewModel.
//
// Active Project (Work on this Project / Stop Working on Project): VIEWING this project
// (loadOverview) never changes which Project is Active - opening a screen must never have a side
// effect on backend Active state. setActiveProject/clearActiveProject are the only two calls
// that mutate it, and both are explicit user actions. Their own in-flight/error state is tracked
// separately in activeActionState rather than folded into uiState, specifically so that a failed
// activate/deactivate leaves the already-loaded overview and its isActive flag exactly as they
// were (see Phase 5/6 of the Active Project slice: never fake a change locally if the backend
// call fails) - the screen shows an inline error instead of losing the loaded content. On
// success, isActive is always re-derived from a fresh loadOverview() fetch rather than flipped
// locally, since the backend remains the sole source of truth for Active state.
//
// Ask Project (Project-Aware Ask): askProject always targets `projectId` - the Project this
// ViewModel instance was constructed for (i.e. whichever Project the Workspace is explicitly
// showing), NEVER the backend's separate Active Project pointer. This ViewModel is already keyed
// by project_id at the viewModel(key = ...) call site in both ProjectDetailScreen and
// ProjectWorkspaceScreen, so a brand-new instance (and brand-new askState, defaulting to Idle) is
// created per distinct Project automatically - one Project's in-flight/answered/failed Ask state
// can never bleed into another's. askState is intentionally separate from uiState/
// activeActionState: a failed or in-flight Ask must never disturb the already-loaded checkpoint
// or Active indicator. The backend's own /ask route is read-only (see
// projects/project_qa.py ProjectQuestionAnsweringService.ask - no PROJECT_STORE/
// PROJECT_ACTIVITY_STORE writes), so askProject adds no mutation of its own around it.

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProjectDetailUiState {
  data object Loading : ProjectDetailUiState

  /** isActive: whether THIS project is the backend's Active Project - not this project's own status. */
  data class Loaded(val overview: ProjectOverview, val isActive: Boolean) : ProjectDetailUiState

  data class Error(val message: String) : ProjectDetailUiState
}

/** Tracks the in-flight/error state of setActiveProject/clearActiveProject specifically. */
sealed interface ActiveProjectActionState {
  data object Idle : ActiveProjectActionState

  data object InProgress : ActiveProjectActionState

  data class Failed(val message: String) : ActiveProjectActionState
}

/** Tracks one in-flight/answered/failed Ask Project question. The question text is kept
 * alongside the result so the UI can show what was asked next to its answer/error, and so a
 * failed attempt can be retried without the user having to retype it. */
sealed interface ProjectAskState {
  data object Idle : ProjectAskState

  data class Submitting(val question: String) : ProjectAskState

  data class Answered(val question: String, val answer: ProjectAskAnswer) : ProjectAskState

  data class Failed(val question: String, val message: String) : ProjectAskState
}

class ProjectDetailViewModel(
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
      if (modelClass.isAssignableFrom(ProjectDetailViewModel::class.java)) {
        return ProjectDetailViewModel(application, projectId) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }

  private val _uiState = MutableStateFlow<ProjectDetailUiState>(ProjectDetailUiState.Loading)
  val uiState: StateFlow<ProjectDetailUiState> = _uiState.asStateFlow()

  private val _activeActionState = MutableStateFlow<ActiveProjectActionState>(ActiveProjectActionState.Idle)
  val activeActionState: StateFlow<ActiveProjectActionState> = _activeActionState.asStateFlow()

  private val _askState = MutableStateFlow<ProjectAskState>(ProjectAskState.Idle)
  val askState: StateFlow<ProjectAskState> = _askState.asStateFlow()

  init {
    loadOverview()
  }

  fun loadOverview() {
    _uiState.update { ProjectDetailUiState.Loading }
    viewModelScope.launch {
      try {
        val overview = withContext(Dispatchers.IO) { repository.getProjectOverview(projectId) }
        // Same "don't fail the whole screen over a secondary read" reasoning as
        // ProjectsViewModel: if this specific fetch fails, treat it as "not Active" rather than
        // losing the overview that already loaded successfully.
        val activeProjectId = withContext(Dispatchers.IO) {
          try {
            repository.getActiveProject()?.projectId
          } catch (exc: Exception) {
            null
          }
        }
        _uiState.update { ProjectDetailUiState.Loaded(overview, isActive = activeProjectId == projectId) }
      } catch (exc: Exception) {
        _uiState.update {
          ProjectDetailUiState.Error(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }

  /** "Work on this Project". Duplicate presses while already in flight are ignored. */
  fun setActiveProject() {
    if (_activeActionState.value is ActiveProjectActionState.InProgress) return
    _activeActionState.update { ActiveProjectActionState.InProgress }
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { repository.setActiveProject(projectId) }
        _activeActionState.update { ActiveProjectActionState.Idle }
        loadOverview()
      } catch (exc: Exception) {
        _activeActionState.update {
          ActiveProjectActionState.Failed(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }

  /** "Stop Working on Project". Duplicate presses while already in flight are ignored. */
  fun clearActiveProject() {
    if (_activeActionState.value is ActiveProjectActionState.InProgress) return
    _activeActionState.update { ActiveProjectActionState.InProgress }
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { repository.clearActiveProject() }
        _activeActionState.update { ActiveProjectActionState.Idle }
        loadOverview()
      } catch (exc: Exception) {
        _activeActionState.update {
          ActiveProjectActionState.Failed(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }

  /**
   * Asks THIS Project (projectId, never the Active Project) a question via the backend's
   * existing read-only Q&A route. Blank/whitespace-only questions and duplicate presses while
   * already in flight are both silently ignored - the Workspace composer's own "Ask Project"
   * button is disabled in both cases, so this is defense-in-depth, not a user-visible error path.
   */
  fun askProject(question: String) {
    val trimmed = question.trim()
    if (trimmed.isEmpty()) return
    if (_askState.value is ProjectAskState.Submitting) return

    _askState.update { ProjectAskState.Submitting(trimmed) }
    viewModelScope.launch {
      try {
        val answer = withContext(Dispatchers.IO) { repository.askProject(projectId, trimmed) }
        _askState.update { ProjectAskState.Answered(trimmed, answer) }
      } catch (exc: Exception) {
        _askState.update { ProjectAskState.Failed(trimmed, exc.message ?: "Could not reach the backend.") }
      }
    }
  }

  /** Returns to Idle after a failed Ask, so the composer can be edited/resubmitted cleanly. */
  fun dismissAskError() {
    if (_askState.value is ProjectAskState.Failed) {
      _askState.update { ProjectAskState.Idle }
    }
  }
}
