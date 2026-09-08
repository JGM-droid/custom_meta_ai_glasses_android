package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.HttpUrlInvestigationSessionApi
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationBackendConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSubmissionDraft
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ConversationLoadState { LOADING, READY, ERROR }

internal data class ConversationAttachmentUiState(
    val uri: String,
    val displayName: String,
    val mimeType: String,
)

internal data class ProjectConversationUiState(
    val loadState: ConversationLoadState = ConversationLoadState.LOADING,
    val conversationId: String? = null,
    val turns: List<ConversationTurn> = emptyList(),
    val draft: String = "",
    val attachment: ConversationAttachmentUiState? = null,
    val sending: Boolean = false,
    val errorMessage: String? = null,
)

internal class ProjectConversationViewModel(
    application: Application,
    private val projectId: String,
    private val repository: ProjectRepository = HttpUrlProjectRepository(),
    private val evidenceRepository: InvestigationSessionRepository = InvestigationSessionRepository(
        HttpUrlInvestigationSessionApi(InvestigationBackendConfig.resolveBaseUrl())),
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
  private val _state = MutableStateFlow(ProjectConversationUiState(
      draft = savedState["conversation_draft"] ?: "",
      attachment = savedState.get<String>("conversation_attachment_uri")?.let { uri ->
        ConversationAttachmentUiState(
            uri = uri,
            displayName = savedState["conversation_attachment_name"] ?: "Photo",
            mimeType = savedState["conversation_attachment_mime"] ?: "image/jpeg",
        )
      },
  ))
  val state: StateFlow<ProjectConversationUiState> = _state.asStateFlow()
  private var pendingIdempotencyKey: String? = savedState["conversation_pending_key"]
  private var stagedReference: ConversationEvidenceReference? =
      savedState.get<String>("conversation_evidence_id")?.let { evidenceId ->
        savedState.get<String>("conversation_session_id")?.let { sessionId ->
          ConversationEvidenceReference(evidenceId, sessionId)
        }
      }

  init { load() }

  fun load() {
    if (_state.value.turns.isEmpty()) _state.update { it.copy(loadState = ConversationLoadState.LOADING) }
    viewModelScope.launch {
      try {
        val conversation = withContext(Dispatchers.IO) { repository.getProjectConversation(projectId) }
        require(conversation.projectId == projectId)
        _state.update { it.copy(
            loadState = ConversationLoadState.READY,
            conversationId = conversation.conversationId,
            turns = conversation.turns.sortedBy(ConversationTurn::sequenceNumber),
            errorMessage = null,
        ) }
      } catch (error: Exception) {
        _state.update { it.copy(loadState = ConversationLoadState.ERROR, errorMessage = safeMessage(error)) }
      }
    }
  }

  fun updateDraft(value: String) {
    if (_state.value.sending) return
    pendingIdempotencyKey = null
    savedState.remove<String>("conversation_pending_key")
    savedState["conversation_draft"] = value
    _state.update { it.copy(draft = value, errorMessage = null) }
  }

  fun attach(uri: Uri, displayName: String, mimeType: String) {
    if (_state.value.sending) return
    pendingIdempotencyKey = null
    stagedReference = null
    clearPendingIdentity()
    val attachment = ConversationAttachmentUiState(uri.toString(), displayName, mimeType)
    savedState["conversation_attachment_uri"] = attachment.uri
    savedState["conversation_attachment_name"] = attachment.displayName
    savedState["conversation_attachment_mime"] = attachment.mimeType
    _state.update { it.copy(attachment = attachment, errorMessage = null) }
  }

  fun removeAttachment() {
    if (_state.value.sending) return
    pendingIdempotencyKey = null
    stagedReference = null
    clearAttachment()
    clearPendingIdentity()
    _state.update { it.copy(attachment = null, errorMessage = null) }
  }

  fun adoptAcceptedEvidence(reference: ConversationEvidenceReference) {
    if (_state.value.sending) return
    require(reference.investigationSessionId.isNotBlank() && reference.evidenceId.isNotBlank())
    stagedReference = reference
    pendingIdempotencyKey = null
    savedState.remove<String>("conversation_pending_key")
    savedState["conversation_evidence_id"] = reference.evidenceId
    savedState["conversation_session_id"] = reference.investigationSessionId
    val attachment = ConversationAttachmentUiState("", "Glasses photo", "image/jpeg")
    _state.update { it.copy(attachment = attachment, errorMessage = null) }
  }

  fun showAttachmentError(error: Throwable) {
    _state.update {
      it.copy(errorMessage = error.message?.takeIf(String::isNotBlank) ?: "Couldn't attach the glasses photo.")
    }
  }

  fun send() {
    val snapshot = _state.value
    if (snapshot.sending || snapshot.draft.isBlank()) return
    val key = pendingIdempotencyKey ?: UUID.randomUUID().toString().also {
      pendingIdempotencyKey = it
      savedState["conversation_pending_key"] = it
    }
    _state.update { it.copy(sending = true, errorMessage = null) }
    viewModelScope.launch {
      try {
        val reference = stagedReference ?: snapshot.attachment?.let { stageAttachment(it) }
        val result = withContext(Dispatchers.IO) {
          repository.sendProjectConversationMessage(
              projectId = projectId,
              text = snapshot.draft.trim(),
              evidenceRefs = listOfNotNull(reference),
              idempotencyKey = key,
          )
        }
        require(result.projectId == projectId)
        val conversation = withContext(Dispatchers.IO) { repository.getProjectConversation(projectId) }
        clearPendingIdentity()
        clearAttachment()
        savedState["conversation_draft"] = ""
        _state.update { it.copy(
            loadState = ConversationLoadState.READY,
            conversationId = result.conversationId,
            turns = conversation.turns.sortedBy(ConversationTurn::sequenceNumber),
            draft = "",
            attachment = null,
            sending = false,
            errorMessage = null,
        ) }
      } catch (error: Exception) {
        _state.update { it.copy(sending = false, errorMessage = safeMessage(error)) }
      }
    }
  }

  fun retry() = send()

  private suspend fun stageAttachment(attachment: ConversationAttachmentUiState): ConversationEvidenceReference =
      withContext(Dispatchers.IO) {
        val uri = Uri.parse(attachment.uri)
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("The selected photo is no longer available.")
        val staged = evidenceRepository.stageEvidenceForConversation(InvestigationSubmissionDraft(
            evidence = listOf(InvestigationEvidenceInput(
                slotIndex = 0,
                filename = attachment.displayName,
                mimeType = attachment.mimeType,
                bytes = bytes,
                source = InvestigationEvidenceSource.LOCAL_PICKER,
            )),
            explanationText = "",
            clientMetadata = mapOf("source" to "project_conversation"),
            projectId = projectId,
        ))
        val evidence = staged.evidence.single()
        ConversationEvidenceReference(evidence.evidenceId, staged.session.sessionId).also {
          stagedReference = it
          savedState["conversation_evidence_id"] = it.evidenceId
          savedState["conversation_session_id"] = it.investigationSessionId
        }
      }

  private fun clearAttachment() {
    savedState.remove<String>("conversation_attachment_uri")
    savedState.remove<String>("conversation_attachment_name")
    savedState.remove<String>("conversation_attachment_mime")
  }

  private fun clearPendingIdentity() {
    pendingIdempotencyKey = null
    stagedReference = null
    savedState.remove<String>("conversation_pending_key")
    savedState.remove<String>("conversation_evidence_id")
    savedState.remove<String>("conversation_session_id")
  }

  private fun safeMessage(error: Exception): String =
      error.message?.takeIf(String::isNotBlank) ?: "Could not reach the Project assistant."

  class Factory(private val application: Application, private val projectId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
      if (modelClass.isAssignableFrom(ProjectConversationViewModel::class.java)) {
        return ProjectConversationViewModel(
            application = application,
            projectId = projectId,
            savedState = extras.createSavedStateHandle(),
        ) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        create(modelClass, CreationExtras.Empty)
  }
}
