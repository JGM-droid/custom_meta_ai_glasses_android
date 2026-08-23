/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// NewProjectViewModel - real Project creation (POST /projects)
//
// Owns the Create Project form's submission state only - not the canonical Project. On success
// it hands back the backend-created ProjectSummary (with the backend's own project_id); the
// caller (AppRoot) navigates to Project Detail, which re-fetches full state from the backend
// rather than trusting a client-constructed overview - see ProjectDetailViewModel.
//
// Duplicate-press safe: submit() is a no-op while already Submitting. A failed submission leaves
// the state as Failed (not stuck/disabled) so the form remains editable and retryable.

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

sealed interface NewProjectSubmitState {
  data object Idle : NewProjectSubmitState

  data object Submitting : NewProjectSubmitState

  data class Failed(val message: String) : NewProjectSubmitState

  data class Succeeded(val project: ProjectSummary) : NewProjectSubmitState
}

class NewProjectViewModel(
    application: Application,
    private val repository: ProjectRepository = HttpUrlProjectRepository(),
) : AndroidViewModel(application) {

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory {
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(NewProjectViewModel::class.java)) {
            return NewProjectViewModel(application) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
      }
    }
  }

  private val _submitState = MutableStateFlow<NewProjectSubmitState>(NewProjectSubmitState.Idle)
  val submitState: StateFlow<NewProjectSubmitState> = _submitState.asStateFlow()

  fun submit(name: String, goal: String, currentObjective: String, nextAction: String) {
    // Ignore duplicate presses while a request is already in flight.
    if (_submitState.value is NewProjectSubmitState.Submitting) return

    val trimmedName = name.trim()
    val trimmedGoal = goal.trim()
    if (trimmedName.isEmpty()) {
      _submitState.update { NewProjectSubmitState.Failed("Project name is required.") }
      return
    }
    if (trimmedGoal.isEmpty()) {
      _submitState.update { NewProjectSubmitState.Failed("Goal is required.") }
      return
    }

    val request = NewProjectRequest(
        name = trimmedName,
        goal = trimmedGoal,
        currentObjective = currentObjective.trim().ifEmpty { null },
        nextAction = nextAction.trim().ifEmpty { null },
    )

    _submitState.update { NewProjectSubmitState.Submitting }
    viewModelScope.launch {
      try {
        val created = withContext(Dispatchers.IO) { repository.createProject(request) }
        _submitState.update { NewProjectSubmitState.Succeeded(created) }
      } catch (exc: Exception) {
        _submitState.update {
          NewProjectSubmitState.Failed(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }

  /** Lets the screen return to an editable state after showing an error, without losing it. */
  fun dismissError() {
    if (_submitState.value is NewProjectSubmitState.Failed) {
      _submitState.update { NewProjectSubmitState.Idle }
    }
  }
}
