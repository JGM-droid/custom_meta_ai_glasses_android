package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InvestigationBackendModelsTest {
  @Test
  fun pollingResponseDeserializesNullableFields() {
    val json =
        JSONObject()
            .put("session_id", "123e4567-e89b-12d3-a456-426614174000")
            .put("investigation_id", JSONObject.NULL)
            .put("status", "collecting")
            .put("created_at", "2026-07-18T12:00:00Z")
            .put("updated_at", "2026-07-18T12:00:05Z")
            .put("image_count", 2)
            .put("explanation_present", true)
            .put("retryable", true)
            .put("error", JSONObject.NULL)
            .put("compact_result", JSONObject.NULL)
            .put("result_available", false)
            .put("poll_after_ms", 5000)

    val dto = BackendPollingResponseDto.fromJsonObject(json)

    assertEquals("123e4567-e89b-12d3-a456-426614174000", dto.sessionId)
    assertEquals(null, dto.investigationId)
    assertEquals(BackendSessionStatus.COLLECTING, dto.status)
    assertEquals(2, dto.imageCount)
    assertTrue(dto.explanationPresent)
    assertFalse(dto.resultAvailable)
    assertEquals(5000, dto.pollAfterMs)
  }

  @Test
  fun compactResultRoundTrips() {
    val dto =
        BackendCompactResultDto(
            schemaVersion = "1.0",
            projectionVersion = "1.0",
            investigationId = "inv_123",
            status = BackendAnalysisStatus.ANALYZED,
            diagnosisShort = "Brake cable loose",
            requiredNextActionShort = "Tighten the cable",
            uncertaintyFlag = false,
            freshnessState = "fresh",
            completedAtUtc = Instant.parse("2026-07-18T12:00:00Z"),
            ageSeconds = 4,
        )

    val parsed = BackendCompactResultDto.fromJsonObject(dto.toJsonObject())

    assertEquals(dto, parsed)
  }

  @Test
  fun sessionStatusEnumDeserializesLowercaseValues() {
    assertEquals(BackendSessionStatus.FINALIZING, BackendSessionStatus.fromWireValue("finalizing"))
    assertEquals(BackendSessionStatus.COMPLETED, BackendSessionStatus.fromWireValue("completed"))
  }

  @Test
  fun apiErrorMappingUsesCategoryAndMessage() {
    val apiError = BackendApiErrorDto(category = "session_not_found", message = "Session does not exist.")

    assertEquals("session_not_found", apiError.category)
    assertEquals("Session does not exist.", apiError.message)
  }

  @Test
  fun analyzeRequestSerializesExpectedRevisionWhenPresent() {
    val json = BackendSessionAnalyzeRequestDto(expectedRevision = 7).toJsonObject()

    assertEquals(7, json.getInt("expected_revision"))
  }

  @Test
  fun analyzeRequestAllowsEmptyBodyWhenExpectedRevisionMissing() {
    val json = BackendSessionAnalyzeRequestDto(expectedRevision = null).toJsonObject()

    assertEquals(0, json.length())
  }

  @Test
  fun analyzeResponseDeserializesNullableFields() {
    val json =
        JSONObject()
            .put("session_id", "123e4567-e89b-12d3-a456-426614174000")
            .put("investigation_id", JSONObject.NULL)
            .put("status", "analyzing")
            .put("accepted", true)
            .put("result_available", false)
            .put("compact_result", JSONObject.NULL)
            .put("retryable", true)
            .put("error", JSONObject.NULL)
            .put("poll_url", "/investigation-sessions/123e4567-e89b-12d3-a456-426614174000/poll")

    val dto = BackendSessionAnalyzeResponseDto.fromJsonObject(json)

    assertEquals("123e4567-e89b-12d3-a456-426614174000", dto.sessionId)
    assertEquals(null, dto.investigationId)
    assertEquals(BackendSessionStatus.ANALYZING, dto.status)
    assertTrue(dto.accepted)
    assertFalse(dto.resultAvailable)
    assertEquals(null, dto.compactResult)
    assertEquals(true, dto.retryable)
    assertEquals(null, dto.error)
    assertEquals("/investigation-sessions/123e4567-e89b-12d3-a456-426614174000/poll", dto.pollUrl)
  }

  @Test
  fun unknownSessionStatusMapsSafely() {
    val parsed = BackendSessionStatus.fromWireValue("new_status_from_backend")
    assertEquals(BackendSessionStatus.UNKNOWN, parsed)
  }
}
