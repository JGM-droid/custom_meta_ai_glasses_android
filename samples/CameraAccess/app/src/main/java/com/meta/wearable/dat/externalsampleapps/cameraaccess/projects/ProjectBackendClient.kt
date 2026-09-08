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

  suspend fun getProjectConversation(projectId: String): ProjectConversation =
      throw UnsupportedOperationException("Project conversation is unavailable.")

  suspend fun sendProjectConversationMessage(
      projectId: String,
      text: String,
      evidenceRefs: List<ConversationEvidenceReference>,
      idempotencyKey: String,
  ): ConversationSendResult = throw UnsupportedOperationException("Project conversation is unavailable.")

  /**
   * Phase 3A closeout: reads the raw image bytes for a [ConversationEvidenceReference] already
   * persisted on a conversation turn - the SAME existing, Project-isolated Evidence content route
   * the legacy Investigation panel already reads from (GET .../investigation-sessions/{session_id}/
   * evidence/{evidence_id}/content - see api.py's get_project_investigation_evidence_content).
   * Conversation only ever stores the reference; this is the one place that resolves it back to
   * bytes, on demand, never persisted by the conversation itself.
   */
  suspend fun getConversationEvidenceImage(
      projectId: String,
      reference: ConversationEvidenceReference,
  ): ByteArray = throw UnsupportedOperationException("Evidence image content is unavailable.")

  suspend fun previewProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressPreview

  suspend fun saveProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressSaveResult

  suspend fun getProjectIdeas(projectId: String): ProjectIdeasProjection

  suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String): ProjectIdeasExecutionResult

  suspend fun setProjectIdeaDisposition(projectId: String, ideaId: String, disposition: String, idempotencyKey: String): ProjectIdeasProjection

  suspend fun promoteProjectIdea(projectId: String, ideaId: String)

  suspend fun applyCheckpointProposal(projectId: String, proposalId: String)

  suspend fun rejectCheckpointProposal(projectId: String, proposalId: String)

  // --- Rich Project Intelligence V1 (ADR-059): typed EXPLORE_PLAN contract ---
  // POST/GET /projects/{project_id}/ai-results/explore-plan[/{result_id}] - the ProjectAIResult
  // presentation envelope (see code/prototype_v1/projects/project_ai_result.py). Every field is
  // read from typed JSON; idea.details (the backend-private persistence encoding) is never parsed.

  /** Requests a new EXPLORE_PLAN interaction. Ready unless the provider needs more information. */
  suspend fun createExplorePlan(projectId: String, userIntent: String, idempotencyKey: String): ExplorePlanCreateResult

  /** Reconstructs one EXPLORE_PLAN interaction from canonical state - safe to call any time, e.g. after reopening the Project. */
  suspend fun getExplorePlan(projectId: String, resultId: String): ExplorePlanResult

  /**
   * Selects one EXPLORE_PLAN option via the backend's existing generic disposition route (the
   * same route the legacy Ideas panel already uses - see setProjectIdeaDisposition above). The
   * backend derives the CheckpointProposal, if any; this client only reads what came back.
   */
  suspend fun selectExplorePlanOption(projectId: String, ideaId: String, idempotencyKey: String): ExplorePlanSelection

  suspend fun createVisualArtifact(projectId: String, resultId: String, optionId: String, idempotencyKey: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable.")
  suspend fun getVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable.")
  suspend fun retryVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String, idempotencyKey: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable.")
  suspend fun getVisualArtifactImages(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifactImages =
      throw UnsupportedOperationException("Visual artifacts are unavailable.")

  // --- ADR-060: unified, planner-selected Project guidance ---
  // POST /projects/{project_id}/ai-results - the caller never names a response family; the
  // backend's Response Planner infers TROUBLESHOOT | EXPLORE_PLAN | GENERAL_GUIDANCE from bounded
  // Project context and this request. investigationSessionId is passed through UNCHANGED when the
  // caller has one (never fabricated when absent) - see project_ai_result.py's
  // ProjectAIResultPlanner._dispatch_troubleshoot for how the backend owns its semantics.

  /**
   * Returns [ProjectGuidanceOutcome.NeedsClarification] (not an exception) when the backend asks
   * one concise clarifying question instead of guessing a family. Any other backend rejection
   * (routing_unavailable, a session-state conflict, project_not_found, etc.) propagates as a
   * [ProjectApiException] - callers show a retryable error and never silently pick another family.
   */
  suspend fun getProjectGuidance(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
      idempotencyKey: String,
  ): ProjectGuidanceOutcome
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
    val revision = project.getInt("revision")

    val checkpointJson = project.optJSONObject("checkpoint")
    val checkpoint = ProjectCheckpoint(
        whereWeLeftOff = checkpointJson?.optNullableString("current_work"),
        nextAction = checkpointJson?.optNullableString("next_action"),
        blockers = checkpointJson?.optNullableString("blockers"),
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

    // Best-effort, same as the investigation fetch above: a failure here must not fail the whole
    // Overview - it only means the HUD/Project Detail show no Explore-plan content this refresh.
    val latestExplorePlan = try {
      loadLatestExplorePlanSummary(normalizedProjectId, pendingProposals)
    } catch (_: ProjectApiException) {
      null
    }

    return ProjectOverview(
        project = summary,
        revision = revision,
        checkpoint = checkpoint,
        recentActivity = recentActivity,
        latestInvestigation = investigation,
        pendingProposals = pendingProposals,
        investigationLoadError = investigationLoadError,
        latestExplorePlan = latestExplorePlan,
    )
  }

  /**
   * Bounded HUD-relevant EXPLORE_PLAN summary for the Project Overview, reusing the backend's
   * existing GET /interactions/explore read projection (the same one the legacy Ideas panel
   * already calls) rather than requiring a caller-known result_id. No new backend contract.
   */
  private fun loadLatestExplorePlanSummary(
      normalizedProjectId: String,
      pendingProposals: List<CheckpointProposalReview>,
  ): ExplorePlanOverviewSummary? {
    val projection = executeJsonObject(path = "/projects/$normalizedProjectId/interactions/explore")
    val groups = projection.optJSONArray("option_sets") ?: JSONArray()
    if (groups.length() == 0) return null
    // Backend returns option_sets oldest-first; the most recently completed interaction is last.
    val latestGroup = groups.getJSONObject(groups.length() - 1)
    val resultId = latestGroup.getString("interaction_id")
    val recommendedOrdinal = latestGroup.optNullableInt("recommended_ordinal")
    val optionsJson = latestGroup.optJSONArray("options") ?: JSONArray()
    val recommendedTitle = (0 until optionsJson.length())
        .map { optionsJson.getJSONObject(it) }
        .firstOrNull { it.optInt("ordinal", -1) == recommendedOrdinal }
        ?.optJSONObject("idea")?.optNullableString("summary")

    val preferred = projection.optJSONObject("preferred_direction")
    val preferredIdea = preferred?.optJSONObject("idea")
    val selectedTitle = preferredIdea?.optNullableString("summary")
    val selectedIdeaId = preferredIdea?.optNullableString("activity_id")
    val hasPendingSelectionProposal = selectedIdeaId != null && pendingProposals.any { proposal ->
      proposal.status == "pending" && selectedIdeaId in proposal.sourceActivityIds
    }
    return ExplorePlanOverviewSummary(
        resultId = resultId,
        recommendedOptionTitle = recommendedTitle,
        selectedOptionTitle = selectedTitle,
        hasPendingSelectionProposal = hasPendingSelectionProposal,
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

  override suspend fun getProjectConversation(projectId: String): ProjectConversation {
    val normalizedProjectId = normalizeId(projectId)
    return executeJsonObject(path = "/projects/$normalizedProjectId/conversation")
        .toProjectConversation(normalizedProjectId)
  }

  override suspend fun sendProjectConversationMessage(
      projectId: String,
      text: String,
      evidenceRefs: List<ConversationEvidenceReference>,
      idempotencyKey: String,
  ): ConversationSendResult {
    val normalizedProjectId = normalizeId(projectId)
    val body = JSONObject().apply {
      put("text", text)
      put("idempotency_key", idempotencyKey)
      put("evidence_refs", JSONArray().apply {
        evidenceRefs.forEach { reference ->
          put(JSONObject().apply {
            put("type", "PROJECT_RESOURCE_REFERENCE")
            put("resource_kind", "EVIDENCE")
            put("resource_id", normalizeId(reference.evidenceId))
            put("relationship", "ATTACHED")
            put("container_kind", "INVESTIGATION_SESSION")
            put("container_id", normalizeId(reference.investigationSessionId))
          })
        }
      })
    }
    val response = executeJsonObject(
        path = "/projects/$normalizedProjectId/conversation/messages",
        method = "POST",
        body = body.toString(),
        readTimeoutMillis = 60_000,
    )
    if (response.getString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned conversation for a different Project.")
    }
    return ConversationSendResult(
        conversationId = response.getString("conversation_id"),
        projectId = normalizedProjectId,
        turns = response.getJSONArray("turns").toConversationTurns(normalizedProjectId),
        reconstructed = response.optBoolean("reconstructed", false),
    )
  }

  override suspend fun getConversationEvidenceImage(
      projectId: String,
      reference: ConversationEvidenceReference,
  ): ByteArray = executeBytes(
      "/projects/${normalizeId(projectId)}/investigation-sessions/" +
          "${normalizeId(reference.investigationSessionId)}/evidence/${normalizeId(reference.evidenceId)}/content",
  )

  private fun JSONObject.toProjectConversation(expectedProjectId: String): ProjectConversation {
    val returnedProjectId = getString("project_id")
    if (returnedProjectId != expectedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned conversation for a different Project.")
    }
    return ProjectConversation(
        conversationId = getString("conversation_id"),
        projectId = returnedProjectId,
        turns = getJSONArray("turns").toConversationTurns(expectedProjectId),
    )
  }

  private fun JSONArray.toConversationTurns(expectedProjectId: String): List<ConversationTurn> =
      (0 until length()).map { index ->
        val turn = getJSONObject(index)
        if (turn.getString("project_id") != expectedProjectId) {
          throw ProjectApiException(200, "project_mismatch", "Backend returned a turn for a different Project.")
        }
        val content = turn.getJSONArray("content_parts")
        val text = (0 until content.length()).map { content.getJSONObject(it) }
            .firstOrNull { it.getString("type") == "TEXT" }?.getString("text")
            ?: throw ProjectApiException(200, "invalid_response", "Conversation turn has no text.")
        val refs = (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.getString("type") == "PROJECT_RESOURCE_REFERENCE" }
            .map { part -> ConversationEvidenceReference(
                evidenceId = part.getString("resource_id"),
                investigationSessionId = part.getString("container_id"),
            ) }
        // Phase 3B: any other content_part type (e.g. EXPLORE_REFERENCE) stays silently ignored by
        // this parser, same as before - only VISUAL_ARTIFACT_REFERENCE is understood here.
        val visualArtifactRef = (0 until content.length()).map { content.getJSONObject(it) }
            .firstOrNull { it.getString("type") == "VISUAL_ARTIFACT_REFERENCE" }
            ?.let { part -> ConversationVisualArtifactReference(
                projectAiResultId = part.getString("project_ai_result_id"),
                optionId = part.getString("option_id"),
                artifactId = part.getString("artifact_id"),
            ) }
        ConversationTurn(
            turnId = turn.getString("turn_id"),
            projectId = expectedProjectId,
            sequenceNumber = turn.getInt("sequence_number"),
            role = ConversationRole.valueOf(turn.getString("role")),
            status = ConversationTurnStatus.valueOf(turn.getString("status")),
            text = text,
            evidenceRefs = refs,
            idempotencyKey = turn.getString("idempotency_key"),
            visualArtifactRef = visualArtifactRef,
        )
      }.sortedBy { it.sequenceNumber }

  override suspend fun previewProjectProgress(
      projectId: String,
      request: ProjectProgressRequest,
  ): ProjectProgressPreview =
      executeJsonObject(
          path = "/projects/${normalizeId(projectId)}/progress/preview",
          method = "POST",
          body = request.toJson().toString(),
      ).toProjectProgressPreview(normalizeId(projectId))

  override suspend fun saveProjectProgress(
      projectId: String,
      request: ProjectProgressRequest,
  ): ProjectProgressSaveResult {
    val normalizedProjectId = normalizeId(projectId)
    val response = executeJsonObject(
        path = "/projects/$normalizedProjectId/progress",
        method = "POST",
        body = request.toJson().toString(),
    )
    if (response.getString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend saved progress for a different Project.")
    }
    return ProjectProgressSaveResult(
        projectId = normalizedProjectId,
        idempotencyKey = response.getString("idempotency_key"),
        reconstructed = response.optBoolean("reconstructed", false),
    )
  }

  override suspend fun getProjectIdeas(projectId: String): ProjectIdeasProjection =
      executeJsonObject(path = "/projects/${normalizeId(projectId)}/interactions/explore").toProjectIdeasProjection()

  override suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String): ProjectIdeasExecutionResult {
    val normalizedProjectId = normalizeId(projectId)
    val body = JSONObject().apply {
      put("user_intent", intent)
      put("input_refs", JSONArray())
      put("idempotency_key", idempotencyKey)
    }
    val response = executeJsonObject(
        path = "/projects/$normalizedProjectId/interactions/explore",
        method = "POST",
        body = body.toString(),
    )
    if (response.getString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned ideas for a different Project.")
    }
    return when (response.getString("result_type")) {
      "OPTION_SET" -> {
        val projection = getProjectIdeas(normalizedProjectId)
        if (projection.projectId != normalizedProjectId) {
          throw ProjectApiException(200, "project_mismatch", "Backend returned ideas for a different Project.")
        }
        ProjectIdeasExecutionResult.Options(projection)
      }
      "INFORMATION_REQUEST" -> {
        val prompt = response.optJSONObject("information_request")?.optNullableString("prompt")
            ?: response.optNullableString("message")
            ?: "More information is needed before suggestions can be created."
        ProjectIdeasExecutionResult.InformationRequest(prompt)
      }
      else -> throw ProjectApiException(200, "invalid_response", "Backend returned an unsupported ideas result.")
    }
  }

  override suspend fun setProjectIdeaDisposition(
      projectId: String,
      ideaId: String,
      disposition: String,
      idempotencyKey: String,
  ): ProjectIdeasProjection {
    val body = JSONObject().apply {
      put("disposition", disposition)
      put("idempotency_key", idempotencyKey)
    }
    val response = executeJsonObject(
        path = "/projects/${normalizeId(projectId)}/ideas/${normalizeId(ideaId)}/disposition",
        method = "POST",
        body = body.toString(),
    )
    return response.getJSONObject("projection").toProjectIdeasProjection()
  }

  override suspend fun promoteProjectIdea(projectId: String, ideaId: String) {
    executeJsonObject(
        path = "/projects/${normalizeId(projectId)}/ideas/${normalizeId(ideaId)}/promote",
        method = "POST",
    )
  }

  override suspend fun applyCheckpointProposal(projectId: String, proposalId: String) {
    executeJsonObject(path = "/projects/${normalizeId(projectId)}/checkpoint-proposals/${normalizeId(proposalId)}/apply", method = "POST")
  }

  override suspend fun rejectCheckpointProposal(projectId: String, proposalId: String) {
    executeJsonObject(path = "/projects/${normalizeId(projectId)}/checkpoint-proposals/${normalizeId(proposalId)}/reject", method = "POST")
  }

  override suspend fun createExplorePlan(projectId: String, userIntent: String, idempotencyKey: String): ExplorePlanCreateResult {
    val normalizedProjectId = normalizeId(projectId)
    val body = JSONObject().apply {
      put("user_intent", userIntent)
      put("input_refs", JSONArray())
      put("idempotency_key", idempotencyKey)
    }
    return try {
      val response = executeJsonObject(
          path = "/projects/$normalizedProjectId/ai-results/explore-plan",
          method = "POST",
          body = body.toString(),
      )
      if (response.getString("project_id") != normalizedProjectId) {
        throw ProjectApiException(200, "project_mismatch", "Backend returned an Explore plan for a different Project.")
      }
      ExplorePlanCreateResult.Ready(response.toExplorePlanResult())
    } catch (exc: ProjectApiException) {
      if (exc.category == "explore_plan_needs_more_information") {
        ExplorePlanCreateResult.InformationRequest(
            exc.message.ifBlank { "More information is needed before design ideas can be created." },
        )
      } else {
        throw exc
      }
    }
  }

  override suspend fun getExplorePlan(projectId: String, resultId: String): ExplorePlanResult {
    val normalizedProjectId = normalizeId(projectId)
    val response = executeJsonObject(
        path = "/projects/$normalizedProjectId/ai-results/explore-plan/${normalizeId(resultId)}",
    )
    if (response.getString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned an Explore plan for a different Project.")
    }
    return response.toExplorePlanResult()
  }

  override suspend fun selectExplorePlanOption(projectId: String, ideaId: String, idempotencyKey: String): ExplorePlanSelection {
    val normalizedProjectId = normalizeId(projectId)
    val body = JSONObject().apply {
      put("disposition", "select")
      put("idempotency_key", idempotencyKey)
    }
    val response = executeJsonObject(
        path = "/projects/$normalizedProjectId/ideas/${normalizeId(ideaId)}/disposition",
        method = "POST",
        body = body.toString(),
    )
    val idea = response.getJSONObject("idea")
    if (idea.optNullableString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned a selection for a different Project.")
    }
    val proposalJson = response.optJSONObject("checkpoint_proposal")
    if (proposalJson != null && proposalJson.optNullableString("project_id") != normalizedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend returned a Project change for a different Project.")
    }
    return ExplorePlanSelection(
        selectedIdeaId = idea.getString("activity_id"),
        checkpointProposal = proposalJson?.toCheckpointProposalReview(),
    )
  }

  override suspend fun getProjectGuidance(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
      idempotencyKey: String,
  ): ProjectGuidanceOutcome {
    val normalizedProjectId = normalizeId(projectId)
    val body = JSONObject().apply {
      put("user_request", userRequest)
      investigationSessionId?.let { put("investigation_session_id", normalizeId(it)) }
      put("idempotency_key", idempotencyKey)
    }
    return try {
      val response = executeJsonObject(
          path = "/projects/$normalizedProjectId/ai-results",
          method = "POST",
          body = body.toString(),
          readTimeoutMillis = 30_000,
      )
      if (response.getString("project_id") != normalizedProjectId) {
        throw ProjectApiException(200, "project_mismatch", "Backend returned guidance for a different Project.")
      }
      ProjectGuidanceOutcome.Ready(response.toProjectGuidanceResult())
    } catch (exc: ProjectApiException) {
      if (exc.category == "routing_needs_clarification") {
        ProjectGuidanceOutcome.NeedsClarification(exc.message.ifBlank { "Could you share a bit more detail?" })
      } else {
        throw exc
      }
    }
  }

  override suspend fun createVisualArtifact(projectId: String, resultId: String, optionId: String, idempotencyKey: String): VisualArtifact {
    val path = visualArtifactBase(projectId, resultId, optionId)
    val body = JSONObject().put("idempotency_key", idempotencyKey).toString()
    return executeJsonObject(path, "POST", body, readTimeoutMillis = 30_000).toVisualArtifact()
  }

  override suspend fun getVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifact =
      executeJsonObject("${visualArtifactBase(projectId, resultId, optionId)}/${normalizeId(artifactId)}").toVisualArtifact()

  override suspend fun retryVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String, idempotencyKey: String): VisualArtifact {
    val body = JSONObject().put("idempotency_key", idempotencyKey).toString()
    return executeJsonObject(
        "${visualArtifactBase(projectId, resultId, optionId)}/${normalizeId(artifactId)}/retry",
        "POST", body, readTimeoutMillis = 60_000,
    ).toVisualArtifact()
  }

  override suspend fun getVisualArtifactImages(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifactImages {
    val base = "${visualArtifactBase(projectId, resultId, optionId)}/${normalizeId(artifactId)}"
    return VisualArtifactImages(source = executeBytes("$base/source"), visualization = executeBytes("$base/content"))
  }

  private fun visualArtifactBase(projectId: String, resultId: String, optionId: String): String =
      "/projects/${normalizeId(projectId)}/ai-results/explore-plan/${normalizeId(resultId)}/options/${normalizeId(optionId)}/visual-artifacts"

  private fun JSONObject.toVisualArtifact() = VisualArtifact(
      artifactId = getString("artifact_id"),
      projectId = getString("project_id"),
      resultId = getString("project_ai_result_id"),
      optionId = getString("option_id"),
      status = VisualArtifactStatus.valueOf(getString("status")),
      sourceEvidenceIds = optJSONArray("source_evidence_ids").toStringList(),
      retention = getString("retention"),
      failureCategory = optNullableString("failure_category"),
  )

  private fun JSONObject.toProjectGuidanceResult(): ProjectGuidanceResult {
    return when (val resultType = getString("result_type")) {
      "EXPLORE_PLAN" -> ProjectGuidanceResult.ExplorePlan(toExplorePlanResult())
      "GENERAL_GUIDANCE" -> {
        val guidance = getJSONObject("general_guidance")
        ProjectGuidanceResult.GeneralGuidance(
            answer = guidance.getString("answer"),
            uncertain = guidance.optBoolean("insufficient_context", false),
        )
      }
      "TROUBLESHOOT" -> {
        val evidenceBacked = optJSONObject("troubleshoot")
        if (evidenceBacked != null) {
          ProjectGuidanceResult.TroubleshootEvidence(resultId = getString("result_id"))
        } else {
          val text = getJSONObject("troubleshoot_text")
          ProjectGuidanceResult.TroubleshootText(
              diagnosis = text.getString("diagnosis"),
              recommendedNextAction = text.getString("recommended_next_action"),
              uncertain = text.optBoolean("uncertain", false),
          )
        }
      }
      else -> throw ProjectApiException(200, "invalid_response", "Backend returned an unsupported guidance result type: $resultType.")
    }
  }

  private fun JSONObject.toExplorePlanResult(): ExplorePlanResult {
    val hud = getJSONObject("hud_projection")
    val group = getJSONObject("explore_plan")
    val optionsJson = group.optJSONArray("options") ?: JSONArray()
    val options = (0 until optionsJson.length()).map { index -> optionsJson.getJSONObject(index).toExplorePlanOption() }
    return ExplorePlanResult(
        projectId = getString("project_id"),
        resultId = getString("result_id"),
        complete = group.optBoolean("complete", false),
        title = group.optNullableString("title"),
        summary = group.optNullableString("summary"),
        hudHeadline = hud.getString("headline"),
        hudNext = hud.optNullableString("next"),
        hudUncertain = hud.optBoolean("uncertainty_flag", false),
        observations = group.optJSONArray("observations").toStringList(),
        recommendedOrdinal = group.optNullableInt("recommended_ordinal"),
        recommendationReason = group.optNullableString("recommendation_reason"),
        nextSteps = group.optJSONArray("next_steps").toStringList(),
        followUpQuestions = group.optJSONArray("follow_up_questions").toStringList(),
        options = options,
    )
  }

  private fun JSONObject.toExplorePlanOption(): ExplorePlanOption {
    val idea = getJSONObject("idea")
    val cost = optJSONObject("estimated_cost")?.let {
      ExplorePlanEstimatedCost(
          currency = it.getString("currency"),
          minAmount = it.optNullableDouble("min_amount"),
          maxAmount = it.optNullableDouble("max_amount"),
          qualifier = it.optNullableString("qualifier"),
      )
    }
    return ExplorePlanOption(
        ideaId = idea.getString("activity_id"),
        ordinal = getInt("ordinal"),
        title = idea.getString("summary"),
        summary = getString("summary"),
        rationale = optNullableString("rationale"),
        tradeoffs = optNullableString("tradeoffs"),
        concept = optNullableString("concept"),
        proposedChanges = optNullableString("proposed_changes"),
        estimatedCost = cost,
        recommended = optBoolean("recommended", false),
        disposition = optNullableString("disposition"),
    )
  }

  private fun JSONObject.toCheckpointProposalReview(): CheckpointProposalReview {
    val patch = getJSONObject("proposed_checkpoint_patch")
    val fields = patch.keys().asSequence().associateWith { key -> patch.optNullableString(key) }
    val sourceIds = optJSONArray("source_activity_ids")
    return CheckpointProposalReview(
        proposalId = getString("proposal_id"),
        projectId = getString("project_id"),
        status = getString("status"),
        reason = getString("reason"),
        proposedFields = fields,
        sourceActivityIds = sourceIds.toStringList(),
    )
  }

  private fun ProjectProgressRequest.toJson() = JSONObject().apply {
    put("idempotency_key", idempotencyKey)
    put("summary", summary)
    details?.let { put("details", it) }
    put("expected_project_revision", expectedProjectRevision)
    checkpointPatch?.let { patch ->
      put("checkpoint_patch", JSONObject().apply {
        patch.currentWork?.let { put("current_work", it) }
        patch.blockers?.let { put("blockers", it) }
        patch.nextAction?.let { put("next_action", it) }
      })
    }
  }

  private fun JSONObject.toProjectProgressPreview(expectedProjectId: String): ProjectProgressPreview {
    val responseProjectId = getString("project_id")
    if (responseProjectId != expectedProjectId) {
      throw ProjectApiException(200, "project_mismatch", "Backend previewed progress for a different Project.")
    }
    val patch = optJSONObject("effective_checkpoint_patch")?.let {
      ProjectProgressCheckpointPatch(
          currentWork = it.optNullableString("current_work"),
          blockers = it.optNullableString("blockers"),
          nextAction = it.optNullableString("next_action"),
      )
    }
    return ProjectProgressPreview(
        projectId = responseProjectId,
        idempotencyKey = getString("idempotency_key"),
        summary = getString("summary"),
        details = optNullableString("details"),
        baseProjectRevision = getInt("base_project_revision"),
        effectiveCheckpointPatch = patch,
        proposalRequired = getBoolean("proposal_required"),
    )
  }

  private fun JSONObject.toProjectIdeasProjection(): ProjectIdeasProjection {
    val options = mutableListOf<ProjectIdeaOption>()
    val groups = optJSONArray("option_sets") ?: JSONArray()
    for (groupIndex in 0 until groups.length()) {
      val groupOptions = groups.getJSONObject(groupIndex).optJSONArray("options") ?: JSONArray()
      for (optionIndex in 0 until groupOptions.length()) {
        val option = groupOptions.getJSONObject(optionIndex)
        val idea = option.getJSONObject("idea")
        options += ProjectIdeaOption(
            ideaId = idea.getString("activity_id"),
            ordinal = option.getInt("ordinal"),
            summary = idea.getString("summary"),
            details = idea.optNullableString("details"),
            disposition = option.optNullableString("disposition"),
            promoted = option.optBoolean("promoted", false),
        )
      }
    }
    val preferred = optJSONObject("preferred_direction")?.optJSONObject("idea")?.optNullableString("activity_id")
    return ProjectIdeasProjection(
        projectId = getString("project_id"),
        options = options,
        preferredIdeaId = preferred,
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

  private fun JSONObject.optNullableInt(key: String): Int? {
    if (isNull(key) || !has(key)) return null
    return optInt(key)
  }

  private fun JSONObject.optNullableDouble(key: String): Double? {
    if (isNull(key) || !has(key)) return null
    return optDouble(key)
  }

  private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { index -> getString(index) }
  }

  private fun executeJsonObject(
      path: String,
      method: String = "GET",
      body: String? = null,
      readTimeoutMillis: Int = 15_000,
  ): JSONObject {
    val connection = openConnection(path, method = method, body = body, readTimeoutMillis = readTimeoutMillis)
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

  private fun openConnection(
      path: String,
      method: String = "GET",
      body: String? = null,
      readTimeoutMillis: Int = 15_000,
  ): HttpURLConnection {
    val url = URL("${baseUrl.trimEnd('/')}$path")
    return connectionFactory(url).apply {
      requestMethod = method
      connectTimeout = 15_000
      readTimeout = readTimeoutMillis
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
