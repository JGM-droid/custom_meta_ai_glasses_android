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
// {"detail": {"category": ..., "message": ...}}) applied to the Project endpoints this app uses:
//   GET    /projects                         -> list[ProjectSummary]
//   GET    /projects/{project_id}             -> Project (identity + checkpoint)
//   GET    /projects/{project_id}/activities  -> list[ProjectActivity]
//   POST   /projects                          -> Project (create)
//   GET    /projects/active                   -> Project, or 404 {category: active_project_not_set}
//   PUT    /projects/active/{project_id}      -> Project (that project becomes Active)
//   DELETE /projects/active                   -> 204 (idempotent; no active project is not an error)
//   POST   /projects/{project_id}/ask         -> ProjectGroundedAnswerResponse (read-only Q&A)
//
// This is a standalone client, not a refactor of the Investigation client - the Investigation
// networking code is left untouched. createProject sends exactly the backend's
// ProjectCreateRequest shape (name, goal, optional checkpoint.current_objective/next_action) -
// no invented fields, no second creation model. The Active Project endpoints are the backend's
// existing single global pointer (see projects/project_store.py ActiveProjectPointer) - this
// client neither invents new endpoints nor keeps a second, Android-owned notion of which Project
// is Active. askProject sends exactly the backend's ProjectAskRequest shape (question only) to
// the existing Project Q&A route (see projects/project_qa.py ProjectQuestionAnsweringService) -
// this client performs no retrieval/reasoning of its own; the backend's context retriever and AI
// provider do all of that. That route is read-only (confirmed by inspection - it never calls into
// PROJECT_STORE/PROJECT_ACTIVITY_STORE), so this client adds no separate mutation around it.

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

  suspend fun createProject(request: NewProjectRequest): ProjectSummary

  /** Null means no Active Project is currently set - a normal state, not an error. */
  suspend fun getActiveProject(): ProjectSummary?

  /** Makes this the backend's one Active Project; the previous Active Project loses that status. */
  suspend fun setActiveProject(projectId: String): ProjectSummary

  /** Clears the Active Project pointer. Idempotent - succeeds even if nothing was Active. */
  suspend fun clearActiveProject()

  /** Read-only: asks THIS project's real backend Q&A route a question. Never mutates Project state. */
  suspend fun askProject(projectId: String, question: String): ProjectAskAnswer

  suspend fun applyCheckpointProposal(projectId: String, proposalId: String)

  suspend fun rejectCheckpointProposal(projectId: String, proposalId: String)
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
    val normalizedProjectId = normalizeId(projectId)
    val project = executeJsonObject(path = "/projects/$normalizedProjectId")
    val summary = project.toProjectSummary()

    val checkpointJson = project.optJSONObject("checkpoint")
    val checkpoint = ProjectCheckpoint(
        whereWeLeftOff = checkpointJson?.optNullableString("current_work"),
        nextAction = checkpointJson?.optNullableString("next_action"),
    )

    val activitiesJson = executeJsonArray(path = "/projects/$normalizedProjectId/activities")
    // Backend returns activities oldest-first (occurred_at_utc ascending); show the most recent
    // ones, newest first.
    val recentActivity = (0 until activitiesJson.length())
        .map { index -> activitiesJson.getJSONObject(index).getString("summary") }
        .takeLast(5)
        .asReversed()
        .map { summaryText -> ProjectActivityEntry(summary = summaryText) }

    var investigation: SavedInvestigationReview? = null
    var pendingProposals: List<CheckpointProposalReview> = emptyList()
    var investigationLoadError: String? = null
    try {
      val proposals = executeJsonArray(path = "/projects/$normalizedProjectId/checkpoint-proposals")
      pendingProposals = (0 until proposals.length())
          .map { proposals.getJSONObject(it).toCheckpointProposalReview() }
          .filter { it.status == "pending" }
    } catch (exc: ProjectApiException) {
      investigationLoadError = exc.message
    }
    try {
      val sessions = executeJsonArray(path = "/projects/$normalizedProjectId/investigation-sessions")
      val latestSession = (0 until sessions.length())
          .map { sessions.getJSONObject(it) }
          .filter { it.optString("status") == "completed" }
          .maxByOrNull { it.optString("updated_at_utc") }
      if (latestSession != null) {
      val sessionId = latestSession.getString("session_id")
      val trust = executeJsonObject(path = "/projects/$normalizedProjectId/investigation-sessions/$sessionId/trust")
      val evidence = executeJsonArray(path = "/projects/$normalizedProjectId/investigation-sessions/$sessionId/evidence")
      val firstImage = (0 until evidence.length()).map { evidence.getJSONObject(it) }
          .firstOrNull { it.optString("evidence_type") == "image" }
      val imageBytes = firstImage?.let {
        try {
          executeBytes(path = "/projects/$normalizedProjectId/investigation-sessions/$sessionId/evidence/${normalizeId(it.getString("evidence_id"))}/content")
        } catch (_: ProjectApiException) {
          null
        }
      }
      val explanation = (0 until evidence.length()).map { evidence.getJSONObject(it) }
          .firstNotNullOfOrNull { it.optNullableString("normalized_text") }
      val proposalId = trust.optNullableString("checkpoint_proposal_id")
      investigation = SavedInvestigationReview(
          sessionId = sessionId,
          projectId = latestSession.getString("project_id"),
          status = latestSession.getString("status"),
          completedAtUtc = latestSession.getString("updated_at_utc"),
          evidenceCount = evidence.length(),
          explanation = explanation,
          hypothesis = trust.getString("hypothesis"),
          recommendedNextAction = trust.getString("recommended_next_action"),
          trustDecision = trust.optNullableString("user_decision"),
          proposalId = proposalId,
          proposalStatus = trust.optNullableString("checkpoint_proposal_status"),
          followUpSessionId = trust.optNullableString("follow_up_investigation_session_id"),
          retainedImage = imageBytes,
      )
      }
    } catch (exc: ProjectApiException) {
      investigationLoadError = listOfNotNull(investigationLoadError, exc.message).joinToString("; ")
    }

    return ProjectOverview(
        project = summary,
        checkpoint = checkpoint,
        recentActivity = recentActivity,
        latestInvestigation = investigation,
        pendingProposals = pendingProposals,
        investigationLoadError = investigationLoadError,
    )
  }

  override suspend fun createProject(request: NewProjectRequest): ProjectSummary {
    val checkpointJson = JSONObject().apply {
      request.currentObjective?.let { put("current_objective", it) }
      request.nextAction?.let { put("next_action", it) }
    }
    val bodyJson = JSONObject().apply {
      put("name", request.name)
      put("goal", request.goal)
      if (checkpointJson.length() > 0) put("checkpoint", checkpointJson)
    }

    val response = executeJsonObject(path = "/projects", method = "POST", body = bodyJson.toString())
    return response.toProjectSummary()
  }

  override suspend fun getActiveProject(): ProjectSummary? {
    return try {
      executeJsonObject(path = "/projects/active").toProjectSummary()
    } catch (exc: ProjectApiException) {
      if (exc.category == "active_project_not_set") null else throw exc
    }
  }

  override suspend fun setActiveProject(projectId: String): ProjectSummary {
    val response = executeJsonObject(path = "/projects/active/${normalizeId(projectId)}", method = "PUT")
    return response.toProjectSummary()
  }

  override suspend fun clearActiveProject() {
    executeNoContent(path = "/projects/active", method = "DELETE")
  }

  override suspend fun askProject(projectId: String, question: String): ProjectAskAnswer {
    val bodyJson = JSONObject().apply { put("question", question) }
    val response = executeJsonObject(path = "/projects/${normalizeId(projectId)}/ask", method = "POST", body = bodyJson.toString())

    val referencesJson = response.optJSONArray("references")
    val referenceSummaries = if (referencesJson != null) {
      (0 until referencesJson.length()).map { index -> referencesJson.getJSONObject(index).getString("summary") }
    } else {
      emptyList()
    }

    return ProjectAskAnswer(
        answer = response.getString("answer"),
        questionClass = response.getString("question_class"),
        groundingStatus = response.getString("grounding_status"),
        insufficientContext = response.optBoolean("insufficient_context", false),
        uncertaintyNote = response.optNullableString("uncertainty_note"),
        referenceSummaries = referenceSummaries,
        provider = response.optNullableString("provider"),
        providerModel = response.optNullableString("provider_model"),
        modelCallCount = response.optInt("model_call_count", 1),
    )
  }

  override suspend fun applyCheckpointProposal(projectId: String, proposalId: String) {
    executeJsonObject(path = "/projects/${normalizeId(projectId)}/checkpoint-proposals/${normalizeId(proposalId)}/apply", method = "POST")
  }

  override suspend fun rejectCheckpointProposal(projectId: String, proposalId: String) {
    executeJsonObject(path = "/projects/${normalizeId(projectId)}/checkpoint-proposals/${normalizeId(proposalId)}/reject", method = "POST")
  }

  private fun JSONObject.toCheckpointProposalReview(): CheckpointProposalReview {
    val patch = getJSONObject("proposed_checkpoint_patch")
    val fields = patch.keys().asSequence().associateWith { key -> patch.optNullableString(key) }
    return CheckpointProposalReview(
        proposalId = getString("proposal_id"),
        projectId = getString("project_id"),
        status = getString("status"),
        reason = getString("reason"),
        proposedFields = fields,
    )
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

  private fun executeJsonObject(path: String, method: String = "GET", body: String? = null): JSONObject {
    val connection = openConnection(path, method = method, body = body)
    return connection.useJsonResponse { responseBody -> JSONObject(responseBody) }
  }

  private fun executeJsonArray(path: String): JSONArray {
    val connection = openConnection(path)
    return connection.useJsonResponse { responseBody -> JSONArray(responseBody) }
  }

  private fun executeBytes(path: String): ByteArray {
    val connection = openConnection(path)
    val code = connection.responseCode
    if (code !in 200..299) {
      val body = connection.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
      val error = parseApiError(body)
      throw ProjectApiException(code, error.first, error.second)
    }
    return connection.inputStream.use { it.readBytes() }
  }

  /** For endpoints whose success response has no body (e.g. 204), so no JSON parse is attempted. */
  private fun executeNoContent(path: String, method: String) {
    val connection = openConnection(path, method = method)
    val code = connection.responseCode
    if (code !in 200..299) {
      val body = connection.errorStream?.use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
      val error = parseApiError(body)
      throw ProjectApiException(code = code, category = error.first, message = error.second)
    }
  }

  private fun openConnection(path: String, method: String = "GET", body: String? = null): HttpURLConnection {
    val url = URL("${baseUrl.trimEnd('/')}$path")
    return connectionFactory(url).apply {
      requestMethod = method
      connectTimeout = 15_000
      readTimeout = 15_000
      doInput = true
      useCaches = false
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        outputStream.use { output -> output.write(body.toByteArray(StandardCharsets.UTF_8)) }
      }
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
