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
)

/** One entry in a project's activity/history - distinct from checkpoint state. */
data class ProjectActivityEntry(
    val summary: String,
)

/** The full Project Overview: identity + current state + history, kept as separate fields. */
data class ProjectOverview(
    val project: ProjectSummary,
    val checkpoint: ProjectCheckpoint,
    val recentActivity: List<ProjectActivityEntry>,
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
