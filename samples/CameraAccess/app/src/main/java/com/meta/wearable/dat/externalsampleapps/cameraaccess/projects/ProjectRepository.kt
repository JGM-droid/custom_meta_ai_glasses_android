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
}

/** Production repository: reads real Project Memory data from the FastAPI backend. */
class HttpUrlProjectRepository(
    private val api: ProjectApi = HttpUrlProjectApi(InvestigationBackendConfig.resolveBaseUrl()),
) : ProjectRepository {
  override suspend fun listProjects(): List<ProjectSummary> = api.listProjects()

  override suspend fun getProjectOverview(projectId: String): ProjectOverview =
      api.getProjectOverview(projectId)
}

/**
 * Local, in-memory project state - kept only for isolated tests/Compose previews where hitting a
 * real backend isn't appropriate. This was the production source for the earlier "Project A
 * state != Project B state" navigation-shell proof; that proof is complete, and
 * HttpUrlProjectRepository is now the production source for Projects Home.
 */
class MockProjectRepository : ProjectRepository {
  private val overviews: Map<String, ProjectOverview> =
      listOf(
              ProjectOverview(
                  project = ProjectSummary(
                      projectId = "upstairs-ac-repair",
                      name = "Upstairs AC Repair",
                      status = "active",
                  ),
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

  override suspend fun listProjects(): List<ProjectSummary> = overviews.values.map { it.project }

  override suspend fun getProjectOverview(projectId: String): ProjectOverview =
      overviews[projectId] ?: throw NoSuchElementException("No mock project state for $projectId")
}
