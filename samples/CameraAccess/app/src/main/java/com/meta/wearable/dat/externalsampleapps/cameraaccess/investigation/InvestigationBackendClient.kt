package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

internal interface InvestigationSessionApi {
  suspend fun createSession(request: BackendSessionCreateRequestDto): BackendSessionDto

  suspend fun getSession(sessionId: String): BackendSessionDto

  suspend fun pollSession(sessionId: String): BackendPollingResponseDto

  suspend fun analyzeSession(
      sessionId: String,
      request: BackendSessionAnalyzeRequestDto,
  ): BackendSessionAnalyzeResponseDto

  suspend fun pauseSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto

  suspend fun resumeSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto

  suspend fun cancelSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto

  suspend fun uploadImageEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto

  suspend fun uploadAudioEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto
}

internal class HttpUrlInvestigationSessionApi(
    private val baseUrl: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : InvestigationSessionApi {
  override suspend fun createSession(request: BackendSessionCreateRequestDto): BackendSessionDto {
    val response = executeJson(
        method = "POST",
        path = "/investigation-sessions",
        requestBody = request.toJsonObject().toString(),
    )
    if (!response.has("session_id") || !response.has("status")) {
      val backendMessage =
          response.optJSONObject("Exception")?.optString("Message")
              ?: response.optString("message")
              ?: "Unexpected create-session payload."
      throw BackendApiException(
          code = 502,
          error =
              BackendApiErrorDto(
                  category = "backend_contract_mismatch",
                  message =
                      "Backend response does not match investigation session contract. $backendMessage",
              ),
      )
    }
    return BackendSessionDto.fromJsonObject(response)
  }

  override suspend fun getSession(sessionId: String): BackendSessionDto {
    val response = executeJson(method = "GET", path = "/investigation-sessions/${normalizeId(sessionId)}")
    return BackendSessionDto.fromJsonObject(response)
  }

  override suspend fun pollSession(sessionId: String): BackendPollingResponseDto {
    val response = executeJson(method = "GET", path = "/investigation-sessions/${normalizeId(sessionId)}/poll")
    return BackendPollingResponseDto.fromJsonObject(response)
  }

  override suspend fun analyzeSession(
      sessionId: String,
      request: BackendSessionAnalyzeRequestDto,
  ): BackendSessionAnalyzeResponseDto {
    val response = executeJson(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/analyze",
        requestBody = request.toJsonObject().toString(),
    )
    return BackendSessionAnalyzeResponseDto.fromJsonObject(response)
  }

  override suspend fun pauseSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    val response = executeJson(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/pause",
        requestBody = request.toJsonObject().toString(),
    )
    return BackendSessionDto.fromJsonObject(response)
  }

  override suspend fun resumeSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    val response = executeJson(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/resume",
        requestBody = request.toJsonObject().toString(),
    )
    return BackendSessionDto.fromJsonObject(response)
  }

  override suspend fun cancelSession(sessionId: String, request: BackendSessionMutationRequestDto): BackendSessionDto {
    val response = executeJson(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/cancel",
        requestBody = request.toJsonObject().toString(),
    )
    return BackendSessionDto.fromJsonObject(response)
  }

  override suspend fun uploadImageEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto {
    val response = executeMultipart(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/evidence/image",
        fileFieldName = "file",
        payload = payload,
        fields = request.toMultipartFields(),
    )
    return BackendEvidenceDto.fromJsonObject(response)
  }

  override suspend fun uploadAudioEvidence(
      sessionId: String,
      request: BackendEvidenceUploadRequestDto,
      payload: BackendEvidencePayloadDto,
  ): BackendEvidenceDto {
    val response = executeMultipart(
        method = "POST",
        path = "/investigation-sessions/${normalizeId(sessionId)}/evidence/audio",
        fileFieldName = "file",
        payload = payload,
        fields = request.toMultipartFields(),
    )
    return BackendEvidenceDto.fromJsonObject(response)
  }

  private fun BackendEvidenceUploadRequestDto.toMultipartFields(): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    fields["source"] = source
    clientTimestampUtc?.let { fields["client_timestamp_utc"] = it.toWireTimestamp() }
    normalizedText?.takeIf { it.isNotBlank() }?.let { fields["normalized_text"] = it }
    metadata?.takeIf { it.isNotEmpty() }?.let { fields["metadata"] = it.toJsonObject().toString() }
    filename?.takeIf { it.isNotBlank() }?.let { fields["filename"] = it }
    mimeType?.takeIf { it.isNotBlank() }?.let { fields["mime_type"] = it }
    width?.let { fields["width"] = it.toString() }
    height?.let { fields["height"] = it.toString() }
    durationSeconds?.let { fields["duration_seconds"] = it.toString() }
    return fields
  }

  private fun executeJson(
      method: String,
      path: String,
      requestBody: String? = null,
  ): JSONObject {
    val connection = openConnection(path, method)
    if (requestBody != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      connection.outputStream.use { output ->
        output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
        output.flush()
      }
    }

    return connection.useJsonResponse()
  }

  private fun executeMultipart(
      method: String,
      path: String,
      fileFieldName: String,
      payload: BackendEvidencePayloadDto,
      fields: Map<String, String>,
  ): JSONObject {
    val boundary = "----InvestigationBoundary${UUID.randomUUID().toString().replace("-", "")}" 
    val connection = openConnection(path, method)
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    connection.outputStream.use { rawOutput ->
      BufferedOutputStream(rawOutput).use { output ->
        for ((name, value) in fields) {
          writeMultipartTextPart(output, boundary, name, value)
        }
        writeMultipartFilePart(
            output = output,
            boundary = boundary,
            fieldName = fileFieldName,
            filename = payload.filename,
            contentType = payload.mimeType,
            bytes = payload.bytes,
        )
        output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
      }
    }
    return connection.useJsonResponse()
  }

  private fun openConnection(path: String, method: String): HttpURLConnection {
    val url = URL("${baseUrl.trimEnd('/')}${path}")
    return connectionFactory(url).apply {
      requestMethod = method
      connectTimeout = 15_000
      readTimeout = 30_000
      doInput = true
      useCaches = false
    }
  }

  private fun writeMultipartTextPart(
      output: BufferedOutputStream,
      boundary: String,
      fieldName: String,
      value: String,
  ) {
    output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write("Content-Disposition: form-data; name=\"$fieldName\"\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write("Content-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write(value.toByteArray(StandardCharsets.UTF_8))
    output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
  }

  private fun writeMultipartFilePart(
      output: BufferedOutputStream,
      boundary: String,
      fieldName: String,
      filename: String,
      contentType: String,
      bytes: ByteArray,
  ) {
    output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write(
        "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\n".toByteArray(StandardCharsets.UTF_8),
    )
    output.write("Content-Type: $contentType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    output.write(bytes)
    output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
  }

  private fun HttpURLConnection.useJsonResponse(): JSONObject {
    val code = responseCode
    val body = (if (code in 200..299) inputStream else errorStream)?.use { input ->
      input.readBytes().toString(StandardCharsets.UTF_8)
    }.orEmpty()

    if (code !in 200..299) {
      throw BackendApiException(code = code, error = parseApiError(body))
    }

    if (body.isBlank()) {
      throw BackendSerializationException("Backend returned an empty body.")
    }

    return try {
      JSONObject(body)
    } catch (exc: Exception) {
      throw BackendSerializationException("Backend returned invalid JSON.", exc)
    }
  }

  private fun parseApiError(body: String): BackendApiErrorDto {
    if (body.isBlank()) {
      return BackendApiErrorDto(category = "unknown_error", message = "Backend request failed.")
    }

    return try {
      val json = JSONObject(body)
      val detail = json.opt("detail")
      when (detail) {
        is JSONObject -> {
          BackendApiErrorDto(
              category = detail.optString("category", "unknown_error"),
              message = detail.optString("message", "Backend request failed."),
          )
        }
        is String -> BackendApiErrorDto(category = "backend_error", message = detail)
        else -> BackendApiErrorDto(category = "unknown_error", message = json.optString("message", "Backend request failed."))
      }
    } catch (_: Exception) {
      BackendApiErrorDto(category = "unknown_error", message = body)
    }
  }

  private fun normalizeId(value: String): String = value.trim()
}

internal class BackendApiException(
    val code: Int,
    val error: BackendApiErrorDto,
) : IOException("HTTP $code ${error.category}: ${error.message}")

internal class BackendSerializationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
