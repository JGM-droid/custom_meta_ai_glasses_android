package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant

internal enum class InvestigationEvidenceSource(
  val wireValue: String,
  val displayLabel: String,
) {
  LIVE_GLASSES("live_glasses", "Live glasses"),
  MOCK_DEVICE("mock_device", "Mock device"),
  LOCAL_PICKER("local_picker", "Local picker");
}

internal enum class InvestigationClientState {
  IDLE,
  PREPARING,
  CREATING_SESSION,
  UPLOADING_EVIDENCE,
  INITIATING_ANALYSIS,
  POLLING,
  COMPLETED,
  FAILED,
  CANCELLED,
}

internal sealed class InvestigationClientError(open val message: String) {
  data object DuplicateSubmission : InvestigationClientError("An investigation is already running.")
  data class ValidationError(override val message: String) : InvestigationClientError(message)
  data class EvidenceConversionError(override val message: String) : InvestigationClientError(message)
  data class BackendError(val category: String, override val message: String) : InvestigationClientError(message)
  data class ContractError(override val message: String) : InvestigationClientError(message)
  data class NetworkError(override val message: String) : InvestigationClientError(message)
  data class SerializationError(override val message: String) : InvestigationClientError(message)
  data class TimeoutError(override val message: String) : InvestigationClientError(message)
  data class CancelledError(override val message: String = "Cancelled.") : InvestigationClientError(message)
}

internal data class InvestigationEvidenceInput(
    val slotIndex: Int,
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
  val source: InvestigationEvidenceSource = InvestigationEvidenceSource.LOCAL_PICKER,
)

internal data class InvestigationSubmissionDraft(
    val evidence: List<InvestigationEvidenceInput>,
    val explanationText: String,
    val clientMetadata: Map<String, Any?>? = null,
    // Explicit Project attribution for this submission - see BackendSessionCreateRequestDto.
    val projectId: String? = null,
    // Present only after the backend's More Evidence trust decision creates the canonical
    // project-owned continuation session. Reusing it avoids inventing a second session.
    val continuationSessionId: String? = null,
)

internal data class InvestigationSubmissionProgress(
    val clientState: InvestigationClientState,
    val sessionId: String? = null,
    val backendStatus: BackendSessionStatus? = null,
    val investigationId: String? = null,
    val compactResult: BackendCompactResultDto? = null,
  val analyzeAccepted: Boolean? = null,
    val pollAfterMs: Int? = null,
    val imageCount: Int? = null,
  val uploadedImageCount: Int? = null,
  val totalImageCount: Int? = null,
    val explanationPresent: Boolean? = null,
    val message: String? = null,
)

internal sealed class InvestigationSubmissionOutcome {
  data class Completed(
      val session: BackendSessionDto,
      val analyzeResponse: BackendSessionAnalyzeResponseDto,
      val polling: BackendPollingResponseDto,
  ) : InvestigationSubmissionOutcome()

  data class Failed(
      val session: BackendSessionDto?,
      val polling: BackendPollingResponseDto?,
      val error: InvestigationClientError,
  ) : InvestigationSubmissionOutcome()

  data class Cancelled(
      val session: BackendSessionDto?,
  ) : InvestigationSubmissionOutcome()
}

internal class InvestigationSessionRepository(
    private val api: InvestigationSessionApi,
  private val normalizeImageEvidence: (InvestigationEvidenceInput) -> InvestigationEvidenceInput = ::normalizeImageEvidenceForBackend,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Instant = { Instant.now() },
    private val maxPollAttempts: Int = 20,
    private val maxPollingDurationMs: Long = Duration.ofMinutes(2).toMillis(),
) {
  private val submissionMutex = Mutex()

  suspend fun createSession(clientMetadata: Map<String, Any?>? = null, projectId: String? = null): BackendSessionDto {
    return api.createSession(BackendSessionCreateRequestDto(clientMetadata = clientMetadata, projectId = projectId))
  }

  suspend fun getSession(sessionId: String): BackendSessionDto {
    return api.getSession(sessionId)
  }

  suspend fun pollSession(sessionId: String): BackendPollingResponseDto {
    return api.pollSession(sessionId)
  }

  suspend fun submitTrustDecision(
      projectId: String,
      sessionId: String,
      decision: BackendTrustDecision,
      correction: String? = null,
  ): BackendTrustDecisionResponseDto {
    return api.submitTrustDecision(
        projectId = projectId,
        sessionId = sessionId,
        request = BackendTrustDecisionRequestDto(decision = decision, correction = correction),
    )
  }

  suspend fun submitInvestigation(
      draft: InvestigationSubmissionDraft,
      onProgress: (InvestigationSubmissionProgress) -> Unit = {},
  ): InvestigationSubmissionOutcome {
    if (!submissionMutex.tryLock()) {
      return InvestigationSubmissionOutcome.Failed(
          session = null,
          polling = null,
          error = InvestigationClientError.DuplicateSubmission,
      )
    }

    var session: BackendSessionDto? = null
    try {
      onProgress(InvestigationSubmissionProgress(clientState = InvestigationClientState.PREPARING))
      validateDraft(draft)
      val explanation = draft.explanationText.trim()

      onProgress(InvestigationSubmissionProgress(clientState = InvestigationClientState.CREATING_SESSION))
      session =
          draft.continuationSessionId?.let { getSession(it) }
              ?: createSession(clientMetadata = draft.clientMetadata, projectId = draft.projectId)

      // If Analyze succeeded but its response was lost, the canonical session may already be
      // finalizing, analyzing, or complete. Reconcile it before uploading or creating anything.
      if (draft.continuationSessionId != null && session.status in setOf(
              BackendSessionStatus.FINALIZING,
              BackendSessionStatus.ANALYZING,
              BackendSessionStatus.COMPLETED,
          )) {
        val canonical = if (session.status == BackendSessionStatus.COMPLETED) {
          pollSession(session.sessionId)
        } else {
          pollUntilTerminal(session.sessionId, onProgress)
        }
        if (canonical.status == BackendSessionStatus.COMPLETED && canonical.resultAvailable && canonical.compactResult != null) {
          val reconciledAnalyze = BackendSessionAnalyzeResponseDto(
              sessionId = canonical.sessionId,
              investigationId = canonical.investigationId,
              status = canonical.status,
              accepted = true,
              resultAvailable = true,
              compactResult = canonical.compactResult,
              retryable = canonical.retryable,
              error = canonical.error,
              pollUrl = "/investigation-sessions/${canonical.sessionId}/poll",
          )
          onProgress(InvestigationSubmissionProgress(
              clientState = InvestigationClientState.COMPLETED,
              sessionId = canonical.sessionId,
              backendStatus = canonical.status,
              investigationId = canonical.investigationId,
              compactResult = canonical.compactResult,
              analyzeAccepted = true,
              imageCount = canonical.imageCount,
              explanationPresent = canonical.explanationPresent,
              message = "Recovered canonical Investigation result.",
          ))
          return InvestigationSubmissionOutcome.Completed(session, reconciledAnalyze, canonical)
        }
      }

        val totalImages = draft.evidence.size
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.UPLOADING_EVIDENCE,
              sessionId = session.sessionId,
              backendStatus = session.status,
            uploadedImageCount = 0,
            totalImageCount = totalImages,
            message = "Uploading image evidence and explanation.",
          ),
      )

      draft.evidence.forEachIndexed { index, item ->
        ensureNotCancelled()
        val explanationForUpload = if (index == 0) explanation else null
        uploadEvidence(
            sessionId = session.sessionId,
            item = item,
            explanationText = explanationForUpload,
        )
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.UPLOADING_EVIDENCE,
            sessionId = session.sessionId,
            backendStatus = session.status,
            uploadedImageCount = index + 1,
            totalImageCount = totalImages,
            message = "Uploaded ${index + 1}/$totalImages images.",
          ),
        )
      }

        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.INITIATING_ANALYSIS,
            sessionId = session.sessionId,
            backendStatus = session.status,
            uploadedImageCount = totalImages,
            totalImageCount = totalImages,
            message = "Initiating analysis.",
          ),
        )

        val analyzeResponse = api.analyzeSession(session.sessionId, BackendSessionAnalyzeRequestDto())

        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.INITIATING_ANALYSIS,
            sessionId = analyzeResponse.sessionId,
            backendStatus = analyzeResponse.status,
            investigationId = analyzeResponse.investigationId,
            compactResult = analyzeResponse.compactResult,
            analyzeAccepted = analyzeResponse.accepted,
            message =
              if (analyzeResponse.resultAvailable) {
              "Analyze completed synchronously."
              } else {
              "Analyze accepted; polling for completion."
              },
          ),
        )

        val immediateCompleted =
          analyzeResponse.status == BackendSessionStatus.COMPLETED &&
            analyzeResponse.resultAvailable &&
            analyzeResponse.compactResult != null
        if (immediateCompleted) {
        val completedSession = session.copy(status = BackendSessionStatus.COMPLETED)
        val completedPolling =
          BackendPollingResponseDto(
            sessionId = analyzeResponse.sessionId,
            investigationId = analyzeResponse.investigationId,
            status = analyzeResponse.status,
            createdAt = completedSession.createdAtUtc,
            updatedAt = completedSession.updatedAtUtc,
            imageCount = draft.evidence.size,
            explanationPresent = explanation.isNotBlank(),
            retryable = analyzeResponse.retryable,
            error = analyzeResponse.error,
            compactResult = analyzeResponse.compactResult,
            resultAvailable = analyzeResponse.resultAvailable,
            pollAfterMs = 30_000,
          )
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.COMPLETED,
            sessionId = analyzeResponse.sessionId,
            backendStatus = analyzeResponse.status,
            investigationId = analyzeResponse.investigationId,
            compactResult = analyzeResponse.compactResult,
            analyzeAccepted = analyzeResponse.accepted,
            imageCount = draft.evidence.size,
            explanationPresent = explanation.isNotBlank(),
            message = "Investigation completed.",
          ),
        )
        return InvestigationSubmissionOutcome.Completed(completedSession, analyzeResponse, completedPolling)
        }

        if (analyzeResponse.resultAvailable && analyzeResponse.compactResult == null) {
        val contractError =
          InvestigationClientError.ContractError(
            "Backend returned result_available=true without compact_result.",
          )
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.FAILED,
            sessionId = analyzeResponse.sessionId,
            backendStatus = analyzeResponse.status,
            analyzeAccepted = analyzeResponse.accepted,
            message = contractError.message,
          ),
        )
        return InvestigationSubmissionOutcome.Failed(session, null, contractError)
        }

        if (analyzeResponse.status == BackendSessionStatus.FAILED || analyzeResponse.status == BackendSessionStatus.CANCELLED) {
        val terminalError =
          analyzeResponse.error?.let { InvestigationClientError.BackendError(it.category, it.message) }
            ?: InvestigationClientError.BackendError(
              category = "analysis_terminal_state",
              message = "Analysis ended with status ${analyzeResponse.status.wireValue}.",
            )
        onProgress(
          InvestigationSubmissionProgress(
            clientState = if (analyzeResponse.status == BackendSessionStatus.CANCELLED) InvestigationClientState.CANCELLED else InvestigationClientState.FAILED,
            sessionId = analyzeResponse.sessionId,
            backendStatus = analyzeResponse.status,
            investigationId = analyzeResponse.investigationId,
            analyzeAccepted = analyzeResponse.accepted,
            message = terminalError.message,
          ),
        )
        return InvestigationSubmissionOutcome.Failed(session, null, terminalError)
        }

        if (!analyzeResponse.retryable && analyzeResponse.error != null) {
        val nonRetryable = InvestigationClientError.BackendError(analyzeResponse.error.category, analyzeResponse.error.message)
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.FAILED,
            sessionId = analyzeResponse.sessionId,
            backendStatus = analyzeResponse.status,
            investigationId = analyzeResponse.investigationId,
            analyzeAccepted = analyzeResponse.accepted,
            message = nonRetryable.message,
          ),
        )
        return InvestigationSubmissionOutcome.Failed(session, null, nonRetryable)
        }

        val polling = pollUntilTerminal(session.sessionId, onProgress = onProgress)
        if (polling.status == BackendSessionStatus.COMPLETED && polling.resultAvailable && polling.compactResult != null) {
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.COMPLETED,
            sessionId = polling.sessionId,
            backendStatus = polling.status,
            investigationId = polling.investigationId,
            compactResult = polling.compactResult,
            analyzeAccepted = analyzeResponse.accepted,
            pollAfterMs = polling.pollAfterMs,
            imageCount = polling.imageCount,
            explanationPresent = polling.explanationPresent,
            message = "Investigation completed.",
          ),
        )
        return InvestigationSubmissionOutcome.Completed(session, analyzeResponse, polling)
        }

        val terminalError =
          polling.error?.let { InvestigationClientError.BackendError(it.category, it.message) }
            ?: InvestigationClientError.BackendError(
              category = "analysis_not_completed",
              message = "Session ended without a completed compact result.",
            )
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session.sessionId,
            backendStatus = polling.status,
            investigationId = polling.investigationId,
            analyzeAccepted = analyzeResponse.accepted,
            pollAfterMs = polling.pollAfterMs,
            imageCount = polling.imageCount,
            explanationPresent = polling.explanationPresent,
            message = terminalError.message,
          ),
      )
        return InvestigationSubmissionOutcome.Failed(session, polling, terminalError)
    } catch (cancellation: CancellationException) {
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.CANCELLED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = "Cancelled.",
          ),
      )
      throw cancellation
    } catch (error: BackendApiException) {
      val mapped = InvestigationClientError.BackendError(error.error.category, error.error.message)
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = mapped.message,
          ),
      )
      return InvestigationSubmissionOutcome.Failed(session, null, mapped)
    } catch (error: BackendSerializationException) {
      val mapped = InvestigationClientError.SerializationError(error.message ?: "Backend response was invalid.")
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = mapped.message,
          ),
      )
      return InvestigationSubmissionOutcome.Failed(session, null, mapped)
      } catch (error: InvestigationEvidenceConversionException) {
        val mapped = InvestigationClientError.EvidenceConversionError(error.message)
        onProgress(
          InvestigationSubmissionProgress(
            clientState = InvestigationClientState.FAILED,
            sessionId = session?.sessionId,
            backendStatus = session?.status,
            message = mapped.message,
          ),
        )
        return InvestigationSubmissionOutcome.Failed(session, null, mapped)
    } catch (error: IllegalArgumentException) {
      val mapped = InvestigationClientError.ValidationError(error.message ?: "Invalid investigation input.")
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = mapped.message,
          ),
      )
      return InvestigationSubmissionOutcome.Failed(session, null, mapped)
    } catch (error: IllegalStateException) {
      val mapped = InvestigationClientError.TimeoutError(error.message ?: "Polling timed out.")
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = mapped.message,
          ),
      )
      return InvestigationSubmissionOutcome.Failed(session, null, mapped)
    } catch (error: Exception) {
      val mapped = InvestigationClientError.NetworkError(error.message ?: error::class.java.simpleName)
      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.FAILED,
              sessionId = session?.sessionId,
              backendStatus = session?.status,
              message = mapped.message,
          ),
      )
      return InvestigationSubmissionOutcome.Failed(session, null, mapped)
    } finally {
      submissionMutex.unlock()
    }
  }

  suspend fun checkConnectivity(clientMetadata: Map<String, Any?>? = null, projectId: String? = null): BackendPollingResponseDto {
    val session = createSession(clientMetadata = clientMetadata, projectId = projectId)
    return pollSession(session.sessionId)
  }

  private suspend fun uploadEvidence(
      sessionId: String,
      item: InvestigationEvidenceInput,
      explanationText: String?,
  ): BackendEvidenceDto {
    val normalizedItem = normalizeImageEvidence(item)
    val request = BackendEvidenceUploadRequestDto(
        source = "android",
        clientTimestampUtc = Instant.now(),
        normalizedText = explanationText,
      metadata = mapOf(
        "capture_source" to item.source.wireValue,
        "capture_source_label" to item.source.displayLabel,
        "capture_slot_index" to item.slotIndex,
      ),
        filename = normalizedItem.filename,
        mimeType = normalizedItem.mimeType,
    )
    val payload = BackendEvidencePayloadDto(
        filename = normalizedItem.filename,
        mimeType = normalizedItem.mimeType,
        bytes = normalizedItem.bytes,
    )
    return api.uploadImageEvidence(sessionId, request, payload)
  }

  private fun validateDraft(draft: InvestigationSubmissionDraft) {
    if (draft.evidence.isEmpty()) {
      throw IllegalArgumentException("At least one image is required.")
    }
    if (draft.explanationText.trim().isEmpty()) {
      throw IllegalArgumentException("Explanation is required before starting investigation.")
    }
    if (draft.evidence.size > InvestigationCaptureSlots.MAX_CAPTURE_SLOTS) {
      throw IllegalArgumentException("At most five images are allowed.")
    }
    if (draft.evidence.any { it.bytes.isEmpty() }) {
      throw IllegalArgumentException("Evidence payload cannot be empty.")
    }
  }

  private suspend fun pollUntilTerminal(
      sessionId: String,
      onProgress: (InvestigationSubmissionProgress) -> Unit,
  ): BackendPollingResponseDto {
    val startedAt = clock()
    var attempts = 0

    while (true) {
      ensureNotCancelled()
      if (attempts >= maxPollAttempts) {
        throw IllegalStateException("Polling limit reached before completion.")
      }
      if (Duration.between(startedAt, clock()).toMillis() > maxPollingDurationMs) {
        throw IllegalStateException("Polling timed out before completion.")
      }

      val polling = api.pollSession(sessionId)
      attempts += 1

      onProgress(
          InvestigationSubmissionProgress(
              clientState = InvestigationClientState.POLLING,
              sessionId = polling.sessionId,
              backendStatus = polling.status,
              investigationId = polling.investigationId,
              compactResult = polling.compactResult,
              pollAfterMs = polling.pollAfterMs,
              imageCount = polling.imageCount,
              explanationPresent = polling.explanationPresent,
              message = "Polling backend status.",
          ),
      )

      val isTerminal =
          polling.status in setOf(BackendSessionStatus.COMPLETED, BackendSessionStatus.FAILED, BackendSessionStatus.CANCELLED)
      if (isTerminal) {
        return polling
      }

      if (!polling.retryable && polling.error != null) {
        return polling
      }

      val delayMs = polling.pollAfterMs.coerceIn(250, 60_000).toLong()
      pollDelay(delayMs)
    }
  }

  private suspend fun ensureNotCancelled() {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
  }
}
