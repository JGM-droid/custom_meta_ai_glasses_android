package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

internal data class InvestigationImageSlotUiState(
    val slotIndex: Int,
    val uriString: String? = null,
    val displayName: String? = null,
  val evidence: InvestigationEvidenceInput? = null,
)

internal data class InvestigationCaptureAppendResult(
    val images: List<InvestigationImageSlotUiState>,
    val appendedSlotIndex: Int?,
)

internal object InvestigationCaptureSlots {
  const val MAX_CAPTURE_SLOTS = 5

  fun emptySlots(): List<InvestigationImageSlotUiState> =
      List(MAX_CAPTURE_SLOTS) { InvestigationImageSlotUiState(it) }

  fun selectedCount(images: List<InvestigationImageSlotUiState>): Int {
    return images.count { it.evidence != null || it.uriString != null }
  }

  fun hasCapacity(images: List<InvestigationImageSlotUiState>): Boolean {
    return selectedCount(images) < MAX_CAPTURE_SLOTS
  }

  fun appendEvidence(
      images: List<InvestigationImageSlotUiState>,
      evidence: InvestigationEvidenceInput,
  ): InvestigationCaptureAppendResult {
    val nextSlot = images.firstOrNull { slot -> slot.evidence == null && slot.uriString == null }?.slotIndex
    if (nextSlot == null) {
      return InvestigationCaptureAppendResult(images = images, appendedSlotIndex = null)
    }

    val updatedImages =
        images.map { slot ->
          if (slot.slotIndex == nextSlot) {
            slot.copy(
                uriString = null,
                displayName = evidence.filename,
                evidence = evidence.copy(slotIndex = nextSlot),
            )
          } else {
            slot
          }
        }
    return InvestigationCaptureAppendResult(images = updatedImages, appendedSlotIndex = nextSlot)
  }

  fun orderedEvidence(images: List<InvestigationImageSlotUiState>): List<InvestigationEvidenceInput> {
    return images
        .sortedBy { it.slotIndex }
        .mapNotNull { slot -> slot.evidence?.copy(slotIndex = slot.slotIndex) }
  }
}

internal data class InvestigationSessionDebugUiState(
    val backendBaseUrl: String = InvestigationBackendConfig.resolveBaseUrl(),
    val clientState: InvestigationClientState = InvestigationClientState.IDLE,
    val sessionId: String? = null,
    val backendStatus: BackendSessionStatus? = null,
    val investigationId: String? = null,
    val compactResult: BackendCompactResultDto? = null,
    val analyzeAccepted: Boolean? = null,
    val pollAfterMs: Int? = null,
    val imageCount: Int = 0,
    val uploadedImageCount: Int = 0,
    val totalImageCount: Int = 0,
    val explanationPresent: Boolean = false,
    val backendErrorCategory: String? = null,
    val explanationText: String = "",
    val images: List<InvestigationImageSlotUiState> = InvestigationCaptureSlots.emptySlots(),
    val activeCaptureCount: Int = 0,
    val hasCaptureCapacity: Boolean = true,
    val statusMessage: String? = null,
    val canSubmit: Boolean = false,
    val isRunning: Boolean = false,
    val trustDecisionInFlight: Boolean = false,
    val trustDecision: BackendTrustDecision? = null,
    val trustStatus: String? = null,
    val trustMessage: String? = null,
    val trustCorrectionText: String = "",
    val continuationSessionId: String? = null,
    val trustControlsAvailable: Boolean = false,
)

internal class InvestigationSessionDebugViewModel(
    application: Application,
    // Explicit Project attribution for whatever this ViewModel instance submits (see
    // BackendSessionCreateRequestDto/ADR-037) - null means the backend's own Active Project
    // fallback/unscoped precedence applies unchanged, exactly matching the existing global
    // Capture / Test Glasses entry point's behavior. Immutable for this ViewModel's lifetime: it
    // is set once, at construction, from the explicit Project the caller navigated in from (see
    // StreamScreen.kt's `key = sourceProjectId` on its viewModel() call) - never mutated later,
    // so a single Capture session can never silently change which Project it belongs to partway
    // through.
    private val sourceProjectId: String? = null,
    private val initialContinuationSessionId: String? = null,
    private val repository: InvestigationSessionRepository = InvestigationSessionRepository(
        api = HttpUrlInvestigationSessionApi(InvestigationBackendConfig.resolveBaseUrl()),
    ),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
  companion object {
    private const val SUBMISSION_TIMEOUT_MS = 120_000L

    fun factory(application: Application, sourceProjectId: String? = null, initialContinuationSessionId: String? = null): ViewModelProvider.Factory {
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(InvestigationSessionDebugViewModel::class.java)) {
            return InvestigationSessionDebugViewModel(application, sourceProjectId, initialContinuationSessionId) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
          if (modelClass.isAssignableFrom(InvestigationSessionDebugViewModel::class.java)) {
            return InvestigationSessionDebugViewModel(
                application,
                sourceProjectId,
                initialContinuationSessionId,
                savedStateHandle = extras.createSavedStateHandle(),
            ) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
      }
    }
  }

  private val _uiState = MutableStateFlow(InvestigationSessionDebugUiState())
  val uiState: StateFlow<InvestigationSessionDebugUiState> = _uiState.asStateFlow()

  private var activeJob: Job? = null

  init {
    _uiState.update { state ->
      val restoredSessionId = initialContinuationSessionId ?: savedStateHandle["investigation_session_id"]
      val restoredContinuationSessionId = resolveContinuationSessionId(
          initialContinuationSessionId = initialContinuationSessionId,
          savedContinuationSessionId = savedStateHandle["investigation_continuation_session_id"],
          savedSessionId = savedStateHandle["investigation_session_id"],
      )
      val restoredImages = state.images.map { slot ->
        slot.copy(
            uriString = savedStateHandle["investigation_image_uri_${slot.slotIndex}"],
            displayName = savedStateHandle["investigation_image_name_${slot.slotIndex}"],
        )
      }
      state.copy(
          backendBaseUrl = InvestigationBackendConfig.resolveBaseUrl(BuildConfig.INVESTIGATION_BACKEND_BASE_URL),
          explanationText = savedStateHandle["investigation_explanation"] ?: state.explanationText,
          sessionId = restoredSessionId,
          // A known canonical session must always flow through repository reconciliation after
          // recreation, even when the previous client state itself was not persisted.
          continuationSessionId = restoredContinuationSessionId,
          backendStatus = restoredSessionId?.let { BackendSessionStatus.CREATED },
          images = restoredImages,
          activeCaptureCount = selectedSlotCount(restoredImages),
          hasCaptureCapacity = InvestigationCaptureSlots.hasCapacity(restoredImages),
      )
    }
  }

  fun onCheckBackendButtonClick() {
    runConnectivityCheck()
  }

  fun onStartInvestigationButtonClick() {
    submitInvestigation()
  }

  fun setExplanationText(text: String) {
    savedStateHandle["investigation_explanation"] = text
    _uiState.update { it.copy(explanationText = text) }
  }

  fun setTrustCorrectionText(text: String) {
    _uiState.update { it.copy(trustCorrectionText = text) }
  }

  /**
   * @param sessionId Defaults to whatever session this ViewModel's own [uiState] already has
   *   loaded (the phone panel's existing behavior, unchanged). The HUD's trust-decision bridge
   *   (see StreamViewModel's onHudTrustDecisionRequested) passes it explicitly instead, since the
   *   session a HUD decision is about is the one a same-sitting HUD-triggered Analyze just
   *   populated into this exact field - not a second, parallel notion of "current session."
   * @return the Job driving this submission, so a caller needing completion (the HUD bridge) can
   *   `join()` it. Null when nothing was started - see [submitInvestigation]'s doc for the same
   *   shape and reasoning.
   */
  fun submitTrustDecision(decision: BackendTrustDecision, sessionId: String? = null): Job? {
    val projectId = sourceProjectId ?: return null
    val resolvedSessionId = sessionId ?: _uiState.value.sessionId ?: return null
    if (_uiState.value.compactResult == null || activeJob?.isActive == true) {
      return activeJob
    }
    activeJob =
        viewModelScope.launch {
          _uiState.update {
            it.copy(
                trustDecisionInFlight = true,
                trustMessage = null,
                backendErrorCategory = null,
            )
          }
          try {
            val response =
                withContext(Dispatchers.IO) {
                  repository.submitTrustDecision(
                      projectId = projectId,
                      sessionId = resolvedSessionId,
                      decision = decision,
                      correction = _uiState.value.trustCorrectionText,
                  )
                }
            if (decision == BackendTrustDecision.MORE_EVIDENCE) {
              val followUpId = response.followUpSessionId
                  ?: throw IllegalStateException("Backend did not return a follow-up Investigation.")
              savedStateHandle["investigation_session_id"] = followUpId
              savedStateHandle["investigation_continuation_session_id"] = followUpId
              _uiState.update {
                InvestigationSessionDebugUiState(
                    backendBaseUrl = it.backendBaseUrl,
                    clientState = InvestigationClientState.IDLE,
                    sessionId = followUpId,
                    backendStatus = BackendSessionStatus.CREATED,
                    continuationSessionId = followUpId,
                    trustDecision = decision,
                    trustStatus = response.status,
                    trustMessage = "Got it - capture another view or add more context.",
                )
              }
            } else {
              // User-facing copy only - simplified per the glasses<->phone trust UX pass (no
              // "working hypothesis"/"apply to project" phrasing in the normal flow). The
              // underlying decision/status this records is unchanged - see BackendTrustDecision
              // and response.status/checkpointProposalStatus, both still available via this
              // screen's own "Technical details" toggle for anyone who needs them.
              val message =
                  when (decision) {
                    BackendTrustDecision.CONTINUE -> "Saved to this Project."
                    BackendTrustDecision.DISAGREE -> "Got it - noted."
                    BackendTrustDecision.MORE_EVIDENCE -> error("handled above")
                  }
              _uiState.update {
                it.copy(
                    trustDecisionInFlight = false,
                    trustDecision = decision,
                    trustStatus = response.status,
                    trustMessage = message,
                )
              }
            }
          } catch (error: BackendApiException) {
            _uiState.update {
              it.copy(
                  trustDecisionInFlight = false,
                  backendErrorCategory = error.error.category,
                  trustMessage = error.error.message,
              )
            }
          } catch (error: Exception) {
            _uiState.update {
              it.copy(
                  trustDecisionInFlight = false,
                  trustMessage = error.message ?: "Trust decision failed.",
              )
            }
          } finally {
            activeJob = null
          }
        }
    return activeJob
  }

  fun setImage(slotIndex: Int, uriString: String?, displayName: String?) {
    savedStateHandle["investigation_image_uri_$slotIndex"] = uriString
    savedStateHandle["investigation_image_name_$slotIndex"] = displayName
    _uiState.update { state ->
      val updatedImages =
          state.images.map { slot ->
            if (slot.slotIndex == slotIndex) {
              slot.copy(uriString = uriString, displayName = displayName, evidence = null)
            } else {
              slot
            }
          }
      val count = selectedSlotCount(updatedImages)
      state.copy(
          images = updatedImages,
          activeCaptureCount = count,
          hasCaptureCapacity = InvestigationCaptureSlots.hasCapacity(updatedImages),
          backendErrorCategory = null,
          statusMessage = null,
      )
    }
  }

  fun setEvidence(slotIndex: Int, evidence: InvestigationEvidenceInput) {
    _uiState.update { state ->
      val updatedImages =
          state.images.map { slot ->
            if (slot.slotIndex == slotIndex) {
              slot.copy(
                  uriString = null,
                  displayName = evidence.filename,
                  evidence = evidence.copy(slotIndex = slotIndex),
              )
            } else {
              slot
            }
          }
      val count = selectedSlotCount(updatedImages)
      state.copy(
          images = updatedImages,
          activeCaptureCount = count,
          hasCaptureCapacity = InvestigationCaptureSlots.hasCapacity(updatedImages),
          backendErrorCategory = null,
          statusMessage = null,
      )
    }
  }

  fun appendLiveEvidence(evidence: InvestigationEvidenceInput): Boolean {
    var appended = false
    _uiState.update { state ->
      val appendedResult = InvestigationCaptureSlots.appendEvidence(state.images, evidence)
      if (appendedResult.appendedSlotIndex == null) {
        return@update state.copy(
            hasCaptureCapacity = false,
            statusMessage = "Investigation full. 5 photos added.",
        )
      }

      appended = true
      val updatedImages = appendedResult.images
      val count = selectedSlotCount(updatedImages)
      state.copy(
          images = updatedImages,
          activeCaptureCount = count,
          hasCaptureCapacity = InvestigationCaptureSlots.hasCapacity(updatedImages),
          backendErrorCategory = null,
          statusMessage = null,
      )
    }
    return appended
  }

  fun appendPickedImage(uriString: String, displayName: String?): Boolean {
    var appended = false
    _uiState.update { state ->
      val nextSlot =
          state.images.firstOrNull { it.evidence == null && it.uriString == null }?.slotIndex
              ?: return@update state.copy(
                  hasCaptureCapacity = false,
                  statusMessage = "Investigation full. 5 photos added.",
              )
      appended = true
      savedStateHandle["investigation_image_uri_$nextSlot"] = uriString
      savedStateHandle["investigation_image_name_$nextSlot"] = displayName
      val updatedImages =
          state.images.map { slot ->
            if (slot.slotIndex == nextSlot) {
              slot.copy(uriString = uriString, displayName = displayName, evidence = null)
            } else slot
          }
      state.copy(
          images = updatedImages,
          activeCaptureCount = selectedSlotCount(updatedImages),
          hasCaptureCapacity = InvestigationCaptureSlots.hasCapacity(updatedImages),
          backendErrorCategory = null,
          statusMessage = null,
      )
    }
    return appended
  }

  fun clearInvestigationResultStatus() {
    _uiState.update {
      it.copy(
          backendErrorCategory = null,
          statusMessage = null,
      )
    }
  }

  fun clearImage(slotIndex: Int) {
    setImage(slotIndex, null, null)
  }

  fun runConnectivityCheck() {
    if (activeJob?.isActive == true) {
      return
    }
    val backendUrlValidation =
        InvestigationBackendConfig.validateConnectivityBaseUrl(_uiState.value.backendBaseUrl)
    if (applyBackendUrlValidationFailure(backendUrlValidation)) {
      return
    }
    activeJob = viewModelScope.launch {
      _uiState.update {
        it.copy(
            isRunning = true,
            clientState = InvestigationClientState.CREATING_SESSION,
            backendErrorCategory = null,
            statusMessage = null,
        )
      }
      try {
        val polling =
          withContext(Dispatchers.IO) {
            repository.checkConnectivity(
              clientMetadata = mapOf(
                "source" to "cameraaccess_debug",
                "backend_base_url" to _uiState.value.backendBaseUrl,
              ),
              projectId = sourceProjectId,
            )
          }
        _uiState.update {
          it.copy(
              isRunning = false,
              clientState = InvestigationClientState.COMPLETED,
              sessionId = polling.sessionId,
              backendStatus = polling.status,
              investigationId = polling.investigationId,
              compactResult = polling.compactResult,
              pollAfterMs = polling.pollAfterMs,
              imageCount = polling.imageCount,
              explanationPresent = polling.explanationPresent,
              analyzeAccepted = null,
              statusMessage =
                  if (polling.resultAvailable) {
                    "Compact result available."
                  } else {
                    "Session created and poll contract verified."
                  },
          )
        }
      } catch (error: Exception) {
        _uiState.update {
          it.copy(
              isRunning = false,
              clientState = InvestigationClientState.FAILED,
              statusMessage = error.message ?: error::class.java.simpleName,
          )
        }
      } finally {
        activeJob = null
      }
    }
  }

  /**
   * @return the Job driving this submission, so a caller that needs to know when it's actually
   *   done (e.g. the HUD's Analyze bridge in StreamScreen.kt - see StreamViewModel's doc on
   *   onHudAnalyzeRequested) can `join()` it rather than polling [uiState]. Null when nothing was
   *   started: either a submission is already active (returns that job instead - joining it waits
   *   for the SAME work already in flight, which is correct) or backend URL validation failed
   *   before any job existed.
   */
  fun submitInvestigation(): Job? {
    if (activeJob?.isActive == true) {
      return activeJob
    }
    val backendUrlValidation =
        InvestigationBackendConfig.validateSubmissionBaseUrl(_uiState.value.backendBaseUrl)
    if (applyBackendUrlValidationFailure(backendUrlValidation)) {
      return null
    }
    activeJob = viewModelScope.launch {
      try {
        val draft =
            withContext(Dispatchers.IO) {
              buildDraft()
            } ?: run {
              _uiState.update {
                it.copy(
                    isRunning = false,
                    clientState = InvestigationClientState.FAILED,
                    backendErrorCategory = "validation",
                    statusMessage = "Select 1 to 5 images before submitting.",
                )
              }
              return@launch
            }
        _uiState.update {
          it.copy(
              isRunning = true,
              statusMessage = null,
              backendErrorCategory = null,
              clientState = InvestigationClientState.PREPARING,
          )
        }
        val outcome =
            withTimeout(SUBMISSION_TIMEOUT_MS) {
              withContext(Dispatchers.IO) {
                repository.submitInvestigation(
                    draft = draft,
                    onProgress = { progress ->
                      _uiState.update { state ->
                        state.copy(
                            clientState = progress.clientState,
                            sessionId = progress.sessionId ?: state.sessionId,
                            backendStatus = progress.backendStatus ?: state.backendStatus,
                            investigationId = progress.investigationId ?: state.investigationId,
                            compactResult = progress.compactResult ?: state.compactResult,
                            analyzeAccepted = progress.analyzeAccepted ?: state.analyzeAccepted,
                            pollAfterMs = progress.pollAfterMs ?: state.pollAfterMs,
                            imageCount = progress.imageCount ?: state.imageCount,
                            uploadedImageCount = progress.uploadedImageCount ?: state.uploadedImageCount,
                            totalImageCount = progress.totalImageCount ?: state.totalImageCount,
                            explanationPresent = progress.explanationPresent ?: state.explanationPresent,
                            isRunning =
                                progress.clientState !in
                                    setOf(
                                        InvestigationClientState.COMPLETED,
                                        InvestigationClientState.FAILED,
                                        InvestigationClientState.CANCELLED,
                                    ),
                            statusMessage = progress.message ?: state.statusMessage,
                        )
                      }
                      progress.sessionId?.let { savedStateHandle["investigation_session_id"] = it }
                    },
                )
              }
            }

        when (outcome) {
          is InvestigationSubmissionOutcome.Completed -> {
            _uiState.update {
              it.copy(
                  isRunning = false,
                  clientState = InvestigationClientState.COMPLETED,
                  sessionId = outcome.polling.sessionId,
                  backendStatus = outcome.polling.status,
                  investigationId = outcome.polling.investigationId,
                  compactResult = outcome.polling.compactResult,
                  analyzeAccepted = outcome.analyzeResponse.accepted,
                  pollAfterMs = outcome.polling.pollAfterMs,
                  imageCount = outcome.polling.imageCount,
                  explanationPresent = outcome.polling.explanationPresent,
                  uploadedImageCount = it.totalImageCount,
                  statusMessage = "Investigation completed.",
                  trustControlsAvailable = sourceProjectId != null,
                  trustDecision = null,
                  trustStatus = null,
                  trustMessage = null,
              )
            }
            savedStateHandle["investigation_session_id"] = outcome.polling.sessionId
          }

          is InvestigationSubmissionOutcome.Cancelled -> {
            _uiState.update {
              it.copy(
                  isRunning = false,
                  clientState = InvestigationClientState.CANCELLED,
                  sessionId = outcome.session?.sessionId ?: it.sessionId,
                  backendErrorCategory = "cancelled",
                  statusMessage = "Submission cancelled.",
              )
            }
          }

          is InvestigationSubmissionOutcome.Failed -> {
            _uiState.update {
              it.copy(
                  isRunning = false,
                  clientState = InvestigationClientState.FAILED,
                  sessionId = outcome.session?.sessionId ?: it.sessionId,
                  backendStatus = outcome.session?.status ?: it.backendStatus,
                  backendErrorCategory = (outcome.error as? InvestigationClientError.BackendError)?.category,
                  statusMessage = outcome.error.message,
              )
            }
          }
        }
      } catch (timeout: TimeoutCancellationException) {
        _uiState.update {
          it.copy(
              isRunning = false,
              clientState = InvestigationClientState.FAILED,
              backendErrorCategory = "client_timeout",
              statusMessage = "Investigation timed out. Please retry.",
          )
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        _uiState.update {
          it.copy(
              isRunning = false,
              clientState = InvestigationClientState.CANCELLED,
              backendErrorCategory = "cancelled",
              statusMessage = "Submission cancelled.",
          )
        }
      } catch (error: Exception) {
        _uiState.update {
          it.copy(
              isRunning = false,
              clientState = InvestigationClientState.FAILED,
              backendErrorCategory = it.backendErrorCategory ?: "client_unexpected_exception",
              statusMessage = error.message ?: error::class.java.simpleName,
          )
        }
      } finally {
        activeJob = null
      }
    }
    return activeJob
  }

  fun cancelSubmission() {
    activeJob?.cancel()
    activeJob = null
    _uiState.update {
      it.copy(
          isRunning = false,
          clientState = InvestigationClientState.CANCELLED,
          backendErrorCategory = "cancelled",
          statusMessage = "Cancelled.",
      )
    }
  }

  private fun buildDraft(): InvestigationSubmissionDraft? {
    val selectedEvidence =
        _uiState.value.images.mapIndexedNotNull { index, slot ->
          slot.evidence?.let { return@mapIndexedNotNull it.copy(slotIndex = index) }

          val uriText = slot.uriString ?: return@mapIndexedNotNull null
          val uri = Uri.parse(uriText)
          val input = readUriBytes(uri) ?: return@mapIndexedNotNull null
          InvestigationEvidenceInput(
              slotIndex = index,
              filename = slot.displayName ?: uri.lastPathSegment ?: "capture_$index.jpg",
              mimeType = detectMimeType(uri) ?: "image/jpeg",
              bytes = input,
              source = InvestigationEvidenceSource.LOCAL_PICKER,
          )
        }

    if (selectedEvidence.isEmpty() ||
        selectedEvidence.size > InvestigationCaptureSlots.MAX_CAPTURE_SLOTS) {
      return null
    }

    return InvestigationSubmissionDraft(
        evidence = selectedEvidence,
        explanationText = _uiState.value.explanationText,
        clientMetadata = mapOf(
            "source" to "cameraaccess_debug",
            "backend_base_url" to _uiState.value.backendBaseUrl,
        ),
        projectId = sourceProjectId,
        continuationSessionId = _uiState.value.continuationSessionId
            ?: _uiState.value.sessionId.takeIf { _uiState.value.clientState == InvestigationClientState.FAILED },
    )
  }

  private fun readUriBytes(uri: Uri): ByteArray? {
    val context = getApplication<Application>().applicationContext
    return try {
      context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
    } catch (_: Exception) {
      null
    }
  }

  private fun detectMimeType(uri: Uri): String? {
    val context = getApplication<Application>().applicationContext
    return context.contentResolver.getType(uri)
  }

  private fun applyBackendUrlValidationFailure(
      validation: InvestigationBackendUrlValidation,
  ): Boolean {
    if (validation.isAllowed) {
      return false
    }

    _uiState.update {
      it.copy(
          isRunning = false,
          clientState = InvestigationClientState.FAILED,
          backendErrorCategory = validation.errorCategory,
          statusMessage = validation.message,
      )
    }
    return true
  }

  override fun onCleared() {
    super.onCleared()
  }

  private fun selectedSlotCount(images: List<InvestigationImageSlotUiState>): Int {
    return InvestigationCaptureSlots.selectedCount(images)
  }

}

internal fun resolveContinuationSessionId(
    initialContinuationSessionId: String?,
    savedContinuationSessionId: String?,
    savedSessionId: String?,
): String? = initialContinuationSessionId ?: savedContinuationSessionId ?: savedSessionId
