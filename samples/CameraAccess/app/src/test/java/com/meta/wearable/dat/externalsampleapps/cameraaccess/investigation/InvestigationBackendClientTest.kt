package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationBackendClientTest {
  @Test
  fun createSessionParsesCanonicalBackendResponse() {
    val recorder = RequestRecorder(createSessionResponseBody())
    val api = HttpUrlInvestigationSessionApi(
        baseUrl = "http://10.0.2.2:8001",
        connectionFactory = { url -> recorder.newConnection(url) },
    )

    val session = runBlockingTest { api.createSession(BackendSessionCreateRequestDto(clientMetadata = mapOf("source" to "cameraaccess_debug"))) }

    assertEquals("POST", recorder.connection.requestMethod)
    assertEquals("/investigation-sessions", recorder.connection.url.path)
    assertEquals("123e4567-e89b-12d3-a456-426614174000", session.sessionId)
    assertEquals(BackendSessionStatus.CREATED, session.status)
  }

  @Test
  fun analyzeEndpointPathIsCanonicalSessionAnalyzeRoute() {
    val recorder = RequestRecorder(analyzeResponseBody())
    val api = HttpUrlInvestigationSessionApi(
        baseUrl = "http://10.0.2.2:8001",
        connectionFactory = { url -> recorder.newConnection(url) },
    )

    runBlockingTest {
      api.analyzeSession(
          sessionId = "123e4567-e89b-12d3-a456-426614174000",
          request = BackendSessionAnalyzeRequestDto(expectedRevision = 9),
      )
    }

    assertEquals("POST", recorder.connection.requestMethod)
    assertEquals("/investigation-sessions/123e4567-e89b-12d3-a456-426614174000/analyze", recorder.connection.url.path)
    assertTrue(recorder.connection.url.path != "/investigations/analyze")
  }

  @Test
  fun analyzeRequestBodyUsesExpectedRevisionField() {
    val recorder = RequestRecorder(analyzeResponseBody())
    val api = HttpUrlInvestigationSessionApi(
        baseUrl = "http://10.0.2.2:8001",
        connectionFactory = { url -> recorder.newConnection(url) },
    )

    runBlockingTest {
      api.analyzeSession(
          sessionId = "123e4567-e89b-12d3-a456-426614174000",
          request = BackendSessionAnalyzeRequestDto(expectedRevision = 3),
      )
    }

    val body = recorder.connection.output.toString(StandardCharsets.UTF_8.name())
    assertTrue(body.contains("\"expected_revision\":3"))
    assertEquals("application/json; charset=utf-8", recorder.connection.customHeaders["Content-Type"])
  }
}

private class RequestRecorder(private val responseBody: String) {
  lateinit var connection: RecordingConnection

  fun newConnection(url: URL): HttpURLConnection {
    connection = RecordingConnection(url, responseBody)
    return connection
  }
}

private class RecordingConnection(url: URL, private val responseBody: String) : HttpURLConnection(url) {
  val output = ByteArrayOutputStream()
  val customHeaders = linkedMapOf<String, String>()

  override fun setRequestProperty(key: String?, value: String?) {
    if (key != null && value != null) {
      customHeaders[key] = value
    }
  }

  override fun getOutputStream(): ByteArrayOutputStream = output

  override fun getInputStream(): InputStream {
    return ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
  }

  override fun getResponseCode(): Int = 200

  override fun disconnect() {}

  override fun usingProxy(): Boolean = false

  override fun connect() {}
}

private fun analyzeResponseBody(): String {
  return """
      {
        "session_id":"123e4567-e89b-12d3-a456-426614174000",
        "investigation_id":null,
        "status":"analyzing",
        "accepted":true,
        "result_available":false,
        "compact_result":null,
        "retryable":true,
        "error":null,
        "poll_url":"/investigation-sessions/123e4567-e89b-12d3-a456-426614174000/poll"
      }
      """.trimIndent()
}

private fun createSessionResponseBody(): String {
  return """
      {
        "schema_version":"2.0",
        "session_id":"123e4567-e89b-12d3-a456-426614174000",
        "status":"created",
        "revision":0,
        "created_at_utc":"2026-07-18T10:00:00Z",
        "updated_at_utc":"2026-07-18T10:00:01Z",
        "paused_at_utc":null,
        "cancelled_at_utc":null,
        "client_metadata":{"source":"cameraaccess_debug"},
        "current_analysis_attempt_id":null,
        "active_analysis_attempt_id":null,
        "latest_analysis_attempt_id":null,
        "completed_result_id":null,
        "last_error":null
      }
      """.trimIndent()
}

private fun <T> runBlockingTest(block: suspend () -> T): T {
  return kotlinx.coroutines.runBlocking { block() }
}
