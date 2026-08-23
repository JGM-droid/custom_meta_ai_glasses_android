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
