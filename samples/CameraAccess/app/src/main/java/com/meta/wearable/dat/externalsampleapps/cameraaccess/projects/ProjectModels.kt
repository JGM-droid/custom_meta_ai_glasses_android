/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectModels - Project Assistant data model
//
// Mirrors the real backend Project Memory contract (see
// C:\Dev\Projects\CustomMetaAIGlasses\custom_meta_ai_glasses\code\prototype_v1\projects\models.py
// and docs/PROJECT_MEMORY_ARCHITECTURE.md): Project identity/metadata, Checkpoint = current
// state, Activity = history, kept as three distinct types rather than one flat blob of strings.
//
// ProjectSummary.projectId is the backend's own canonical project_id (a UUID string) - the ONLY
// project identity this app carries anywhere, through navigation and through the repository
// lookup alike. There is no separate Android-only project id.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

/**
 * Project identity/metadata - who this project is, not what state it's in. Carried through
 * AppRoot navigation (see TopLevelScreen.ProjectDetail/ProjectWorkspace) as the single source of
 * project identity.
 */
data class ProjectSummary(
    val projectId: String,
    val name: String,
    val status: String,
)

/**
 * Current project state - mirrors the backend Project "checkpoint" concept
 * (checkpoint.current_work / checkpoint.next_action). Nullable: the backend allows these to be
 * genuinely unset, and the UI renders an honest empty state rather than blank/fake text.
 */
data class ProjectCheckpoint(
    val whereWeLeftOff: String?,
    val nextAction: String?,
    val blockers: String? = null,
)

/** One entry in a project's activity/history - distinct from checkpoint state. */
data class ProjectActivityEntry(
    val summary: String,
)

data class SavedInvestigationReview(
    val sessionId: String,
    val projectId: String,
    val status: String,
    val completedAtUtc: String,
    val evidenceCount: Int,
    val explanation: String?,
    val hypothesis: String,
    val recommendedNextAction: String,
    val trustDecision: String?,
    val proposalId: String?,
    val proposalStatus: String?,
    val followUpSessionId: String?,
    val retainedImage: ByteArray?,
)

data class CheckpointProposalReview(
    val proposalId: String,
    val projectId: String,
    val status: String,
    val reason: String,
    val proposedFields: Map<String, String?>,
    val sourceActivityIds: List<String> = emptyList(),
)

/** The full Project Overview: identity + current state + history, kept as separate fields. */
data class ProjectOverview(
    val project: ProjectSummary,
    val checkpoint: ProjectCheckpoint,
    val recentActivity: List<ProjectActivityEntry>,
    val revision: Int = 0,
    val latestInvestigation: SavedInvestigationReview? = null,
    val pendingProposals: List<CheckpointProposalReview> = emptyList(),
    val investigationLoadError: String? = null,
    /** Bounded HUD-relevant summary of this Project's most recent EXPLORE_PLAN interaction, if
     * any (see [display.ProjectContinuityHudState]'s ProjectHudContent.explorePlanSummary). Null
     * when no complete EXPLORE_PLAN exists yet for this Project - HUD content is unaffected. */
    val latestExplorePlan: ExplorePlanOverviewSummary? = null,
)

/**
 * The three HUD-relevant EXPLORE_PLAN states, derived deterministically from the backend's own
 * canonical Explore read projection plus this Project's existing [pendingProposals] - never a
 * separate Android-owned notion of selection/apply state.
 */
data class ExplorePlanOverviewSummary(
    val resultId: String,
    val recommendedOptionTitle: String?,
    val selectedOptionTitle: String?,
    val hasPendingSelectionProposal: Boolean,
)

data class ProjectProgressCheckpointPatch(
    val currentWork: String? = null,
    val blockers: String? = null,
    val nextAction: String? = null,
)

data class ProjectProgressRequest(
    val idempotencyKey: String,
    val summary: String,
    val details: String?,
    val expectedProjectRevision: Int,
    val checkpointPatch: ProjectProgressCheckpointPatch?,
)

data class ProjectProgressPreview(
    val projectId: String,
    val idempotencyKey: String,
    val summary: String,
    val details: String?,
    val baseProjectRevision: Int,
    val effectiveCheckpointPatch: ProjectProgressCheckpointPatch?,
    val proposalRequired: Boolean,
)

data class ProjectProgressSaveResult(
    val projectId: String,
    val idempotencyKey: String,
    val reconstructed: Boolean,
)

/**
 * A new Project to create - mirrors the backend's ProjectCreateRequest exactly (name/goal
 * required, checkpoint.current_objective/next_action optional). No other backend
 * ProjectCreateRequest/ProjectCheckpoint fields are exposed here - the create form is
 * deliberately minimal.
 */
data class NewProjectRequest(
    val name: String,
    val goal: String,
    val currentObjective: String? = null,
    val nextAction: String? = null,
)

/**
 * A grounded answer from POST /projects/{project_id}/ask - mirrors ProjectGroundedAnswerResponse.
 * project_id/project_name/question are deliberately NOT carried here: the caller already knows
 * the canonical project_id it asked (see ProjectRepository.askProject) and tracks the question
 * text itself, so echoing the backend's copies back would just be a second, redundant identity.
 * `answer` is the only field the primary Workspace UI renders; the rest is preserved for a
 * possible future debug/Details view but must never dominate the normal product experience (see
 * the Project-Aware Ask slice - the UI must never surface raw backend terms like "grounding
 * status" or "question class" in the main answer presentation).
 */
data class ProjectAskAnswer(
    val answer: String,
    val questionClass: String,
    val groundingStatus: String,
    val insufficientContext: Boolean,
    val uncertaintyNote: String?,
    val referenceSummaries: List<String>,
    val provider: String?,
    val providerModel: String?,
    val modelCallCount: Int,
)

enum class ConversationRole { USER, ASSISTANT }

enum class ConversationTurnStatus { PROCESSING, COMPLETED, FAILED }

data class ConversationEvidenceReference(
    val evidenceId: String,
    val investigationSessionId: String,
)

/**
 * Phase 3B: pointer to a generated VisualArtifact already referenced from an assistant turn -
 * exactly the same (result_id, option_id, artifact_id) identity the existing VisualArtifact HTTP
 * routes already use. Never carries image bytes itself.
 */
data class ConversationVisualArtifactReference(
    val projectAiResultId: String,
    val optionId: String,
    val artifactId: String,
)

data class ConversationTurn(
    val turnId: String,
    val projectId: String,
    val sequenceNumber: Int,
    val role: ConversationRole,
    val status: ConversationTurnStatus,
    val text: String,
    val evidenceRefs: List<ConversationEvidenceReference>,
    val idempotencyKey: String,
    val visualArtifactRef: ConversationVisualArtifactReference? = null,
)

data class ProjectConversation(
    val conversationId: String,
    val projectId: String,
    val turns: List<ConversationTurn>,
)

data class ConversationSendResult(
    val conversationId: String,
    val projectId: String,
    val turns: List<ConversationTurn>,
    val reconstructed: Boolean,
)

data class ProjectIdeaOption(
    val ideaId: String,
    val ordinal: Int,
    val summary: String,
    val details: String?,
    val disposition: String?,
    val promoted: Boolean,
)

data class ProjectIdeasProjection(
    val projectId: String,
    val options: List<ProjectIdeaOption>,
    val preferredIdeaId: String?,
)

sealed interface ProjectIdeasExecutionResult {
  data class Options(val projection: ProjectIdeasProjection) : ProjectIdeasExecutionResult
  data class InformationRequest(val prompt: String) : ProjectIdeasExecutionResult
}

// --- Rich Project Intelligence V1 (ADR-059): EXPLORE_PLAN typed contract ---
//
// Mirrors the backend's ProjectAIResult/ProjectExploreGroupView/ProjectExploreOptionView exactly
// (see code/prototype_v1/projects/models.py) - every field below is read from a typed backend JSON
// field (option.summary/rationale/tradeoffs/concept/proposed_changes/estimated_cost,
// group.observations/recommended_ordinal/recommendation_reason/next_steps/follow_up_questions).
// NONE of it is parsed from idea.details - that field is backend-private persistence encoding
// (a reserved-delimiter-joined string) and must never be read by this app. This is a deliberately
// separate, new model family from ProjectIdeaOption/ProjectIdeasProjection above (the legacy
// OPTION_SET-only Ideas panel, which does read idea.details and is left untouched) - EXPLORE_PLAN
// evolves the same backend Explore service but is consumed here through the new
// /ai-results/explore-plan envelope, not /interactions/explore.

data class ExplorePlanEstimatedCost(
    val currency: String,
    val minAmount: Double?,
    val maxAmount: Double?,
    val qualifier: String?,
)

data class ExplorePlanOption(
    val ideaId: String,
    val ordinal: Int,
    val title: String,
    val summary: String,
    val rationale: String?,
    val tradeoffs: String?,
    val concept: String?,
    val proposedChanges: String?,
    val estimatedCost: ExplorePlanEstimatedCost?,
    val recommended: Boolean,
    val disposition: String?,
)

/** One EXPLORE_PLAN interaction - a Room Redesign-style rich option set. */
data class ExplorePlanResult(
    val projectId: String,
    val resultId: String,
    val complete: Boolean,
    val title: String?,
    val summary: String?,
    val hudHeadline: String,
    val hudNext: String?,
    val hudUncertain: Boolean,
    val observations: List<String>,
    val recommendedOrdinal: Int?,
    val recommendationReason: String?,
    val nextSteps: List<String>,
    val followUpQuestions: List<String>,
    val options: List<ExplorePlanOption>,
) {
  val recommendedOption: ExplorePlanOption?
    get() = recommendedOrdinal?.let { ordinal -> options.firstOrNull { it.ordinal == ordinal } }
}

sealed interface ExplorePlanCreateResult {
  data class Ready(val result: ExplorePlanResult) : ExplorePlanCreateResult
  data class InformationRequest(val prompt: String) : ExplorePlanCreateResult
}

/**
 * The result of an explicit SELECT disposition on one EXPLORE_PLAN option. [checkpointProposal]
 * is non-null exactly when the backend derived/reconstructed a revision-bound CheckpointProposal
 * for this selection (see projects/project_explore.py's _selection_proposal) - Android never
 * builds this proposal itself, it only ever displays what the backend already created.
 */
data class ExplorePlanSelection(
    val selectedIdeaId: String,
    val checkpointProposal: CheckpointProposalReview?,
)

enum class VisualArtifactStatus { PENDING, READY, FAILED }

data class VisualArtifact(
    val artifactId: String,
    val projectId: String,
    val resultId: String,
    val optionId: String,
    val status: VisualArtifactStatus,
    val sourceEvidenceIds: List<String>,
    val retention: String,
    val failureCategory: String?,
)

data class VisualArtifactImages(
    val source: ByteArray,
    val visualization: ByteArray,
)

// --- ADR-060: bounded intelligent Project guidance routing ---
//
// One natural interaction - Project context + current evidence + a typed/spoken request - POSTs
// to the backend's unified /projects/{project_id}/ai-results endpoint. The BACKEND'S Response
// Planner infers which of TROUBLESHOOT | EXPLORE_PLAN | GENERAL_GUIDANCE applies; Android never
// exposes these family names to the user and never chooses between them itself. This sealed
// result mirrors exactly the backend's ProjectAIResult typed payload union (see
// code/prototype_v1/projects/models.py's ProjectAIResult) - one variant per family, plus a
// distinct evidence-backed vs. text-only split within TROUBLESHOOT (ProjectAIResult.troubleshoot
// vs. troubleshoot_text).

sealed interface ProjectGuidanceResult {
  /** result_type=EXPLORE_PLAN. Reuses the existing rich 3-option ExplorePlanResult/ExplorePlanPanel
   * unchanged - Get Guidance only decides that this Project request should be answered this way. */
  data class ExplorePlan(val result: ExplorePlanResult) : ProjectGuidanceResult

  /** result_type=TROUBLESHOOT with ProjectAIResult.troubleshoot set - the backend actually ran the
   * existing Investigation session/evidence pipeline for this request. Android does not render
   * this payload directly; it reloads so the existing SavedInvestigationSection/trust UI (Looks
   * right / Add more info / Not quite), already driven by ProjectOverview.latestInvestigation,
   * picks it up the same way it already does for any completed Investigation. */
  data class TroubleshootEvidence(val resultId: String) : ProjectGuidanceResult

  /** result_type=TROUBLESHOOT with ProjectAIResult.troubleshoot_text set (ADR-060 Path B) - no
   * Investigation session/evidence existed for this request, so the backend answered from Project
   * context + the typed request alone. Always inferred/unconfirmed; evidenceRefs is always empty;
   * there is no trust/persistence action for this ephemeral result because the backend created
   * nothing durable to confirm against. */
  data class TroubleshootText(
      val diagnosis: String,
      val recommendedNextAction: String,
      val uncertain: Boolean,
  ) : ProjectGuidanceResult

  /** result_type=GENERAL_GUIDANCE - ephemeral, read-only, never a Project mutation of any kind. */
  data class GeneralGuidance(
      val answer: String,
      val uncertain: Boolean,
  ) : ProjectGuidanceResult
}

/**
 * The two outcomes the unified endpoint can return besides a technical failure (which surfaces as
 * a thrown [ProjectApiException] - see ProjectBackendClient.kt). [NeedsClarification] is not an
 * error: the backend genuinely could not determine a response need from what was supplied and
 * asks exactly one concise question rather than guessing a family - Android keeps the user in the
 * same composer and lets them answer/resubmit through the same Get Guidance action.
 */
sealed interface ProjectGuidanceOutcome {
  data class Ready(val result: ProjectGuidanceResult) : ProjectGuidanceOutcome
  data class NeedsClarification(val question: String) : ProjectGuidanceOutcome
}
