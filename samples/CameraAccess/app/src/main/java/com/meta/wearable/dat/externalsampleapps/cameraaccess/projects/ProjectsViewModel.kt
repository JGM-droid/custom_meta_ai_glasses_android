/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectsViewModel - loads the real Projects list for ProjectsHomeScreen
//
// Same shape as InvestigationSessionDebugViewModel: an AndroidViewModel wrapping a
// MutableStateFlow<UiState>, with the actual network call dispatched on Dispatchers.IO from the
// call site (matching that file's convention).

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

sealed interface ProjectsHomeUiState {
  data object Loading : ProjectsHomeUiState

  data class Loaded(val projects: List<ProjectSummary>) : ProjectsHomeUiState

  data class Error(val message: String) : ProjectsHomeUiState
}

class ProjectsViewModel(
    application: Application,
    private val repository: ProjectRepository = HttpUrlProjectRepository(),
) : AndroidViewModel(application) {

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory {
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(ProjectsViewModel::class.java)) {
            return ProjectsViewModel(application) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
      }
    }
  }

  private val _uiState = MutableStateFlow<ProjectsHomeUiState>(ProjectsHomeUiState.Loading)
  val uiState: StateFlow<ProjectsHomeUiState> = _uiState.asStateFlow()

  init {
    loadProjects()
  }

  fun loadProjects() {
    _uiState.update { ProjectsHomeUiState.Loading }
    viewModelScope.launch {
      try {
        val projects = withContext(Dispatchers.IO) { repository.listProjects() }
        _uiState.update { ProjectsHomeUiState.Loaded(projects) }
      } catch (exc: Exception) {
        _uiState.update {
          ProjectsHomeUiState.Error(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }
}
