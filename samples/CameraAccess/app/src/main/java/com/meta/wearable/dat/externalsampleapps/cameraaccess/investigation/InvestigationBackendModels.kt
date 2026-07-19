package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

internal enum class BackendSessionStatus(val wireValue: String) {
  CREATED("created"),
  COLLECTING("collecting"),
  PAUSED("paused"),
  FINALIZING("finalizing"),
  ANALYZING("analyzing"),
  FAILED("failed"),
  COMPLETED("completed"),
  CANCELLED("cancelled"),
  UNKNOWN("unknown");

  companion object {
    fun fromWireValue(value: String): BackendSessionStatus {
      return entries.firstOrNull { it.wireValue == value.trim() } ?: UNKNOWN
    }
  }
}

internal enum class BackendAnalysisStatus(val wireValue: String) {
  VALIDATED("validated"),
  ANALYZED("analyzed"),
  UNKNOWN("unknown");

  companion object {
    fun fromWireValue(value: String): BackendAnalysisStatus {
      return entries.firstOrNull { it.wireValue == value.trim() } ?: UNKNOWN
    }
  }
}

internal enum class BackendEvidenceType(val wireValue: String) {
  IMAGE("image"),
  AUDIO("audio");

  companion object {
    fun fromWireValue(value: String): BackendEvidenceType {
      return entries.firstOrNull { it.wireValue == value.trim() }
          ?: throw IllegalArgumentException("Unknown evidence type: $value")
    }
  }
}

internal enum class BackendEvidenceValidationStatus(val wireValue: String) {
  ACCEPTED("accepted"),
  DUPLICATE_ACCEPTED("duplicate_accepted");

  companion object {
    fun fromWireValue(value: String): BackendEvidenceValidationStatus {
      return entries.firstOrNull { it.wireValue == value.trim() }
          ?: throw IllegalArgumentException("Unknown evidence validation status: $value")
    }
  }
}

internal data class BackendSessionCreateRequestDto(
    val clientMetadata: Map<String, Any?>? = null,
) {
  fun toJsonObject(): JSONObject =
      JSONObject().apply {
        clientMetadata?.let { put("client_metadata", it.toJsonObject()) }
      }
}

internal data class BackendSessionMutationRequestDto(
    val expectedRevision: Int? = null,
) {
  fun toJsonObject(): JSONObject =
      JSONObject().apply {
        expectedRevision?.let { put("expected_revision", it) }
      }
}

internal data class BackendSessionErrorMetadataDto(
    val errorCategory: String,
    val retryable: Boolean,
    val occurredAtUtc: Instant,
    val safeMessage: String,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("error_category", errorCategory)
          .put("retryable", retryable)
          .put("occurred_at_utc", occurredAtUtc.toWireTimestamp())
          .put("safe_message", safeMessage)

  companion object {
    fun fromJsonObject(json: JSONObject): BackendSessionErrorMetadataDto {
      return BackendSessionErrorMetadataDto(
          errorCategory = json.getString("error_category"),
          retryable = json.getBoolean("retryable"),
          occurredAtUtc = json.getString("occurred_at_utc").parseWireInstant(),
          safeMessage = json.getString("safe_message"),
      )
    }
  }
}

internal data class BackendCompactResultDto(
    val schemaVersion: String,
    val projectionVersion: String,
    val investigationId: String,
    val status: BackendAnalysisStatus,
    val diagnosisShort: String,
    val requiredNextActionShort: String,
    val uncertaintyFlag: Boolean,
    val freshnessState: String,
    val completedAtUtc: Instant,
    val ageSeconds: Int?,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("schema_version", schemaVersion)
          .put("projection_version", projectionVersion)
          .put("investigation_id", investigationId)
          .put("status", status.wireValue)
          .put("diagnosis_short", diagnosisShort)
          .put("required_next_action_short", requiredNextActionShort)
          .put("uncertainty_flag", uncertaintyFlag)
          .put("freshness_state", freshnessState)
          .put("completed_at_utc", completedAtUtc.toWireTimestamp())
          .putNullable("age_seconds", ageSeconds)

  companion object {
    fun fromJsonObject(json: JSONObject): BackendCompactResultDto {
      return BackendCompactResultDto(
          schemaVersion = json.getString("schema_version"),
          projectionVersion = json.getString("projection_version"),
          investigationId = json.getString("investigation_id"),
          status = BackendAnalysisStatus.fromWireValue(json.getString("status")),
          diagnosisShort = json.getString("diagnosis_short"),
          requiredNextActionShort = json.getString("required_next_action_short"),
          uncertaintyFlag = json.getBoolean("uncertainty_flag"),
          freshnessState = json.getString("freshness_state"),
          completedAtUtc = json.getString("completed_at_utc").parseWireInstant(),
          ageSeconds = json.optIntOrNull("age_seconds"),
      )
    }
  }
}

internal data class BackendSessionPollingErrorDto(
    val category: String,
    val message: String,
    val occurredAtUtc: Instant?,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("category", category)
          .put("message", message)
          .putNullable("occurred_at_utc", occurredAtUtc?.toWireTimestamp())

  companion object {
    fun fromJsonObject(json: JSONObject): BackendSessionPollingErrorDto {
      return BackendSessionPollingErrorDto(
          category = json.getString("category"),
          message = json.getString("message"),
          occurredAtUtc = json.optStringOrNull("occurred_at_utc")?.parseWireInstant(),
      )
    }
  }
}

internal data class BackendSessionDto(
    val schemaVersion: String,
    val sessionId: String,
    val status: BackendSessionStatus,
    val revision: Int,
    val createdAtUtc: Instant,
    val updatedAtUtc: Instant,
    val pausedAtUtc: Instant?,
    val cancelledAtUtc: Instant?,
    val clientMetadata: Map<String, Any?>?,
    val currentAnalysisAttemptId: String?,
    val activeAnalysisAttemptId: String?,
    val latestAnalysisAttemptId: String?,
    val completedResultId: String?,
    val lastError: BackendSessionErrorMetadataDto?,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("schema_version", schemaVersion)
          .put("session_id", sessionId)
          .put("status", status.wireValue)
          .put("revision", revision)
          .put("created_at_utc", createdAtUtc.toWireTimestamp())
          .put("updated_at_utc", updatedAtUtc.toWireTimestamp())
          .putNullable("paused_at_utc", pausedAtUtc?.toWireTimestamp())
          .putNullable("cancelled_at_utc", cancelledAtUtc?.toWireTimestamp())
          .putNullable("client_metadata", clientMetadata?.toJsonObject())
          .putNullable("current_analysis_attempt_id", currentAnalysisAttemptId)
          .putNullable("active_analysis_attempt_id", activeAnalysisAttemptId)
          .putNullable("latest_analysis_attempt_id", latestAnalysisAttemptId)
          .putNullable("completed_result_id", completedResultId)
          .putNullable("last_error", lastError?.toJsonObject())

  companion object {
    fun fromJsonObject(json: JSONObject): BackendSessionDto {
      return BackendSessionDto(
          schemaVersion = json.getString("schema_version"),
          sessionId = json.getString("session_id"),
          status = BackendSessionStatus.fromWireValue(json.getString("status")),
          revision = json.getInt("revision"),
          createdAtUtc = json.getString("created_at_utc").parseWireInstant(),
          updatedAtUtc = json.getString("updated_at_utc").parseWireInstant(),
          pausedAtUtc = json.optStringOrNull("paused_at_utc")?.parseWireInstant(),
          cancelledAtUtc = json.optStringOrNull("cancelled_at_utc")?.parseWireInstant(),
          clientMetadata = json.optJSONObject("client_metadata")?.toScalarMap(),
          currentAnalysisAttemptId = json.optStringOrNull("current_analysis_attempt_id"),
          activeAnalysisAttemptId = json.optStringOrNull("active_analysis_attempt_id"),
          latestAnalysisAttemptId = json.optStringOrNull("latest_analysis_attempt_id"),
          completedResultId = json.optStringOrNull("completed_result_id"),
          lastError = json.optJSONObject("last_error")?.let(BackendSessionErrorMetadataDto::fromJsonObject),
      )
    }
  }
}

internal data class BackendEvidenceUploadRequestDto(
    val source: String = "android",
    val clientTimestampUtc: Instant? = null,
    val normalizedText: String? = null,
    val metadata: Map<String, Any?>? = null,
    val filename: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
)

internal data class BackendEvidencePayloadDto(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

internal data class BackendEvidenceDto(
    val schemaVersion: String,
    val evidenceId: String,
    val sessionId: String,
    val evidenceType: BackendEvidenceType,
    val source: String,
    val createdAtUtc: Instant,
    val validationStatus: BackendEvidenceValidationStatus,
    val sequenceNumber: Int,
    val clientTimestampUtc: Instant?,
    val filename: String,
    val mimeType: String,
    val storageRef: String,
    val contentHash: String?,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Double?,
    val normalizedText: String?,
    val metadata: Map<String, Any?>?,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("schema_version", schemaVersion)
          .put("evidence_id", evidenceId)
          .put("session_id", sessionId)
          .put("evidence_type", evidenceType.wireValue)
          .put("source", source)
          .put("created_at_utc", createdAtUtc.toWireTimestamp())
          .put("validation_status", validationStatus.wireValue)
          .put("sequence_number", sequenceNumber)
          .putNullable("client_timestamp_utc", clientTimestampUtc?.toWireTimestamp())
          .put("filename", filename)
          .put("mime_type", mimeType)
          .put("storage_ref", storageRef)
          .putNullable("content_hash", contentHash)
          .putNullable("width", width)
          .putNullable("height", height)
          .putNullable("duration_seconds", durationSeconds)
          .putNullable("normalized_text", normalizedText)
          .putNullable("metadata", metadata?.toJsonObject())

  companion object {
    fun fromJsonObject(json: JSONObject): BackendEvidenceDto {
      return BackendEvidenceDto(
          schemaVersion = json.getString("schema_version"),
          evidenceId = json.getString("evidence_id"),
          sessionId = json.getString("session_id"),
          evidenceType = BackendEvidenceType.fromWireValue(json.getString("evidence_type")),
          source = json.getString("source"),
          createdAtUtc = json.getString("created_at_utc").parseWireInstant(),
          validationStatus = BackendEvidenceValidationStatus.fromWireValue(json.getString("validation_status")),
          sequenceNumber = json.getInt("sequence_number"),
          clientTimestampUtc = json.optStringOrNull("client_timestamp_utc")?.parseWireInstant(),
          filename = json.getString("filename"),
          mimeType = json.getString("mime_type"),
          storageRef = json.getString("storage_ref"),
          contentHash = json.optStringOrNull("content_hash"),
          width = json.optIntOrNull("width"),
          height = json.optIntOrNull("height"),
          durationSeconds = json.optDoubleOrNull("duration_seconds"),
          normalizedText = json.optStringOrNull("normalized_text"),
          metadata = json.optJSONObject("metadata")?.toScalarMap(),
      )
    }
  }
}

internal data class BackendPollingResponseDto(
    val sessionId: String,
    val investigationId: String?,
    val status: BackendSessionStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val imageCount: Int,
    val explanationPresent: Boolean,
    val retryable: Boolean,
    val error: BackendSessionPollingErrorDto?,
    val compactResult: BackendCompactResultDto?,
    val resultAvailable: Boolean,
    val pollAfterMs: Int,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("session_id", sessionId)
          .putNullable("investigation_id", investigationId)
          .put("status", status.wireValue)
          .put("created_at", createdAt.toWireTimestamp())
          .put("updated_at", updatedAt.toWireTimestamp())
          .put("image_count", imageCount)
          .put("explanation_present", explanationPresent)
          .put("retryable", retryable)
          .putNullable("error", error?.toJsonObject())
          .putNullable("compact_result", compactResult?.toJsonObject())
          .put("result_available", resultAvailable)
          .put("poll_after_ms", pollAfterMs)

  companion object {
    fun fromJsonObject(json: JSONObject): BackendPollingResponseDto {
      return BackendPollingResponseDto(
          sessionId = json.getString("session_id"),
          investigationId = json.optStringOrNull("investigation_id"),
          status = BackendSessionStatus.fromWireValue(json.getString("status")),
          createdAt = json.getString("created_at").parseWireInstant(),
          updatedAt = json.getString("updated_at").parseWireInstant(),
          imageCount = json.getInt("image_count"),
          explanationPresent = json.getBoolean("explanation_present"),
          retryable = json.getBoolean("retryable"),
          error = json.optJSONObject("error")?.let(BackendSessionPollingErrorDto::fromJsonObject),
          compactResult = json.optJSONObject("compact_result")?.let(BackendCompactResultDto::fromJsonObject),
          resultAvailable = json.getBoolean("result_available"),
          pollAfterMs = json.getInt("poll_after_ms"),
      )
    }
  }
}

internal data class BackendSessionAnalyzeRequestDto(
    val expectedRevision: Int? = null,
) {
  fun toJsonObject(): JSONObject =
      JSONObject().apply {
        expectedRevision?.let { put("expected_revision", it) }
      }
}

internal data class BackendSessionAnalyzeResponseDto(
    val sessionId: String,
    val investigationId: String?,
    val status: BackendSessionStatus,
    val accepted: Boolean,
    val resultAvailable: Boolean,
    val compactResult: BackendCompactResultDto?,
    val retryable: Boolean,
    val error: BackendSessionPollingErrorDto?,
    val pollUrl: String,
) {
  fun toJsonObject(): JSONObject =
      JSONObject()
          .put("session_id", sessionId)
          .putNullable("investigation_id", investigationId)
          .put("status", status.wireValue)
          .put("accepted", accepted)
          .put("result_available", resultAvailable)
          .putNullable("compact_result", compactResult?.toJsonObject())
          .put("retryable", retryable)
          .putNullable("error", error?.toJsonObject())
          .put("poll_url", pollUrl)

  companion object {
    fun fromJsonObject(json: JSONObject): BackendSessionAnalyzeResponseDto {
      return BackendSessionAnalyzeResponseDto(
          sessionId = json.getString("session_id"),
          investigationId = json.optStringOrNull("investigation_id"),
          status = BackendSessionStatus.fromWireValue(json.getString("status")),
          accepted = json.getBoolean("accepted"),
          resultAvailable = json.getBoolean("result_available"),
          compactResult = json.optJSONObject("compact_result")?.let(BackendCompactResultDto::fromJsonObject),
          retryable = json.getBoolean("retryable"),
          error = json.optJSONObject("error")?.let(BackendSessionPollingErrorDto::fromJsonObject),
          pollUrl = json.getString("poll_url"),
      )
    }
  }
}

internal data class BackendApiErrorDto(
    val category: String,
    val message: String,
)

internal fun Instant.toWireTimestamp(): String = DateTimeFormatter.ISO_INSTANT.format(this)

internal fun String.parseWireInstant(): Instant = Instant.parse(this)

internal fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
  if (value == null) {
    return this
  }
  return put(name, value)
}

internal fun Map<String, Any?>.toJsonObject(): JSONObject {
  val result = JSONObject()
  for ((key, value) in entries) {
    result.put(key, value.toJsonValue())
  }
  return result
}

internal fun JSONObject.toScalarMap(): Map<String, Any?> {
  val result = linkedMapOf<String, Any?>()
  val keys = keys()
  while (keys.hasNext()) {
    val key = keys.next()
    result[key] = opt(key).fromJsonValue()
  }
  return result
}

private fun Any?.toJsonValue(): Any? {
  return when (this) {
    null -> JSONObject.NULL
    is String,
    is Boolean,
    is Int,
    is Long,
    is Double,
    is Float,
    is Short,
    is Byte -> this
    is Map<*, *> -> {
      val result = JSONObject()
      for ((key, value) in entries) {
        result.put(key.toString(), value.toJsonValue())
      }
      result
    }
    is List<*> -> JSONArray(this.map { it.toJsonValue() })
    else -> this.toString()
  }
}

private fun Any?.fromJsonValue(): Any? {
  return when (this) {
    JSONObject.NULL -> null
    is JSONObject -> toScalarMap()
    is JSONArray -> List(length()) { index -> opt(index).fromJsonValue() }
    else -> this
  }
}

internal fun JSONObject.optStringOrNull(name: String): String? {
  if (!has(name) || isNull(name)) {
    return null
  }
  val value = optString(name, "")
  return value.ifBlank { null }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
  if (!has(name) || isNull(name)) {
    return null
  }
  return try {
    getInt(name)
  } catch (_: Exception) {
    null
  }
}

internal fun JSONObject.optDoubleOrNull(name: String): Double? {
  if (!has(name) || isNull(name)) {
    return null
  }
  return try {
    getDouble(name)
  } catch (_: Exception) {
    null
  }
}
