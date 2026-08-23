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

  data class Loaded(val overview: ProjectOverview) : ProjectDetailUiState

  data class Error(val message: String) : ProjectDetailUiState
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

  init {
    loadOverview()
  }

  fun loadOverview() {
    _uiState.update { ProjectDetailUiState.Loading }
    viewModelScope.launch {
      try {
        val overview = withContext(Dispatchers.IO) { repository.getProjectOverview(projectId) }
        _uiState.update { ProjectDetailUiState.Loaded(overview) }
      } catch (exc: Exception) {
        _uiState.update {
          ProjectDetailUiState.Error(exc.message ?: "Could not reach the backend.")
        }
      }
    }
  }
}
