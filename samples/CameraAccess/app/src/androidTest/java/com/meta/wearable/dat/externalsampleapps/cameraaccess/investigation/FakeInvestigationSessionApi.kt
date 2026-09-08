/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.time.Instant

/**
 * ADR-060 multimodal bridge test double: a minimal, deterministic [InvestigationSessionApi] so
 * instrumented UI tests exercising [InvestigationSessionDebugViewModel.stagePendingEvidenceForGuidance]
 * never make a real network call to the Investigation backend. Only implements what staging
 * actually calls (createSession/getSession/uploadImageEvidence); every other method fails loudly
 * if ever reached, since this fake is not meant to support the full submit/analyze/poll/trust
 * lifecycle (see InvestigationSessionRepositoryTest.kt's own, separate FakeInvestigationSessionApi
 * for that - this is deliberately not a duplicate of that JVM-only, file-private fake, just a
 * same-shaped minimal stand-in usable from androidTest).
 */
internal class FakeInvestigationSessionApi(
    private val defaultSessionId: String = "22222222-2222-2222-2222-222222222222",
) : InvestigationSessionApi {
  var createSessionCalls: Int = 0
    private set
  val uploadedImageFilenames = mutableListOf<String>()
  var currentStatus: BackendSessionStatus = BackendSessionStatus.CREATED
    private set

  override suspend fun createSession(request: BackendSessionCreateRequestDto): BackendSessionDto {
    createSessionCalls += 1
    currentStatus = BackendSessionStatus.CREATED
    return sessionDto(sessionId = defaultSessionId, projectId = request.projectId)
  }

  // Echoes back the REQUESTED session id (like a real backend would), never this fake's own
  // default - an explicit caller-supplied continuation/session id must reach the guidance request
  // unchanged (see ProjectGuidanceFlowTest's explicitActiveInvestigationSessionIdIsSentThroughUnchanged).
  override suspend fun getSession(sessionId: String): BackendSessionDto = sessionDto(sessionId = sessionId)

  override suspend fun uploadImageEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto {
    currentStatus = BackendSessionStatus.COLLECTING
    uploadedImageFilenames += payload.filename
    return BackendEvidenceDto(
        schemaVersion = "1.0",
        evidenceId = "33333333-3333-3333-3333-333333333333",
        sessionId = sessionId,
        evidenceType = BackendEvidenceType.IMAGE,
        source = "android",
        createdAtUtc = Instant.parse("2026-09-04T00:00:00Z"),
        validationStatus = BackendEvidenceValidationStatus.ACCEPTED,
        sequenceNumber = uploadedImageFilenames.size,
        clientTimestampUtc = Instant.parse("2026-09-04T00:00:00Z"),
        filename = payload.filename,
        mimeType = payload.mimeType,
        storageRef = "evidence/payloads/${payload.filename}",
        contentHash = null,
        width = null,
        height = null,
        durationSeconds = null,
        normalizedText = request.normalizedText,
        metadata = request.metadata,
    )
  }

  override suspend fun pollSession(sessionId: String): BackendPollingResponseDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  override suspend fun analyzeSession(sessionId: String, request: BackendSessionAnalyzeRequestDto): BackendSessionAnalyzeResponseDto =
      error("Staging must never call analyze - if this is reached, the multimodal bridge made a second reasoning call.")

  override suspend fun submitTrustDecision(
      projectId: String,
      sessionId: String,
      request: BackendTrustDecisionRequestDto,
  ): BackendTrustDecisionResponseDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  override suspend fun pauseSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  override suspend fun resumeSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  override suspend fun cancelSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  override suspend fun uploadAudioEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto =
      error("Not used by staging - this fake only supports createSession/getSession/uploadImageEvidence.")

  private fun sessionDto(sessionId: String = defaultSessionId, projectId: String? = null): BackendSessionDto = BackendSessionDto(
      schemaVersion = "2.0",
      sessionId = sessionId,
      status = currentStatus,
      revision = 0,
      createdAtUtc = Instant.parse("2026-09-04T00:00:00Z"),
      updatedAtUtc = Instant.parse("2026-09-04T00:00:00Z"),
      pausedAtUtc = null,
      cancelledAtUtc = null,
      clientMetadata = null,
      currentAnalysisAttemptId = null,
      activeAnalysisAttemptId = null,
      latestAnalysisAttemptId = null,
      completedResultId = null,
      lastError = null,
      projectId = projectId,
  )
}
