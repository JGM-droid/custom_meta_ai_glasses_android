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
}

/**
 * Local, in-memory project state - kept only for isolated tests/Compose previews where hitting a
 * real backend isn't appropriate. This was the production source for the earlier "Project A
 * state != Project B state" navigation-shell proof; that proof is complete, and
 * HttpUrlProjectRepository is now the production source for Projects Home.
 */
class MockProjectRepository : ProjectRepository {
  private val overviews: MutableMap<String, ProjectOverview> =
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
        checkpoint = ProjectCheckpoint(
            whereWeLeftOff = null,
            nextAction = request.nextAction,
        ),
        recentActivity = emptyList(),
    )
    return summary
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
}
