package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VisualArtifactUiState {
  data object Idle : VisualArtifactUiState
  data object Requesting : VisualArtifactUiState
  data class Ready(val artifact: VisualArtifact, val images: VisualArtifactImages) : VisualArtifactUiState
  data class Failed(val message: String) : VisualArtifactUiState
}

class VisualArtifactViewModel(
    application: Application,
    private val projectId: String,
    private val repository: ProjectRepository = HttpUrlProjectRepository(),
) : AndroidViewModel(application) {
  class Factory(private val application: Application, private val projectId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VisualArtifactViewModel(application, projectId) as T
  }

  private val _states = MutableStateFlow<Map<String, VisualArtifactUiState>>(emptyMap())
  val states: StateFlow<Map<String, VisualArtifactUiState>> = _states.asStateFlow()
  private val keys = mutableMapOf<String, String>()
  private val artifacts = mutableMapOf<String, VisualArtifact>()

  fun visualize(resultId: String, optionId: String) {
    if (_states.value[optionId] is VisualArtifactUiState.Requesting) return
    val key = keys.getOrPut(optionId) { UUID.randomUUID().toString() }
    set(optionId, VisualArtifactUiState.Requesting)
    viewModelScope.launch {
      try {
        val existing = artifacts[optionId]
        val artifact = withContext(Dispatchers.IO) {
          if (existing?.status == VisualArtifactStatus.FAILED) {
            repository.retryVisualArtifact(projectId, resultId, optionId, existing.artifactId, key)
          } else {
            repository.createVisualArtifact(projectId, resultId, optionId, key)
          }
        }
        artifacts[optionId] = artifact
        awaitReady(resultId, optionId, artifact)
      } catch (exc: Exception) {
        set(optionId, VisualArtifactUiState.Failed(exc.message ?: "Could not create visualization."))
      }
    }
  }

  private suspend fun awaitReady(resultId: String, optionId: String, initial: VisualArtifact) {
    var current = initial
    repeat(40) {
      if (current.status == VisualArtifactStatus.READY) {
        val images = withContext(Dispatchers.IO) {
          repository.getVisualArtifactImages(projectId, resultId, optionId, current.artifactId)
        }
        keys.remove(optionId)
        set(optionId, VisualArtifactUiState.Ready(current, images))
        return
      }
      if (current.status == VisualArtifactStatus.FAILED) {
        set(optionId, VisualArtifactUiState.Failed("Visualization failed. Retry when ready."))
        return
      }
      delay(1_500)
      current = withContext(Dispatchers.IO) {
        repository.getVisualArtifact(projectId, resultId, optionId, current.artifactId)
      }
      artifacts[optionId] = current
    }
    set(optionId, VisualArtifactUiState.Failed("Visualization is taking longer than expected. Retry to check again."))
  }

  private fun set(optionId: String, state: VisualArtifactUiState) {
    _states.value = _states.value.toMutableMap().apply { put(optionId, state) }
  }
}
