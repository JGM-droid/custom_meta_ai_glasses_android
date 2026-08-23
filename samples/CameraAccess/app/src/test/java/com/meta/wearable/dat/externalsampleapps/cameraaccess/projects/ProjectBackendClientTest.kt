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
  fun createProjectSendsCanonicalRequestBodyWithOptionalCheckpointFields() {
    val recorder = RequestRecorder(
        createdProjectResponseBody(
            id = "44444444-4444-4444-4444-444444444444",
            name = "Garage Door Sensor Test",
            status = "active",
        ),
        code = 201,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    runBlockingTest {
      api.createProject(
          NewProjectRequest(
              name = "Garage Door Sensor Test",
              goal = "Determine why the garage door sensor intermittently reports an obstruction.",
              currentObjective = "Inspect sensor alignment and wiring.",
              nextAction = "Capture photos of both safety sensors.",
          ),
      )
    }

    assertEquals("POST", recorder.connection.requestMethod)
    assertEquals("/projects", recorder.connection.url.path)
    assertEquals(
        "application/json; charset=utf-8",
        recorder.connection.customHeaders["Content-Type"],
    )

    val sentBody = org.json.JSONObject(recorder.connection.output.toString(StandardCharsets.UTF_8.name()))
    assertEquals("Garage Door Sensor Test", sentBody.getString("name"))
    assertEquals(
        "Determine why the garage door sensor intermittently reports an obstruction.",
        sentBody.getString("goal"),
    )
    // No extra top-level fields - matches the backend's ProjectCreateRequest exactly
    // (extra="forbid"): name, goal, checkpoint only (status is omitted, defaults server-side).
    assertEquals(setOf("name", "goal", "checkpoint"), sentBody.keyNames())

    val checkpoint = sentBody.getJSONObject("checkpoint")
    assertEquals("Inspect sensor alignment and wiring.", checkpoint.getString("current_objective"))
    assertEquals("Capture photos of both safety sensors.", checkpoint.getString("next_action"))
    assertEquals(setOf("current_objective", "next_action"), checkpoint.keyNames())
  }

  @Test
  fun createProjectOmitsCheckpointKeyWhenNoOptionalFieldsProvided() {
    val recorder = RequestRecorder(
        createdProjectResponseBody(id = "55555555-5555-5555-5555-555555555555", name = "Minimal Project", status = "active"),
        code = 201,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    runBlockingTest {
      api.createProject(NewProjectRequest(name = "Minimal Project", goal = "Just a goal.", currentObjective = null, nextAction = null))
    }

    val sentBody = org.json.JSONObject(recorder.connection.output.toString(StandardCharsets.UTF_8.name()))
    assertEquals(setOf("name", "goal"), sentBody.keyNames())
  }

  @Test
  fun createProjectParsesResponseAndPreservesCanonicalProjectId() {
    val recorder = RequestRecorder(
        createdProjectResponseBody(id = "66666666-6666-6666-6666-666666666666", name = "Garage Door Sensor Test", status = "active"),
        code = 201,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    val created = runBlockingTest {
      api.createProject(NewProjectRequest(name = "Garage Door Sensor Test", goal = "Goal.", currentObjective = null, nextAction = null))
    }

    // The exact backend-assigned project_id must come back unchanged - never a client-generated
    // id, never the request's own data reflected back without the server's canonical identity.
    assertEquals("66666666-6666-6666-6666-666666666666", created.projectId)
    assertEquals("Garage Door Sensor Test", created.name)
    assertEquals("active", created.status)
  }

  @Test
  fun createProjectValidationErrorThrowsWithBackendCategory() {
    val recorder = RequestRecorder(
        body = """{"detail":{"category":"validation_error","message":"name: field required"}}""",
        code = 422,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    try {
      runBlockingTest { api.createProject(NewProjectRequest(name = "", goal = "Goal.")) }
      fail("Expected ProjectApiException")
    } catch (exc: ProjectApiException) {
      assertEquals(422, exc.code)
      assertEquals("validation_error", exc.category)
    }
  }

  @Test
  fun createProjectNetworkFailurePropagatesRatherThanCrashing() {
    val api = HttpUrlProjectApi(
        baseUrl = "http://10.0.2.2:8001",
        connectionFactory = { throw java.io.IOException("Unable to resolve host") },
    )

    try {
      runBlockingTest { api.createProject(NewProjectRequest(name = "X", goal = "Y")) }
      fail("Expected an IOException to propagate")
    } catch (exc: java.io.IOException) {
      assertEquals("Unable to resolve host", exc.message)
    }
  }

  @Test
  fun getActiveProjectParsesBackendResponseWhenSet() {
    val recorder = RequestRecorder(
        createdProjectResponseBody(id = "77777777-7777-7777-7777-777777777777", name = "Upstairs AC Repair", status = "active"),
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    val active = runBlockingTest { api.getActiveProject() }

    assertEquals("GET", recorder.connection.requestMethod)
    assertEquals("/projects/active", recorder.connection.url.path)
    assertEquals("77777777-7777-7777-7777-777777777777", active?.projectId)
    assertEquals("Upstairs AC Repair", active?.name)
  }

  @Test
  fun getActiveProjectReturnsNullWhenNoneIsSetRatherThanThrowing() {
    val recorder = RequestRecorder(
        body = """{"detail":{"category":"active_project_not_set","message":"No active project is currently selected."}}""",
        code = 404,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    // No Active Project is a normal state, not an error - callers must not have to catch an
    // exception just to render "nothing is Active".
    val active = runBlockingTest { api.getActiveProject() }

    assertNull(active)
  }

  @Test
  fun getActiveProjectPropagatesOtherBackendErrors() {
    val recorder = RequestRecorder(
        body = """{"detail":{"category":"project_storage_error","message":"Project storage is unavailable."}}""",
        code = 500,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    try {
      runBlockingTest { api.getActiveProject() }
      fail("Expected ProjectApiException for a non-'not set' failure")
    } catch (exc: ProjectApiException) {
      assertEquals(500, exc.code)
      assertEquals("project_storage_error", exc.category)
    }
  }

  @Test
  fun setActiveProjectSendsPutToCanonicalProjectId() {
    val recorder = RequestRecorder(
        createdProjectResponseBody(id = "88888888-8888-8888-8888-888888888888", name = "Lanyard Construction Website", status = "active"),
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    val activated = runBlockingTest { api.setActiveProject("88888888-8888-8888-8888-888888888888") }

    assertEquals("PUT", recorder.connection.requestMethod)
    assertEquals("/projects/active/88888888-8888-8888-8888-888888888888", recorder.connection.url.path)
    assertEquals("88888888-8888-8888-8888-888888888888", activated.projectId)
  }

  @Test
  fun setActiveProjectWithUnknownIdThrowsProjectNotFound() {
    val recorder = RequestRecorder(
        body = """{"detail":{"category":"project_not_found","message":"Project does not exist."}}""",
        code = 404,
    )
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    try {
      runBlockingTest { api.setActiveProject("00000000-0000-0000-0000-000000000000") }
      fail("Expected ProjectApiException")
    } catch (exc: ProjectApiException) {
      assertEquals(404, exc.code)
      assertEquals("project_not_found", exc.category)
    }
  }

  @Test
  fun clearActiveProjectSendsDeleteAndSucceedsOnEmptyBody() {
    val recorder = RequestRecorder(body = "", code = 204)
    val api = HttpUrlProjectApi(baseUrl = "http://10.0.2.2:8001", connectionFactory = recorder::newConnection)

    // Must not throw despite the empty 204 body - unlike the JSON-returning endpoints, no parse
    // is attempted here.
    runBlockingTest { api.clearActiveProject() }

    assertEquals("DELETE", recorder.connection.requestMethod)
    assertEquals("/projects/active", recorder.connection.url.path)
  }

  @Test
  fun activeProjectNetworkFailurePropagatesRatherThanCrashing() {
    val api = HttpUrlProjectApi(
        baseUrl = "http://10.0.2.2:8001",
        connectionFactory = { throw java.io.IOException("Unable to resolve host") },
    )

    try {
      runBlockingTest { api.setActiveProject("11111111-1111-1111-1111-111111111111") }
      fail("Expected an IOException to propagate")
    } catch (exc: java.io.IOException) {
      assertEquals("Unable to resolve host", exc.message)
    }
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
  val output = ByteArrayOutputStream()
  val customHeaders = linkedMapOf<String, String>()

  override fun setRequestProperty(key: String?, value: String?) {
    if (key != null && value != null) {
      customHeaders[key] = value
    }
  }

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

private fun createdProjectResponseBody(id: String, name: String, status: String): String =
    """
    {
      "schema_version":"1.0",
      "project_id":"$id",
      "name":"$name",
      "goal":"Determine why the garage door sensor intermittently reports an obstruction.",
      "status":"$status",
      "checkpoint":{
        "current_objective":"Inspect sensor alignment and wiring.",
        "completed_summary":null,
        "discoveries_summary":null,
        "current_work":null,
        "stopped_at":null,
        "blockers":null,
        "next_action":"Capture photos of both safety sensors."
      },
      "revision":0,
      "created_at_utc":"2026-08-22T22:00:00Z",
      "updated_at_utc":"2026-08-22T22:00:00Z"
    }
    """.trimIndent()

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

private fun org.json.JSONObject.keyNames(): Set<String> {
  val result = mutableSetOf<String>()
  val iterator = keys()
  while (iterator.hasNext()) result.add(iterator.next())
  return result
}
