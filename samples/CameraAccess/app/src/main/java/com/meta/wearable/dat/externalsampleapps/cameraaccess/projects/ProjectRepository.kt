/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectRepository - Project Overview data source boundary
//
// ProjectsViewModel/ProjectDetailViewModel depend only on this interface, not on any concrete
// implementation. HttpUrlProjectRepository (backed by ProjectBackendClient -> the real FastAPI
// Project Memory API) is the production source. MockProjectRepository is retained only for
// isolated tests/previews - see class doc below - and must never be the production source for
// Projects Home.
//
// This slice performs read-only network calls (GET only, via ProjectApi) - no OpenAI calls, no
// local database, no Android-owned canonical Project Memory. The FastAPI backend remains the
// single source of truth (see docs/PROJECT_MEMORY_ARCHITECTURE.md).
//
// Base URL: reuses investigation.InvestigationBackendConfig.resolveBaseUrl() as-is rather than
// introducing a second, independently-configured base URL. Investigation and Project traffic
// both target the exact same FastAPI backend origin, so a second config object would risk the
// two drifting out of sync (e.g. after a tunnel URL rotates) for no benefit. That object's own
// logic (emulator/physical-device host resolution, BuildConfig.INVESTIGATION_BACKEND_BASE_URL)
// is generic backend-reachability handling, not Investigation-specific.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationBackendConfig

interface ProjectRepository {
  /** Throws on network/backend failure - callers map that to an error UI state. */
  suspend fun listProjects(): List<ProjectSummary>

  /** Throws on network/backend failure - callers map that to an error UI state. */
  suspend fun getProjectOverview(projectId: String): ProjectOverview

  /**
   * Creates a real backend Project via POST /projects and returns its canonical identity. Throws
   * on validation/network/backend failure - the caller keeps the form editable and surfaces the
   * error rather than assuming success.
   */
  suspend fun createProject(request: NewProjectRequest): ProjectSummary

  /**
   * The backend's one Active Project (see docs/PROJECT_MEMORY_ARCHITECTURE.md), or null if none
   * is currently set - a normal state, not an error. This is distinct from which Project a
   * screen is merely viewing: opening a Project never calls setActiveProject.
   */
  suspend fun getActiveProject(): ProjectSummary?

  /**
   * Makes this Project the backend's Active Project; the previous Active Project (if any) loses
   * that status. Throws on failure - the caller must leave canonical state unchanged rather than
   * assuming success.
   */
  suspend fun setActiveProject(projectId: String): ProjectSummary

  /** Clears the Active Project. Idempotent. Throws on failure. */
  suspend fun clearActiveProject()

  /**
   * Asks the given Project (its own canonical project_id - never the Active Project unless they
   * happen to be the same) a question via the backend's existing, read-only Project Q&A route.
   * Throws on validation/network/backend failure - the caller keeps the question editable and
   * surfaces the error rather than assuming an answer. Never mutates Project state.
   */
  suspend fun askProject(projectId: String, question: String): ProjectAskAnswer

  suspend fun getProjectConversation(projectId: String): ProjectConversation =
      throw UnsupportedOperationException("Project conversation is unavailable in this repository.")

  suspend fun sendProjectConversationMessage(
      projectId: String,
      text: String,
      evidenceRefs: List<ConversationEvidenceReference>,
      idempotencyKey: String,
  ): ConversationSendResult = throw UnsupportedOperationException("Project conversation is unavailable in this repository.")

  /**
   * Phase 3A closeout: resolves a [ConversationEvidenceReference] already persisted on a
   * conversation turn back to its raw image bytes, via the same Project-isolated Evidence content
   * route the legacy Investigation panel already reads from. Conversation only ever stores the
   * reference; this is the on-demand read path back to the canonical Evidence bytes - never a
   * second image store.
   */
  suspend fun getConversationEvidenceImage(
      projectId: String,
      reference: ConversationEvidenceReference,
  ): ByteArray = throw UnsupportedOperationException("Conversation evidence images are unavailable in this repository.")

  suspend fun previewProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressPreview

  suspend fun saveProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressSaveResult

  suspend fun getProjectIdeas(projectId: String): ProjectIdeasProjection

  suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String): ProjectIdeasExecutionResult

  suspend fun setProjectIdeaDisposition(projectId: String, ideaId: String, disposition: String, idempotencyKey: String): ProjectIdeasProjection

  suspend fun promoteProjectIdea(projectId: String, ideaId: String)

  suspend fun applyCheckpointProposal(projectId: String, proposalId: String)

  suspend fun rejectCheckpointProposal(projectId: String, proposalId: String)

  suspend fun createExplorePlan(projectId: String, userIntent: String, idempotencyKey: String): ExplorePlanCreateResult

  suspend fun getExplorePlan(projectId: String, resultId: String): ExplorePlanResult

  suspend fun selectExplorePlanOption(projectId: String, ideaId: String, idempotencyKey: String): ExplorePlanSelection

  suspend fun createVisualArtifact(projectId: String, resultId: String, optionId: String, idempotencyKey: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable in this repository.")
  suspend fun getVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable in this repository.")
  suspend fun retryVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String, idempotencyKey: String): VisualArtifact =
      throw UnsupportedOperationException("Visual artifacts are unavailable in this repository.")
  suspend fun getVisualArtifactImages(projectId: String, resultId: String, optionId: String, artifactId: String): VisualArtifactImages =
      throw UnsupportedOperationException("Visual artifacts are unavailable in this repository.")

  /** ADR-060: one unified, planner-selected guidance request. See ProjectApi.getProjectGuidance's doc. */
  suspend fun getProjectGuidance(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
      idempotencyKey: String,
  ): ProjectGuidanceOutcome
}

/** Production repository: reads/creates real Project Memory data via the FastAPI backend. */
class HttpUrlProjectRepository(
    private val api: ProjectApi = HttpUrlProjectApi(InvestigationBackendConfig.resolveBaseUrl()),
) : ProjectRepository {
  override suspend fun listProjects(): List<ProjectSummary> = api.listProjects()

  override suspend fun getProjectOverview(projectId: String): ProjectOverview =
      api.getProjectOverview(projectId)

  override suspend fun createProject(request: NewProjectRequest): ProjectSummary =
      api.createProject(request)

  override suspend fun getActiveProject(): ProjectSummary? = api.getActiveProject()

  override suspend fun setActiveProject(projectId: String): ProjectSummary =
      api.setActiveProject(projectId)

  override suspend fun clearActiveProject() = api.clearActiveProject()

  override suspend fun askProject(projectId: String, question: String): ProjectAskAnswer =
      api.askProject(projectId, question)

  override suspend fun getProjectConversation(projectId: String) = api.getProjectConversation(projectId)

  override suspend fun sendProjectConversationMessage(
      projectId: String,
      text: String,
      evidenceRefs: List<ConversationEvidenceReference>,
      idempotencyKey: String,
  ) = api.sendProjectConversationMessage(projectId, text, evidenceRefs, idempotencyKey)

  override suspend fun getConversationEvidenceImage(projectId: String, reference: ConversationEvidenceReference) =
      api.getConversationEvidenceImage(projectId, reference)

  override suspend fun previewProjectProgress(projectId: String, request: ProjectProgressRequest) =
      api.previewProjectProgress(projectId, request)

  override suspend fun saveProjectProgress(projectId: String, request: ProjectProgressRequest) =
      api.saveProjectProgress(projectId, request)

  override suspend fun getProjectIdeas(projectId: String) = api.getProjectIdeas(projectId)

  override suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String) =
      api.generateProjectIdeas(projectId, intent, idempotencyKey)

  override suspend fun setProjectIdeaDisposition(projectId: String, ideaId: String, disposition: String, idempotencyKey: String) =
      api.setProjectIdeaDisposition(projectId, ideaId, disposition, idempotencyKey)

  override suspend fun promoteProjectIdea(projectId: String, ideaId: String) = api.promoteProjectIdea(projectId, ideaId)

  override suspend fun applyCheckpointProposal(projectId: String, proposalId: String) =
      api.applyCheckpointProposal(projectId, proposalId)

  override suspend fun rejectCheckpointProposal(projectId: String, proposalId: String) =
      api.rejectCheckpointProposal(projectId, proposalId)

  override suspend fun createExplorePlan(projectId: String, userIntent: String, idempotencyKey: String) =
      api.createExplorePlan(projectId, userIntent, idempotencyKey)

  override suspend fun getExplorePlan(projectId: String, resultId: String) =
      api.getExplorePlan(projectId, resultId)

  override suspend fun selectExplorePlanOption(projectId: String, ideaId: String, idempotencyKey: String) =
      api.selectExplorePlanOption(projectId, ideaId, idempotencyKey)

  override suspend fun createVisualArtifact(projectId: String, resultId: String, optionId: String, idempotencyKey: String) =
      api.createVisualArtifact(projectId, resultId, optionId, idempotencyKey)

  override suspend fun getVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String) =
      api.getVisualArtifact(projectId, resultId, optionId, artifactId)

  override suspend fun retryVisualArtifact(projectId: String, resultId: String, optionId: String, artifactId: String, idempotencyKey: String) =
      api.retryVisualArtifact(projectId, resultId, optionId, artifactId, idempotencyKey)

  override suspend fun getVisualArtifactImages(projectId: String, resultId: String, optionId: String, artifactId: String) =
      api.getVisualArtifactImages(projectId, resultId, optionId, artifactId)

  override suspend fun getProjectGuidance(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
      idempotencyKey: String,
  ) = api.getProjectGuidance(projectId, userRequest, investigationSessionId, idempotencyKey)
}

/**
 * Local, in-memory project state - kept only for isolated tests/Compose previews where hitting a
 * real backend isn't appropriate. This was the production source for the earlier "Project A
 * state != Project B state" navigation-shell proof; that proof is complete, and
 * HttpUrlProjectRepository is now the production source for Projects Home.
 */
class MockProjectRepository : ProjectRepository {
  private val conversations = mutableMapOf<String, ProjectConversation>()
  private val overviews: MutableMap<String, ProjectOverview> =
      listOf(
              ProjectOverview(
                  project = ProjectSummary(
                      projectId = "upstairs-ac-repair",
                      name = "Upstairs AC Repair",
                      status = "active",
                  ),
                  revision = 1,
                  checkpoint = ProjectCheckpoint(
                      whereWeLeftOff =
                          "Capacitor appears swollen - suspected cause of the compressor " +
                              "short-cycling.",
                      nextAction = "Identify the capacitor rating and confirm the replacement part.",
                  ),
                  recentActivity = listOf(
                      ProjectActivityEntry("Captured a photo of the swollen capacitor."),
                      ProjectActivityEntry("Noted the compressor short-cycling every 4-5 minutes."),
                      ProjectActivityEntry("Confirmed breaker panel labeling for the AC circuit."),
                  ),
              ),
              ProjectOverview(
                  project = ProjectSummary(
                      projectId = "custom-meta-ai-glasses",
                      name = "Custom Meta AI Glasses",
                      status = "active",
                  ),
                  revision = 1,
                  checkpoint = ProjectCheckpoint(
                      whereWeLeftOff =
                          "Project Assistant navigation shell is wired up on Android; glasses " +
                              "registration/streaming/capture confirmed unaffected.",
                      nextAction = "Connect Project Overview to the real /projects Project Memory API.",
                  ),
                  recentActivity = listOf(
                      ProjectActivityEntry("Wired the Project Assistant navigation shell into the Android app."),
                      ProjectActivityEntry("Verified Capture / Test Glasses still reaches the existing camera flow."),
                      ProjectActivityEntry("Reviewed docs/PROJECT_MEMORY_ARCHITECTURE.md ahead of Project Overview v1."),
                  ),
              ),
          )
          .associateBy { it.project.projectId }
          .toMutableMap()

  private var activeProjectId: String? = null

  override suspend fun listProjects(): List<ProjectSummary> = overviews.values.map { it.project }

  override suspend fun getProjectOverview(projectId: String): ProjectOverview =
      overviews[projectId] ?: throw NoSuchElementException("No mock project state for $projectId")

  override suspend fun createProject(request: NewProjectRequest): ProjectSummary {
    val projectId = "mock-${overviews.size + 1}-${request.name.lowercase().replace(" ", "-")}"
    val summary = ProjectSummary(projectId = projectId, name = request.name, status = "active")
    overviews[projectId] = ProjectOverview(
        project = summary,
        revision = 1,
        checkpoint = ProjectCheckpoint(
            whereWeLeftOff = null,
            nextAction = request.nextAction,
        ),
        recentActivity = emptyList(),
    )
    return summary
  }

  /**
   * Test-only seeding hook (see class doc: "kept only for isolated tests/Compose previews") - lets
   * a test replace an already-created project's canonical overview wholesale, e.g. to attach a
   * historical, undecided Investigation/trust result or prior evidence before exercising HUD/
   * conversation code against it. Never used by production call sites.
   */
  internal fun seedOverview(overview: ProjectOverview) {
    overviews[overview.project.projectId] = overview
  }

  override suspend fun getActiveProject(): ProjectSummary? =
      activeProjectId?.let { overviews[it]?.project }

  override suspend fun setActiveProject(projectId: String): ProjectSummary {
    val project = overviews[projectId]?.project
        ?: throw NoSuchElementException("No mock project state for $projectId")
    activeProjectId = projectId
    return project
  }

  override suspend fun clearActiveProject() {
    activeProjectId = null
  }

  override suspend fun askProject(projectId: String, question: String): ProjectAskAnswer {
    val overview = overviews[projectId] ?: throw NoSuchElementException("No mock project state for $projectId")
    return ProjectAskAnswer(
        answer = "Mock answer for ${overview.project.name}: $question",
        questionClass = "mock",
        groundingStatus = "grounded",
        insufficientContext = false,
        uncertaintyNote = null,
        referenceSummaries = emptyList(),
        provider = null,
        providerModel = null,
        modelCallCount = 1,
    )
  }

  override suspend fun getProjectConversation(projectId: String): ProjectConversation {
    require(overviews.containsKey(projectId))
    return conversations.getOrPut(projectId) {
      ProjectConversation("conversation-$projectId", projectId, emptyList())
    }
  }

  override suspend fun sendProjectConversationMessage(
      projectId: String,
      text: String,
      evidenceRefs: List<ConversationEvidenceReference>,
      idempotencyKey: String,
  ): ConversationSendResult {
    val current = getProjectConversation(projectId)
    val existing = current.turns.filter { it.idempotencyKey == idempotencyKey }
    if (existing.size == 2) return ConversationSendResult(current.conversationId, projectId, existing, true)
    val user = ConversationTurn(
        "user-$idempotencyKey", projectId, current.turns.size + 1, ConversationRole.USER,
        ConversationTurnStatus.COMPLETED, text, evidenceRefs, idempotencyKey)
    val assistant = ConversationTurn(
        "assistant-$idempotencyKey", projectId, current.turns.size + 2, ConversationRole.ASSISTANT,
        ConversationTurnStatus.COMPLETED, "Mock assistant response to: $text", emptyList(), idempotencyKey)
    conversations[projectId] = current.copy(turns = current.turns + user + assistant)
    return ConversationSendResult(current.conversationId, projectId, listOf(user, assistant), false)
  }

  override suspend fun previewProjectProgress(projectId: String, request: ProjectProgressRequest) =
      ProjectProgressPreview(
          projectId = projectId,
          idempotencyKey = request.idempotencyKey,
          summary = request.summary,
          details = request.details,
          baseProjectRevision = request.expectedProjectRevision,
          effectiveCheckpointPatch = request.checkpointPatch,
          proposalRequired = request.checkpointPatch != null,
      )

  override suspend fun saveProjectProgress(projectId: String, request: ProjectProgressRequest): ProjectProgressSaveResult {
    require(overviews.containsKey(projectId))
    return ProjectProgressSaveResult(projectId, request.idempotencyKey, reconstructed = false)
  }

  override suspend fun getProjectIdeas(projectId: String) =
      ProjectIdeasProjection(projectId, emptyList(), null)

  override suspend fun generateProjectIdeas(projectId: String, intent: String, idempotencyKey: String) =
      ProjectIdeasExecutionResult.Options(
          ProjectIdeasProjection(
              projectId,
              listOf(
                  ProjectIdeaOption("mock-1", 1, "Warm modern", "Warm wood and soft neutrals.", null, false),
                  ProjectIdeaOption("mock-2", 2, "Dark contemporary", "Deep contrast and restrained accents.", null, false),
                  ProjectIdeaOption("mock-3", 3, "Minimal natural", "Natural textures and a quiet palette.", null, false),
              ),
              null,
          ),
      )

  override suspend fun setProjectIdeaDisposition(projectId: String, ideaId: String, disposition: String, idempotencyKey: String) =
      getProjectIdeas(projectId)

  override suspend fun promoteProjectIdea(projectId: String, ideaId: String) = Unit

  override suspend fun applyCheckpointProposal(projectId: String, proposalId: String) {
    require(overviews.containsKey(projectId))
    val proposal = explorePlanProposals[proposalId] ?: return
    explorePlanProposals[proposalId] = proposal.copy(status = "applied")
    val overview = overviews.getValue(projectId)
    overviews[projectId] = overview.copy(
        pendingProposals = overview.pendingProposals.filterNot { it.proposalId == proposalId },
        checkpoint = overview.checkpoint.copy(
            whereWeLeftOff = proposal.proposedFields["current_work"] ?: overview.checkpoint.whereWeLeftOff,
            nextAction = proposal.proposedFields["next_action"] ?: overview.checkpoint.nextAction,
        ),
    )
  }

  override suspend fun rejectCheckpointProposal(projectId: String, proposalId: String) {
    require(overviews.containsKey(projectId))
    explorePlanProposals[proposalId] = explorePlanProposals[proposalId]?.copy(status = "rejected") ?: return
    val overview = overviews.getValue(projectId)
    overviews[projectId] = overview.copy(pendingProposals = overview.pendingProposals.filterNot { it.proposalId == proposalId })
  }

  private val explorePlans = mutableMapOf<String, ExplorePlanResult>()
  private val explorePlanProposals = mutableMapOf<String, CheckpointProposalReview>()

  override suspend fun createExplorePlan(projectId: String, userIntent: String, idempotencyKey: String): ExplorePlanCreateResult {
    require(overviews.containsKey(projectId))
    val resultId = "mock-explore-plan-1"
    val plan = explorePlans[resultId] ?: ExplorePlanResult(
        projectId = projectId,
        resultId = resultId,
        complete = true,
        title = "Room directions",
        summary = "Three possible directions for this room.",
        hudHeadline = "3 design ideas ready. AI recommends Warm Modern.",
        hudNext = "Confirm budget range with the user.",
        hudUncertain = false,
        observations = listOf("The room currently reads as cold and under-furnished."),
        recommendedOrdinal = 1,
        recommendationReason = "Warm Modern best matches the stated goal of a warmer, welcoming room.",
        nextSteps = listOf("Confirm budget range with the user.", "Select material samples for the recommended direction."),
        followUpQuestions = listOf("Is there an existing color palette to keep?"),
        options = listOf(
            ExplorePlanOption(
                ideaId = "mock-option-1", ordinal = 1, title = "Warm Modern",
                summary = "Warm wood and soft neutral layers.", rationale = "Supports a welcoming room.",
                tradeoffs = "Needs material samples.", concept = "Layer warm wood tones with soft neutral textiles.",
                proposedChanges = "Add oak accents and warm-white lighting.",
                estimatedCost = ExplorePlanEstimatedCost("USD", 500.0, 1200.0, "rough estimate"),
                recommended = true, disposition = null,
            ),
            ExplorePlanOption(
                ideaId = "mock-option-2", ordinal = 2, title = "Dark Contemporary",
                summary = "Deep contrast with restrained accents.", rationale = null, tradeoffs = null,
                concept = null, proposedChanges = null, estimatedCost = null, recommended = false, disposition = null,
            ),
            ExplorePlanOption(
                ideaId = "mock-option-3", ordinal = 3, title = "Minimal Natural",
                summary = "Natural textures and a quiet palette.", rationale = null, tradeoffs = null,
                concept = null, proposedChanges = null, estimatedCost = null, recommended = false, disposition = null,
            ),
        ),
    )
    explorePlans[resultId] = plan
    return ExplorePlanCreateResult.Ready(plan)
  }

  override suspend fun getExplorePlan(projectId: String, resultId: String): ExplorePlanResult =
      explorePlans[resultId] ?: throw NoSuchElementException("No mock Explore plan for $resultId")

  override suspend fun selectExplorePlanOption(projectId: String, ideaId: String, idempotencyKey: String): ExplorePlanSelection {
    val plan = explorePlans.values.firstOrNull { plan -> plan.options.any { it.ideaId == ideaId } }
        ?: throw NoSuchElementException("No mock Explore plan option $ideaId")
    val updatedOptions = plan.options.map { option -> option.copy(disposition = if (option.ideaId == ideaId) "select" else option.disposition) }
    explorePlans[plan.resultId] = plan.copy(options = updatedOptions)

    val existingProposalId = explorePlanProposals.values.firstOrNull { ideaId in it.sourceActivityIds }?.proposalId
    val selectedOption = plan.options.first { it.ideaId == ideaId }
    val proposal = if (existingProposalId != null) {
      explorePlanProposals.getValue(existingProposalId)
    } else {
      val proposalId = "mock-explore-proposal-${explorePlanProposals.size + 1}"
      CheckpointProposalReview(
          proposalId = proposalId,
          projectId = projectId,
          status = "pending",
          reason = "User selected \"${selectedOption.title}\" as the preferred direction; applying this proposal records that direction and its next step in canonical Project state.",
          proposedFields = mapOf(
              "current_work" to "Selected direction: ${selectedOption.title}. ${selectedOption.summary}",
              "next_action" to plan.nextSteps.firstOrNull(),
          ),
          sourceActivityIds = listOf(ideaId),
      ).also { explorePlanProposals[proposalId] = it }
    }
    val overview = overviews[projectId]
    if (overview != null && overview.pendingProposals.none { it.proposalId == proposal.proposalId }) {
      overviews[projectId] = overview.copy(pendingProposals = overview.pendingProposals + proposal)
    }
    return ExplorePlanSelection(selectedIdeaId = ideaId, checkpointProposal = proposal)
  }

  // --- ADR-060: unified guidance stand-in ---
  //
  // guidanceRouter simulates the real backend's Response Planner for tests/previews ONLY - this
  // class is never the production source (see class doc above). Tests that need an exact outcome
  // should set this directly rather than relying on the keyword default, which exists only so
  // demo/preview call sites get plausible behavior without configuring anything.
  var guidanceRouter: suspend (projectId: String, userRequest: String, investigationSessionId: String?) -> ProjectGuidanceOutcome =
      { projectId, userRequest, investigationSessionId -> defaultGuidanceRouting(projectId, userRequest, investigationSessionId) }

  override suspend fun getProjectGuidance(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
      idempotencyKey: String,
  ): ProjectGuidanceOutcome {
    require(overviews.containsKey(projectId))
    return guidanceRouter(projectId, userRequest, investigationSessionId)
  }

  private suspend fun defaultGuidanceRouting(
      projectId: String,
      userRequest: String,
      investigationSessionId: String?,
  ): ProjectGuidanceOutcome {
    val normalized = userRequest.trim().lowercase()
    val planningTerms = listOf("idea", "warmer", "modern", "direction", "design", "plan", "layout")
    val followUpTerms = listOf("why", "explain", "status", "where did we", "what's the")
    val diagnosticTerms = listOf("wrong", "check", "broken", "stuck", "sticking", "won't", "start", "fix", "leak")
    return when {
      normalized.isBlank() -> ProjectGuidanceOutcome.NeedsClarification("Could you share a bit more detail?")
      planningTerms.any { it in normalized } -> {
        when (val created = createExplorePlan(projectId, userRequest, "guidance-derived")) {
          is ExplorePlanCreateResult.Ready -> ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.ExplorePlan(created.result))
          is ExplorePlanCreateResult.InformationRequest -> ProjectGuidanceOutcome.NeedsClarification(created.prompt)
        }
      }
      followUpTerms.any { it in normalized } -> ProjectGuidanceOutcome.Ready(
          ProjectGuidanceResult.GeneralGuidance(
              answer = "Mock general guidance answer for: $userRequest",
              uncertain = false,
          ),
      )
      investigationSessionId != null && diagnosticTerms.any { it in normalized } ->
          ProjectGuidanceOutcome.Ready(ProjectGuidanceResult.TroubleshootEvidence(resultId = "mock-troubleshoot-evidence-1"))
      diagnosticTerms.any { it in normalized } -> ProjectGuidanceOutcome.Ready(
          ProjectGuidanceResult.TroubleshootText(
              diagnosis = "Mock diagnosis for: $userRequest",
              recommendedNextAction = "Mock recommended next action.",
              uncertain = false,
          ),
      )
      else -> ProjectGuidanceOutcome.Ready(
          ProjectGuidanceResult.GeneralGuidance(
              answer = "Mock general guidance answer for: $userRequest",
              uncertain = false,
          ),
      )
    }
  }
}
