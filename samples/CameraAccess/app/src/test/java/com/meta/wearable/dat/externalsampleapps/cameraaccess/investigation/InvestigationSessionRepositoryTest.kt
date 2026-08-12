package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationSessionRepositoryTest {
  @Test
  fun rejectsEmptyImagesBeforeNetworking() = runBlocking {
    val api = FakeInvestigationSessionApi()
    val repository = InvestigationSessionRepository(api = api)

    val outcome =
        repository.submitInvestigation(
            draft = InvestigationSubmissionDraft(evidence = emptyList(), explanationText = "test"),
        )

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.ValidationError)
    assertEquals(0, api.createSessionCalls)
  }

  @Test
  fun rejectsMoreThanThreeImagesBeforeNetworking() = runBlocking {
    val api = FakeInvestigationSessionApi()
    val repository = InvestigationSessionRepository(api = api)
    val draft =
        InvestigationSubmissionDraft(
            evidence = listOf(image("1.jpg"), image("2.jpg"), image("3.jpg"), image("4.jpg")),
            explanationText = "test",
        )

    val outcome = repository.submitInvestigation(draft)

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.ValidationError)
    assertEquals(0, api.createSessionCalls)
  }

  @Test
  fun rejectsBlankExplanationBeforeNetworking() = runBlocking {
    val api = FakeInvestigationSessionApi()
    val repository = InvestigationSessionRepository(api = api)

    val outcome =
        repository.submitInvestigation(
            draft = InvestigationSubmissionDraft(evidence = listOf(image("one.jpg")), explanationText = "   "),
        )

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.ValidationError)
    assertTrue(failed.error.message.contains("Explanation is required", ignoreCase = true))
    assertEquals(0, api.createSessionCalls)
  }

  @Test
  fun fullWorkflowSequenceIsCreateUploadAnalyzePoll() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
            pollResponses = mutableListOf(poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult())),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome =
        repository.submitInvestigation(
            InvestigationSubmissionDraft(
                evidence = listOf(image("one.jpg"), image("two.jpg")),
                explanationText = "spoken transcript",
            ),
        )

    assertTrue(outcome is InvestigationSubmissionOutcome.Completed)
    assertEquals(
        listOf("create", "upload:one.jpg", "upload:two.jpg", "analyze", "poll"),
        api.callTrace,
    )
  }

  @Test
  fun brandNewSessionDoesNotPerformIllegalResumeTransition() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            failOnResumeFromCreated = true,
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    assertTrue(outcome is InvestigationSubmissionOutcome.Completed)
    assertEquals(listOf("create", "upload:one.jpg", "analyze"), api.callTrace)
  }

  @Test
  fun oneImageInvestigationUploadsExactlyOneImage() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("single.jpg", source = InvestigationEvidenceSource.LIVE_GLASSES)),
            explanationText = "single capture",
        ),
    )

    assertEquals(1, api.uploadedImageFilenames.size)
    assertEquals("single.jpg", api.uploadedImageFilenames.single())
  }

  @Test
  fun analysisStartsOnlyAfterEvidenceUploadSucceeds() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("one.jpg"), image("two.jpg")),
            explanationText = "order check",
        ),
    )

    assertEquals(listOf("create", "upload:one.jpg", "upload:two.jpg", "analyze"), api.callTrace)
  }

  @Test
  fun sessionCreationFailurePreventsUpload() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            createException = BackendApiException(503, BackendApiErrorDto("backend_unreachable", "Backend is unavailable.")),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.BackendError)
    assertEquals(0, api.uploadedImageFilenames.size)
    assertEquals(0, api.analyzeCalls)
  }

  @Test
  fun uploadFailurePreventsAnalyze() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            uploadException = BackendApiException(422, BackendApiErrorDto("upload_rejected", "Image evidence failed validation.")),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    val backend = failed.error as InvestigationClientError.BackendError
    assertEquals("upload_rejected", backend.category)
    assertEquals(0, api.analyzeCalls)
  }

  @Test
  fun analyzeIsCalledExactlyOnceAndPollingNeverReinvokesAnalyze() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
            pollResponses =
                mutableListOf(
                    poll(status = BackendSessionStatus.FINALIZING, resultAvailable = false),
                    poll(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
                    poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()),
                ),
        )
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "explanation"))

    assertEquals(1, api.analyzeCalls)
  }

  @Test
  fun explanationIsSentOnlyThroughSupportedNormalizedTextField() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("one.jpg"), image("two.jpg")),
            explanationText = "spoken transcript",
        ),
    )

    assertEquals(listOf("spoken transcript", null), api.uploadedExplanationTexts)
  }

  @Test
  fun explanationIsTrimmedAndSentOnFirstUploadBeforeAnalyze() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("one.jpg")),
            explanationText = "  spoken transcript  ",
        ),
    )

    assertEquals(listOf("spoken transcript"), api.uploadedExplanationTexts)
    assertEquals(listOf("create", "upload:one.jpg", "analyze"), api.callTrace)
  }

  @Test
  fun immediateCompletedAnalyzeResponseSkipsPolling() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    assertTrue(outcome is InvestigationSubmissionOutcome.Completed)
    assertEquals(0, api.pollCalls)
  }

  @Test
  fun nonTerminalAnalyzeResponseEntersPolling() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.FINALIZING, resultAvailable = false),
            pollResponses = mutableListOf(poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult())),
        )
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    assertEquals(1, api.pollCalls)
  }

  @Test
  fun pollAfterHintIsRespected() = runBlocking {
    val delays = mutableListOf<Long>()
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
            pollResponses =
                mutableListOf(
                    poll(status = BackendSessionStatus.ANALYZING, resultAvailable = false, pollAfterMs = 1500),
                    poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult(), pollAfterMs = 30000),
                ),
        )
    val repository = InvestigationSessionRepository(api = api, pollDelay = { delays += it })

    repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    assertEquals(listOf(1500L), delays)
  }

  @Test
  fun pollingCompletedResponseReturnsCompactResult() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
            pollResponses = mutableListOf(poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult())),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val completed = outcome as InvestigationSubmissionOutcome.Completed
    assertEquals(BackendSessionStatus.COMPLETED, completed.polling.status)
    assertTrue(completed.polling.resultAvailable)
    assertTrue(completed.polling.compactResult != null)
  }

  @Test
  fun analyzeFailureStopsWorkflow() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeException = BackendApiException(500, BackendApiErrorDto("provider_failure", "Analysis provider is unavailable.")),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.BackendError)
    assertEquals(0, api.pollCalls)
  }

  @Test
  fun nonRetryableAnalyzeErrorIsNotRetried() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeException = BackendApiException(422, BackendApiErrorDto("missing_explanation", "A non-empty normalized explanation is required before analysis.")),
        )
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "has explanation"))

    assertEquals(1, api.analyzeCalls)
  }

  @Test
  fun analysisAttemptConflictIsSurfacedClearly() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeException = BackendApiException(409, BackendApiErrorDto("analysis_attempt_conflict", "Session analysis is already in progress.")),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    val backend = failed.error as InvestigationClientError.BackendError
    assertEquals("analysis_attempt_conflict", backend.category)
  }

  @Test
  fun completedAnalyzeResponseBehavesIdempotentlyWithoutSecondAnalyze() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    assertTrue(outcome is InvestigationSubmissionOutcome.Completed)
    assertEquals(1, api.analyzeCalls)
    assertEquals(0, api.pollCalls)
  }

  @Test
  fun missingEvidenceErrorIsMapped() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeException = BackendApiException(422, BackendApiErrorDto("insufficient_evidence", "At least one accepted image is required.")))
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    val backend = failed.error as InvestigationClientError.BackendError
    assertEquals("insufficient_evidence", backend.category)
  }

  @Test
  fun missingExplanationErrorIsMapped() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeException = BackendApiException(422, BackendApiErrorDto("missing_explanation", "A non-empty normalized explanation is required before analysis.")))
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "has explanation"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    val backend = failed.error as InvestigationClientError.BackendError
    assertEquals("missing_explanation", backend.category)
  }

  @Test
  fun transportFailureIsMapped() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeException = IOException("socket closed"))
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.NetworkError)
  }

  @Test
  fun cancellationStopsPolling() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
            pollResponses = MutableList(10) { poll(status = BackendSessionStatus.ANALYZING, resultAvailable = false, pollAfterMs = 1000) },
        )
    val repository = InvestigationSessionRepository(api = api, pollDelay = { delay(200) })

    val job = async { repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done")) }
    delay(50)
    job.cancel(CancellationException("cancel"))
    try {
      job.await()
    } catch (_: CancellationException) {
      // expected
    }

    assertTrue(api.pollCalls >= 1)
    assertEquals(1, api.analyzeCalls)
  }

    @Test
    fun pollingTerminatesAtClientTimeout() = runBlocking {
    val api =
      FakeInvestigationSessionApi(
        analyzeResponse = analyzeResponse(status = BackendSessionStatus.ANALYZING, resultAvailable = false),
        pollResponses = MutableList(10) { poll(status = BackendSessionStatus.ANALYZING, resultAvailable = false, pollAfterMs = 250) },
      )
    val repository =
      InvestigationSessionRepository(
        api = api,
        pollDelay = { },
        maxPollAttempts = 1,
        maxPollingDurationMs = 60_000,
      )

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.TimeoutError)
    }

  @Test
  fun duplicateSubmissionIsPrevented() = runBlocking {
    val api = FakeInvestigationSessionApi(delayOnUploadMs = 50)
    val repository = InvestigationSessionRepository(api = api)
    val draft = InvestigationSubmissionDraft(evidence = listOf(image("first.jpg")), explanationText = "test")

    val first = async { repository.submitInvestigation(draft) }
    delay(5)
    val second = repository.submitInvestigation(draft)
    first.await()

    val duplicate = second as InvestigationSubmissionOutcome.Failed
    assertTrue(duplicate.error is InvestigationClientError.DuplicateSubmission)
  }

  @Test
  fun preservesImageOrderDuringUpload() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("first.jpg"), image("second.jpg"), image("third.jpg")),
            explanationText = "Brake noise",
        ),
    )

    assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), api.uploadedImageFilenames)
  }

  @Test
  fun jpegEvidenceRemainsValid() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("camera.jpg", mimeType = "image/jpeg")),
            explanationText = "Brake noise",
        ),
    )

    assertEquals(listOf("camera.jpg"), api.uploadedImageFilenames)
    assertEquals(listOf("image/jpeg"), api.uploadedImageMimeTypes)
  }

  @Test
  fun pngEvidenceRemainsValid() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("picker.png", mimeType = "image/png")),
            explanationText = "Brake noise",
        ),
    )

    assertEquals(listOf("picker.png"), api.uploadedImageFilenames)
    assertEquals(listOf("image/png"), api.uploadedImageMimeTypes)
  }

  @Test
  fun heicEvidenceIsConvertedToJpegBeforeUpload() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository =
        InvestigationSessionRepository(
            api = api,
            normalizeImageEvidence = { evidence ->
              if (evidence.mimeType == "image/heic" || evidence.mimeType == "image/heif") {
                evidence.copy(filename = "${evidence.filename.substringBeforeLast('.')}.jpg", mimeType = "image/jpeg")
              } else {
                evidence
              }
            },
        )

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("capture_1.heic", mimeType = "image/heic")),
            explanationText = "Brake noise",
        ),
    )

    assertEquals(listOf("capture_1.jpg"), api.uploadedImageFilenames)
    assertEquals(listOf("image/jpeg"), api.uploadedImageMimeTypes)
  }

  @Test
  fun convertedEvidencePreservesUploadOrder() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository =
        InvestigationSessionRepository(
            api = api,
            normalizeImageEvidence = { evidence ->
              if (evidence.mimeType == "image/heic" || evidence.mimeType == "image/heif") {
                evidence.copy(filename = "${evidence.filename.substringBeforeLast('.')}.jpg", mimeType = "image/jpeg")
              } else {
                evidence
              }
            },
        )

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence =
                listOf(
                    image("first.heic", mimeType = "image/heic"),
                    image("second.jpg", mimeType = "image/jpeg"),
                    image("third.heif", mimeType = "image/heif"),
                ),
            explanationText = "Brake noise",
        ),
    )

    assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), api.uploadedImageFilenames)
    assertEquals(listOf("image/jpeg", "image/jpeg", "image/jpeg"), api.uploadedImageMimeTypes)
  }

  @Test
  fun conversionFailureSurfacesExplicitError() = runBlocking {
    val api = FakeInvestigationSessionApi()
    val repository =
        InvestigationSessionRepository(
            api = api,
            normalizeImageEvidence = {
              throw InvestigationEvidenceConversionException("Failed to convert HEIC evidence capture_1.heic to JPEG for backend upload.")
            },
        )

    val outcome =
        repository.submitInvestigation(
            InvestigationSubmissionDraft(
                evidence = listOf(image("capture_1.heic", mimeType = "image/heic")),
                explanationText = "Brake noise",
            ),
        )

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertTrue(failed.error is InvestigationClientError.EvidenceConversionError)
    assertTrue(failed.error.message.contains("Failed to convert HEIC evidence", ignoreCase = true))
  }

  @Test
  fun liveGlassesCaptureMetadataIsPreservedOnUpload() = runBlocking {
    val api = FakeInvestigationSessionApi(analyzeResponse = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()))
    val repository = InvestigationSessionRepository(api = api)

    repository.submitInvestigation(
        InvestigationSubmissionDraft(
            evidence = listOf(image("live.jpg", source = InvestigationEvidenceSource.LIVE_GLASSES)),
            explanationText = "Brake noise",
        ),
    )

    assertEquals("live_glasses", api.uploadedEvidenceMetadata.single()["capture_source"])
    assertEquals(0, api.uploadedEvidenceMetadata.single()["capture_slot_index"])
  }

  @Test
  fun defaultBackendUrlIsEmulator8001() {
    assertEquals("http://10.0.2.2:8001", InvestigationBackendConfig.resolveBaseUrl(""))
  }

  @Test
  fun noDirectAndroidToOpenAiRouteExistsInSessionClient() {
    val apiMethods = InvestigationSessionApi::class.java.methods.map { it.name }
    assertFalse(apiMethods.any { it.contains("openai", ignoreCase = true) })
  }

  @Test
  fun obsoleteBackendCapabilityGapIsNoLongerReturnedAfterUpload() = runBlocking {
    val api =
        FakeInvestigationSessionApi(
            analyzeResponse = analyzeResponse(status = BackendSessionStatus.FAILED, resultAvailable = false, retryable = false),
            pollResponses = mutableListOf(),
        )
    val repository = InvestigationSessionRepository(api = api)

    val outcome = repository.submitInvestigation(InvestigationSubmissionDraft(listOf(image("one.jpg")), "done"))

    val failed = outcome as InvestigationSubmissionOutcome.Failed
    assertFalse(failed.error.message.contains("capability gap", ignoreCase = true))
  }

  private fun image(
      name: String,
      mimeType: String = "image/jpeg",
      source: InvestigationEvidenceSource = InvestigationEvidenceSource.LOCAL_PICKER,
  ): InvestigationEvidenceInput {
    return InvestigationEvidenceInput(
        slotIndex = 0,
        filename = name,
        mimeType = mimeType,
        bytes = byteArrayOf(1, 2, 3),
        source = source,
    )
  }
}

private class FakeInvestigationSessionApi(
    private val delayOnUploadMs: Long = 0,
  private val requireCollectingBeforeUpload: Boolean = false,
  private val failOnResumeFromCreated: Boolean = false,
  private val createException: Exception? = null,
  private val uploadException: Exception? = null,
    private val analyzeException: Exception? = null,
    private val analyzeResponse: BackendSessionAnalyzeResponseDto = analyzeResponse(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult()),
    private val pollResponses: MutableList<BackendPollingResponseDto> = mutableListOf(),
) : InvestigationSessionApi {
  companion object {
    const val SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
  }

  var createSessionCalls: Int = 0
    private set
  var analyzeCalls: Int = 0
    private set
  var pollCalls: Int = 0
    private set
  val uploadedImageFilenames = mutableListOf<String>()
  val uploadedImageMimeTypes = mutableListOf<String>()
  val uploadedExplanationTexts = mutableListOf<String?>()
  val uploadedEvidenceMetadata = mutableListOf<Map<String, Any?>>()
  val callTrace = mutableListOf<String>()
  private var currentSessionStatus: BackendSessionStatus = BackendSessionStatus.CREATED

  override suspend fun createSession(request: BackendSessionCreateRequestDto): BackendSessionDto {
    callTrace += "create"
    createSessionCalls += 1
    createException?.let { throw it }
    currentSessionStatus = BackendSessionStatus.CREATED
    return session(status = BackendSessionStatus.CREATED, revision = 0)
  }

  override suspend fun getSession(sessionId: String): BackendSessionDto = session(status = currentSessionStatus)

  override suspend fun pollSession(sessionId: String): BackendPollingResponseDto {
    callTrace += "poll"
    pollCalls += 1
    if (pollResponses.isNotEmpty()) {
      return pollResponses.removeAt(0)
    }
    return poll(status = BackendSessionStatus.COMPLETED, resultAvailable = true, compact = compactResult())
  }

  override suspend fun analyzeSession(
      sessionId: String,
      request: BackendSessionAnalyzeRequestDto,
  ): BackendSessionAnalyzeResponseDto {
    callTrace += "analyze"
    analyzeCalls += 1
    analyzeException?.let { throw it }
    return analyzeResponse
  }

  override suspend fun pauseSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    currentSessionStatus = BackendSessionStatus.PAUSED
    return session(status = BackendSessionStatus.PAUSED)
  }

  override suspend fun resumeSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    if (failOnResumeFromCreated && currentSessionStatus == BackendSessionStatus.CREATED) {
      throw BackendApiException(
          409,
          BackendApiErrorDto(
              "invalid_state_transition",
              "Session cannot be resumed from the current state.",
          ),
      )
    }
    callTrace += "resume"
    currentSessionStatus = BackendSessionStatus.COLLECTING
    return session(status = BackendSessionStatus.COLLECTING, revision = 1)
  }

  override suspend fun cancelSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    currentSessionStatus = BackendSessionStatus.CANCELLED
    return session(status = BackendSessionStatus.CANCELLED)
  }

  override suspend fun uploadImageEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto {
    if (delayOnUploadMs > 0) {
      delay(delayOnUploadMs)
    }
    if (currentSessionStatus == BackendSessionStatus.CREATED) {
      currentSessionStatus = BackendSessionStatus.COLLECTING
    }
    if (requireCollectingBeforeUpload && currentSessionStatus != BackendSessionStatus.COLLECTING) {
      throw BackendApiException(
        409,
        BackendApiErrorDto(
          "invalid_state_transition",
          "Evidence can only be uploaded while collecting.",
        ),
      )
    }
    uploadException?.let { throw it }
    callTrace += "upload:${payload.filename}"
    uploadedImageFilenames += payload.filename
    uploadedImageMimeTypes += payload.mimeType
    uploadedExplanationTexts += request.normalizedText
    uploadedEvidenceMetadata += request.metadata ?: emptyMap()
    return evidence(payload.filename)
  }

  override suspend fun uploadAudioEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto {
    return evidence(payload.filename)
  }

  private fun session(
      status: BackendSessionStatus,
      revision: Int = 1,
  ): BackendSessionDto {
    return BackendSessionDto(
        schemaVersion = "2.0",
        sessionId = SESSION_ID,
        status = status,
        revision = revision,
        createdAtUtc = Instant.parse("2026-07-18T10:00:00Z"),
        updatedAtUtc = Instant.parse("2026-07-18T10:00:01Z"),
        pausedAtUtc = null,
        cancelledAtUtc = null,
        clientMetadata = null,
        currentAnalysisAttemptId = null,
        activeAnalysisAttemptId = null,
        latestAnalysisAttemptId = null,
        completedResultId = null,
        lastError = null,
    )
  }

  private fun evidence(filename: String): BackendEvidenceDto {
    return BackendEvidenceDto(
        schemaVersion = "1.0",
        evidenceId = "123e4567-e89b-12d3-a456-426614174111",
        sessionId = SESSION_ID,
        evidenceType = BackendEvidenceType.IMAGE,
        source = "android",
        createdAtUtc = Instant.parse("2026-07-18T10:00:02Z"),
        validationStatus = BackendEvidenceValidationStatus.ACCEPTED,
        sequenceNumber = 1,
        clientTimestampUtc = Instant.parse("2026-07-18T10:00:02Z"),
        filename = filename,
        mimeType = "image/jpeg",
        storageRef = "evidence/payloads/$filename",
        contentHash = null,
        width = null,
        height = null,
        durationSeconds = null,
        normalizedText = null,
        metadata = null,
    )
  }
}

private fun analyzeResponse(
    status: BackendSessionStatus,
    resultAvailable: Boolean,
    compact: BackendCompactResultDto? = null,
    retryable: Boolean = true,
): BackendSessionAnalyzeResponseDto {
  return BackendSessionAnalyzeResponseDto(
      sessionId = FakeInvestigationSessionApi.SESSION_ID,
      investigationId = compact?.investigationId,
      status = status,
      accepted = true,
      resultAvailable = resultAvailable,
      compactResult = compact,
      retryable = retryable,
      error = null,
      pollUrl = "/investigation-sessions/${FakeInvestigationSessionApi.SESSION_ID}/poll",
  )
}

private fun poll(
    status: BackendSessionStatus,
    resultAvailable: Boolean,
    compact: BackendCompactResultDto? = null,
    pollAfterMs: Int = 500,
): BackendPollingResponseDto {
  return BackendPollingResponseDto(
      sessionId = FakeInvestigationSessionApi.SESSION_ID,
      investigationId = compact?.investigationId,
      status = status,
      createdAt = Instant.parse("2026-07-18T10:00:00Z"),
      updatedAt = Instant.parse("2026-07-18T10:00:05Z"),
      imageCount = 1,
      explanationPresent = true,
      retryable = true,
      error = null,
      compactResult = compact,
      resultAvailable = resultAvailable,
      pollAfterMs = pollAfterMs,
  )
}

private fun compactResult(): BackendCompactResultDto {
  return BackendCompactResultDto(
      schemaVersion = "1.0",
      projectionVersion = "1.0",
      investigationId = "inv-id",
      status = BackendAnalysisStatus.ANALYZED,
      diagnosisShort = "Loose cable",
      requiredNextActionShort = "Retighten cable",
      uncertaintyFlag = false,
      freshnessState = "fresh",
      completedAtUtc = Instant.parse("2026-07-18T10:00:07Z"),
      ageSeconds = 0,
  )
}
