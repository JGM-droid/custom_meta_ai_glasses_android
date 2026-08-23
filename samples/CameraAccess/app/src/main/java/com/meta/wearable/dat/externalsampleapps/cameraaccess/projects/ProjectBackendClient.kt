/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectBackendClient - real backend Project Memory API access
//
// Mirrors investigation/InvestigationBackendClient.kt's HttpURLConnection conventions (same
// request/response/error-parsing shape, same backend error contract:
// {"detail": {"category": ..., "message": ...}}) applied to the read-only Project endpoints this
// slice needs:
//   GET /projects                         -> list[ProjectSummary]
//   GET /projects/{project_id}             -> Project (identity + checkpoint)
//   GET /projects/{project_id}/activities  -> list[ProjectActivity]
//
// This is a standalone client, not a refactor of the Investigation client - the Investigation
// networking code is left untouched. Read-only: no POST/PATCH/PUT/DELETE calls exist here, so
// this client can never mutate backend Project Memory.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface ProjectApi {
  suspend fun listProjects(): List<ProjectSummary>

  suspend fun getProjectOverview(projectId: String): ProjectOverview
}

internal class ProjectApiException(val code: Int, val category: String, override val message: String) :
    IOException(message)

internal class HttpUrlProjectApi(
    private val baseUrl: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : ProjectApi {

  override suspend fun listProjects(): List<ProjectSummary> {
    val response = executeJsonArray(path = "/projects")
    return (0 until response.length()).map { index ->
      response.getJSONObject(index).toProjectSummary()
    }
  }

  override suspend fun getProjectOverview(projectId: String): ProjectOverview {
    val project = executeJsonObject(path = "/projects/${normalizeId(projectId)}")
    val summary = project.toProjectSummary()

    val checkpointJson = project.optJSONObject("checkpoint")
    val checkpoint = ProjectCheckpoint(
        whereWeLeftOff = checkpointJson?.optNullableString("current_work"),
        nextAction = checkpointJson?.optNullableString("next_action"),
    )

    val activitiesJson = executeJsonArray(path = "/projects/${normalizeId(projectId)}/activities")
    // Backend returns activities oldest-first (occurred_at_utc ascending); show the most recent
    // ones, newest first.
    val recentActivity = (0 until activitiesJson.length())
        .map { index -> activitiesJson.getJSONObject(index).getString("summary") }
        .takeLast(5)
        .asReversed()
        .map { summaryText -> ProjectActivityEntry(summary = summaryText) }

    return ProjectOverview(project = summary, checkpoint = checkpoint, recentActivity = recentActivity)
  }

  private fun JSONObject.toProjectSummary(): ProjectSummary =
      ProjectSummary(
          projectId = getString("project_id"),
          name = getString("name"),
          status = getString("status"),
      )

  private fun JSONObject.optNullableString(key: String): String? {
    if (isNull(key) || !has(key)) return null
    val value = optString(key, "")
    return value.ifBlank { null }
  }

  private fun executeJsonObject(path: String): JSONObject {
    val connection = openConnection(path)
    return connection.useJsonResponse { body -> JSONObject(body) }
  }

  private fun executeJsonArray(path: String): JSONArray {
    val connection = openConnection(path)
    return connection.useJsonResponse { body -> JSONArray(body) }
  }

  private fun openConnection(path: String): HttpURLConnection {
    val url = URL("${baseUrl.trimEnd('/')}$path")
    return connectionFactory(url).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 15_000
      doInput = true
      useCaches = false
    }
  }

  private fun <T> HttpURLConnection.useJsonResponse(parse: (String) -> T): T {
    val code = responseCode
    val body = (if (code in 200..299) inputStream else errorStream)?.use { input ->
      input.readBytes().toString(StandardCharsets.UTF_8)
    }.orEmpty()

    if (code !in 200..299) {
      val error = parseApiError(body)
      throw ProjectApiException(code = code, category = error.first, message = error.second)
    }

    if (body.isBlank()) {
      throw ProjectApiException(code = code, category = "empty_response", message = "Backend returned an empty body.")
    }

    return try {
      parse(body)
    } catch (exc: Exception) {
      throw ProjectApiException(code = code, category = "invalid_response", message = "Backend returned invalid JSON.")
    }
  }

  /** Returns (category, message), matching the backend's {"detail": {"category", "message"}}. */
  private fun parseApiError(body: String): Pair<String, String> {
    if (body.isBlank()) return "unknown_error" to "Backend request failed."
    return try {
      val json = JSONObject(body)
      when (val detail = json.opt("detail")) {
        is JSONObject -> detail.optString("category", "unknown_error") to detail.optString("message", "Backend request failed.")
        is String -> "backend_error" to detail
        else -> "unknown_error" to json.optString("message", "Backend request failed.")
      }
    } catch (_: Exception) {
      "unknown_error" to body
    }
  }

  private fun normalizeId(value: String): String = value.trim()
}
