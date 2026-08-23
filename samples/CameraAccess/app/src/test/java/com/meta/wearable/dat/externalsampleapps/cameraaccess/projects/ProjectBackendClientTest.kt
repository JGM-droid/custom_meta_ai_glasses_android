package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectBackendClientTest {

  @Test
  fun listProjectsParsesCanonicalBackendResponse() {
    val recorder = RequestRecorder(listProjectsResponseBody())
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    val projects = runBlockingTest { api.listProjects() }

    assertEquals("GET", recorder.connection.requestMethod)
    assertEquals("/projects", recorder.connection.url.path)
    assertEquals(2, projects.size)
    assertEquals("11111111-1111-1111-1111-111111111111", projects[0].projectId)
    assertEquals("Upstairs AC Repair", projects[0].name)
    assertEquals("active", projects[0].status)
    assertEquals("22222222-2222-2222-2222-222222222222", projects[1].projectId)
    assertEquals("Custom Meta AI Glasses", projects[1].name)
  }

  @Test
  fun twoDifferentProjectsProduceDistinctOverviews() {
    val acRecorder = RequestRecorder(getProjectResponseBody(id = "11111111-1111-1111-1111-111111111111", currentWork = "Capacitor appears swollen.", nextAction = "Identify capacitor rating."))
    val acApi = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = { url ->
      if (url.path.endsWith("/activities")) RequestRecorder(emptyActivitiesResponseBody()).newConnection(url)
      else acRecorder.newConnection(url)
    })
    val acOverview = runBlockingTest { acApi.getProjectOverview("11111111-1111-1111-1111-111111111111") }

    val glassesRecorder = RequestRecorder(getProjectResponseBody(id = "22222222-2222-2222-2222-222222222222", currentWork = "Navigation shell wired up.", nextAction = "Connect Project Overview to the API."))
    val glassesApi = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = { url ->
      if (url.path.endsWith("/activities")) RequestRecorder(emptyActivitiesResponseBody()).newConnection(url)
      else glassesRecorder.newConnection(url)
    })
    val glassesOverview = runBlockingTest { glassesApi.getProjectOverview("22222222-2222-2222-2222-222222222222") }

    assertEquals("Capacitor appears swollen.", acOverview.checkpoint.whereWeLeftOff)
    assertEquals("Navigation shell wired up.", glassesOverview.checkpoint.whereWeLeftOff)
    assertTrue(acOverview.checkpoint.whereWeLeftOff != glassesOverview.checkpoint.whereWeLeftOff)
    assertTrue(acOverview.project.projectId != glassesOverview.project.projectId)
  }

  @Test
  fun getProjectOverviewMapsNullCheckpointFieldsToNull() {
    val recorder = RequestRecorder(getProjectResponseBody(id = "11111111-1111-1111-1111-111111111111", currentWork = null, nextAction = null))
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = { url ->
      if (url.path.endsWith("/activities")) RequestRecorder(emptyActivitiesResponseBody()).newConnection(url)
      else recorder.newConnection(url)
    })

    val overview = runBlockingTest { api.getProjectOverview("11111111-1111-1111-1111-111111111111") }

    assertNull(overview.checkpoint.whereWeLeftOff)
    assertNull(overview.checkpoint.nextAction)
  }

  @Test
  fun recentActivityIsCappedAtFiveNewestFirst() {
    val projectRecorder = RequestRecorder(getProjectResponseBody(id = "11111111-1111-1111-1111-111111111111", currentWork = "x", nextAction = "y"))
    val activitiesRecorder = RequestRecorder(activitiesResponseBody(count = 7))
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = { url ->
      if (url.path.endsWith("/activities")) activitiesRecorder.newConnection(url) else projectRecorder.newConnection(url)
    })

    val overview = runBlockingTest { api.getProjectOverview("11111111-1111-1111-1111-111111111111") }

    // Backend returns oldest-first (1..7); the 5 most recent are 3..7, newest first.
    assertEquals(5, overview.recentActivity.size)
    assertEquals("Activity 7", overview.recentActivity[0].summary)
    assertEquals("Activity 3", overview.recentActivity[4].summary)
  }

  @Test
  fun notFoundResponseThrowsWithBackendErrorCategory() {
    val recorder = RequestRecorder(
        body = """{"detail":{"category":"project_not_found","message":"Project does not exist."}}""",
        code = 404,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    try {
      runBlockingTest { api.listProjects() }
      fail("Expected ProjectApiException")
    } catch (exc: ProjectApiException) {
      assertEquals(404, exc.code)
      assertEquals("project_not_found", exc.category)
      assertEquals("Project does not exist.", exc.message)
    }
  }
}

private class RequestRecorder(private val body: String, private val code: Int = 200) {
  lateinit var connection: RecordingConnection

  fun newConnection(url: URL): HttpURLConnection {
    connection = RecordingConnection(url, body, code)
    return connection
  }
}

private class RecordingConnection(url: URL, private val body: String, private val code: Int) : HttpURLConnection(url) {
  private val output = ByteArrayOutputStream()

  override fun setRequestProperty(key: String?, value: String?) {}

  override fun getOutputStream(): ByteArrayOutputStream = output

  override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))

  override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))

  override fun getResponseCode(): Int = code

  override fun disconnect() {}

  override fun usingProxy(): Boolean = false

  override fun connect() {}
}

private fun listProjectsResponseBody(): String =
    """
    [
      {"project_id":"11111111-1111-1111-1111-111111111111","name":"Upstairs AC Repair","status":"active","updated_at_utc":"2026-08-20T10:00:00Z","current_objective":null,"next_action":"Identify capacitor rating."},
      {"project_id":"22222222-2222-2222-2222-222222222222","name":"Custom Meta AI Glasses","status":"active","updated_at_utc":"2026-08-21T10:00:00Z","current_objective":null,"next_action":null}
    ]
    """.trimIndent()

private fun getProjectResponseBody(id: String, currentWork: String?, nextAction: String?): String {
  val currentWorkJson = if (currentWork == null) "null" else "\"$currentWork\""
  val nextActionJson = if (nextAction == null) "null" else "\"$nextAction\""
  return """
      {
        "schema_version":"1.0",
        "project_id":"$id",
        "name":"Test Project",
        "goal":"Test goal",
        "status":"active",
        "checkpoint":{
          "current_objective":null,
          "completed_summary":null,
          "discoveries_summary":null,
          "current_work":$currentWorkJson,
          "stopped_at":null,
          "blockers":null,
          "next_action":$nextActionJson
        },
        "revision":1,
        "created_at_utc":"2026-08-20T10:00:00Z",
        "updated_at_utc":"2026-08-20T10:00:00Z"
      }
      """.trimIndent()
}

private fun emptyActivitiesResponseBody(): String = "[]"

private fun activitiesResponseBody(count: Int): String {
  val entries = (1..count).joinToString(",") { index ->
    """
    {
      "schema_version":"1.0",
      "activity_id":"33333333-3333-3333-3333-33333333333$index",
      "project_id":"11111111-1111-1111-1111-111111111111",
      "activity_type":"note",
      "source_type":"user",
      "confirmation_status":"reported",
      "summary":"Activity $index",
      "details":null,
      "occurred_at_utc":"2026-08-2${index}T10:00:00Z",
      "created_at_utc":"2026-08-2${index}T10:00:00Z",
      "metadata":null
    }
    """.trimIndent()
  }
  return "[$entries]"
}

private fun <T> runBlockingTest(block: suspend () -> T): T {
  return kotlinx.coroutines.runBlocking { block() }
}
