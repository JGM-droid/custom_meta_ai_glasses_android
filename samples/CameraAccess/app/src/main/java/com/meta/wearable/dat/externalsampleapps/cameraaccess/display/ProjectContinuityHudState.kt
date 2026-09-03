/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview

internal enum class ProjectHudDestination {
  OVERVIEW,
  DETAILS,
}

internal enum class ProjectHudPhoneDestination {
  PROJECT_DETAIL,
  PROJECT_REVIEW,
  // Evidence was accepted (a HUD Capture -> Use completed) during the CURRENT glasses Project
  // session - see ProjectContinuityHudStateMachine.evidenceAcceptedThisSession, set synchronously
  // by captureAccepted(), never by the Investigation ViewModel's asynchronous eligibility push
  // (proven physically unreliable to depend on for this decision - see phoneHandoff()'s doc). The
  // phone must land directly on that in-progress investigation UI, not a generic Project screen
  // the user then has to navigate away from to find their own just-captured photos. Takes
  // priority over PROJECT_REVIEW (see phoneHandoff()'s doc) since it reflects what the user just
  // did on THIS device, in THIS sitting.
  ACTIVE_INVESTIGATION,
}

internal data class ProjectHudPhoneHandoff(
    val projectId: String,
    val destination: ProjectHudPhoneDestination,
)

internal data class ProjectHudContent(
    val projectId: String,
    val projectName: String,
    val whereWeLeftOff: String?,
    val nextAction: String?,
    val evidenceCount: Int,
    val latestGuidance: String?,
    val attentionSummary: String?,
    val pendingTrustReview: ProjectHudPendingTrustReview? = null,
) {
  val isEmpty: Boolean
    get() =
        whereWeLeftOff == null &&
            nextAction == null &&
            evidenceCount == 0 &&
            latestGuidance == null &&
            attentionSummary == null

  val hasAdditionalDetails: Boolean
    get() = evidenceCount > 0 || latestGuidance != null
}

/**
 * An AI analysis result the canonical backend already holds for this Project that no trust
 * decision has been recorded against yet - i.e. [com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview.trustDecision]
 * is null. Presented as a HUD decision point (see [ProjectContinuityHudController]'s
 * trustReviewScreen) rather than folded into [ProjectHudContent.latestGuidance], which keeps
 * showing the same hypothesis text even after it has been decided on. [sessionId] is what a trust
 * decision submits against - the same Investigation session this hypothesis came from.
 */
internal data class ProjectHudPendingTrustReview(
    val sessionId: String,
    val hypothesis: String,
    val recommendedNextAction: String,
)

/** The three trust actions the roadmap requires - see docs/ROADMAP.md's Glasses foundation. */
internal enum class ProjectHudTrustAction {
  KEEP_AS_HYPOTHESIS,
  ADD_EVIDENCE,
  RETURN,
}

internal sealed interface ProjectHudUiState {
  val projectId: String
  val projectName: String

  data class Loading(
      override val projectId: String,
      override val projectName: String,
  ) : ProjectHudUiState

  data class Ready(
      val content: ProjectHudContent,
      val destination: ProjectHudDestination = ProjectHudDestination.OVERVIEW,
  ) : ProjectHudUiState {
    override val projectId: String = content.projectId
    override val projectName: String = content.projectName
  }

  data class Stale(
      val content: ProjectHudContent,
      val message: String,
  ) : ProjectHudUiState {
    override val projectId: String = content.projectId
    override val projectName: String = content.projectName
  }

  data class Disconnected(
      override val projectId: String,
      override val projectName: String,
  ) : ProjectHudUiState

  data class Error(
      override val projectId: String,
      override val projectName: String,
      val message: String,
  ) : ProjectHudUiState
}

internal data class ProjectHudLoadRequest(
    val projectId: String,
    val projectName: String,
    val token: Long,
)

/**
 * Transient, presentation-only status for a HUD-triggered capture request. Independent of
 * [ProjectHudUiState] because a capture attempt overlays the current Ready/Stale content rather
 * than replacing it - the last loaded Project summary must stay visible while capture is in
 * flight or has just failed. Never retried automatically: [Failed] only ever clears through a new
 * explicit user tap (see [ProjectContinuityHudStateMachine.acceptCapture]).
 */
internal sealed interface ProjectHudCaptureStatus {
  data object Idle : ProjectHudCaptureStatus

  data object Capturing : ProjectHudCaptureStatus

  /**
   * A photo was captured and is held pending on the phone (not yet appended to Investigation
   * evidence - see [ProjectContinuityHudController]'s onUseRequested doc). Resolves only through
   * an explicit user tap: [ProjectContinuityHudStateMachine.acceptUse] or
   * [ProjectContinuityHudStateMachine.acceptRetake].
   */
  data object AwaitingConfirmation : ProjectHudCaptureStatus

  data class Failed(val message: String) : ProjectHudCaptureStatus
}

/**
 * Whether Analyze is currently offered, and if not, why - pushed by the session owner from the
 * Investigation ViewModel's own product-state derivation (investigation.deriveInvestigationProductState,
 * reused as-is; never duplicated here). [canAnalyze] alone drives whether the Analyze action is
 * tappable (unchanged gate); [hasEvidence]/[hasExplanation] exist only so the HUD can explain a
 * `false` [canAnalyze] instead of silently omitting Analyze - the proven gap where evidence exists
 * (a HUD Capture -> Use just happened) but explanation does not (the glasses have no free-text/
 * voice input surface; only the phone's existing Investigation panel does).
 */
internal data class ProjectHudAnalysisEligibility(
    val canAnalyze: Boolean = false,
    val hasEvidence: Boolean = false,
    val hasExplanation: Boolean = false,
)

/**
 * Transient, presentation-only status shared by both halves of the Analyze lifecycle: starting an
 * analysis (before [ProjectHudPendingTrustReview] exists) and submitting a trust decision against
 * one (after it exists) - see [ProjectContinuityHudController]'s render precedence doc for how the
 * same three states are interpreted differently in each phase. Independent of [ProjectHudUiState]
 * for the same reason [ProjectHudCaptureStatus] is: it overlays current content rather than
 * replacing it. [Failed] never clears itself - only a new explicit tap does.
 */
internal sealed interface ProjectHudAnalysisStatus {
  data object Idle : ProjectHudAnalysisStatus

  data object Working : ProjectHudAnalysisStatus

  data class Failed(val message: String) : ProjectHudAnalysisStatus
}

/**
 * Pure state machine for the read-only HUD. It owns no Project persistence and performs no
 * network or DAT calls, which makes identity/race/callback behavior deterministic to test.
 */
internal class ProjectContinuityHudStateMachine {
  var uiState: ProjectHudUiState? = null
    private set

  var renderGeneration: Long = 0
    private set

  var captureStatus: ProjectHudCaptureStatus = ProjectHudCaptureStatus.Idle
    private set

  var analysisEligibility: ProjectHudAnalysisEligibility = ProjectHudAnalysisEligibility()
    private set

  // Deterministic Continue-on-phone signal - see ProjectHudPhoneDestination.ACTIVE_INVESTIGATION's
  // doc. Set synchronously by captureAccepted() (a real Use just completed) - never by the
  // Investigation ViewModel's asynchronous analysisEligibility push, which phoneHandoff() used to
  // depend on and which proved unreliable to time against a real physical Continue-on-phone tap.
  // Reset in selectProject() - the same explicit-Project boundary every other per-Project field
  // here already resets at - so it can never leak across Projects or across a torn-down/reattached
  // session for the SAME Project (stopStream() clears StreamViewModel's own project marker, which
  // forces a fresh selectProject() on the next attach - see StreamViewModel.configureProjectHud).
  var evidenceAcceptedThisSession: Boolean = false
    private set

  var analysisStatus: ProjectHudAnalysisStatus = ProjectHudAnalysisStatus.Idle
    private set

  private var selectedProjectId: String? = null
  private var selectedProjectName: String? = null
  private var requestToken: Long = 0
  private var consumedActions = mutableSetOf<String>()
  private var lastReadyContent: ProjectHudContent? = null

  fun selectProject(projectId: String, projectName: String): ProjectHudLoadRequest {
    require(projectId.isNotBlank()) { "The HUD requires an explicit project_id." }
    if (selectedProjectId != projectId) lastReadyContent = null
    selectedProjectId = projectId
    selectedProjectName = projectName
    // A newly selected explicit Project can never inherit a capture/analysis status left over
    // from whichever Project (or no Project) the HUD was previously attached to.
    captureStatus = ProjectHudCaptureStatus.Idle
    analysisEligibility = ProjectHudAnalysisEligibility()
    analysisStatus = ProjectHudAnalysisStatus.Idle
    evidenceAcceptedThisSession = false
    uiState = ProjectHudUiState.Loading(projectId, projectName)
    advanceRender()
    return nextRequest(projectId, projectName)
  }

  fun refresh(reconnecting: Boolean = false): ProjectHudLoadRequest? {
    val projectId = selectedProjectId ?: return null
    val projectName = selectedProjectName ?: return null
    val retainedContent = (uiState as? ProjectHudUiState.Ready)?.content ?: lastReadyContent
    if (retainedContent != null) {
      uiState =
          ProjectHudUiState.Stale(
              retainedContent,
              if (reconnecting) "Reconnecting — checking current Project state"
              else "Refreshing current Project state",
          )
    } else {
      uiState = ProjectHudUiState.Loading(projectId, projectName)
    }
    advanceRender()
    return nextRequest(projectId, projectName)
  }

  fun accept(request: ProjectHudLoadRequest, overview: ProjectOverview): Boolean {
    if (!isCurrent(request) || overview.project.projectId != request.projectId) return false
    val content = mapOverview(overview, request.projectName)
    lastReadyContent = content
    uiState = ProjectHudUiState.Ready(content)
    advanceRender()
    return true
  }

  fun fail(request: ProjectHudLoadRequest, message: String): Boolean {
    if (!isCurrent(request)) return false
    val stale = uiState as? ProjectHudUiState.Stale
    uiState =
        stale?.copy(message = "Refresh failed — showing the last loaded Project state")
            ?: ProjectHudUiState.Error(
                request.projectId,
                request.projectName,
                message.ifBlank { "Project state is unavailable." },
            )
    advanceRender()
    return true
  }

  fun disconnected() {
    val projectId = selectedProjectId ?: return
    val projectName = selectedProjectName ?: return
    // Whatever capture/analysis was in flight (or had just failed) belonged to the connection
    // that just dropped - reconnecting must not resurrect a stale "Capturing..."/"Working..."/
    // failure banner nothing will ever resolve. analysisEligibility is deliberately left alone: it
    // is an availability signal from the Investigation ViewModel, not a request in flight, and a
    // fresh render() once reconnected will pick up whatever it is by then anyway.
    captureStatus = ProjectHudCaptureStatus.Idle
    analysisStatus = ProjectHudAnalysisStatus.Idle
    uiState = ProjectHudUiState.Disconnected(projectId, projectName)
    advanceRender()
  }

  fun showDetails(generation: Long): Boolean {
    if (!acceptAction(generation, "details")) return false
    val ready = uiState as? ProjectHudUiState.Ready ?: return false
    uiState = ready.copy(destination = ProjectHudDestination.DETAILS)
    advanceRender()
    return true
  }

  fun showOverview(generation: Long): Boolean {
    if (!acceptAction(generation, "back")) return false
    val ready = uiState as? ProjectHudUiState.Ready ?: return false
    uiState = ready.copy(destination = ProjectHudDestination.OVERVIEW)
    advanceRender()
    return true
  }

  /**
   * ACTIVE_INVESTIGATION takes priority over PROJECT_REVIEW: evidence accepted THIS sitting
   * ([evidenceAcceptedThisSession] - set synchronously by [captureAccepted], never from the
   * Investigation ViewModel's asynchronous eligibility push, which this decision used to depend
   * on and proved unreliable to time against a real physical Continue-on-phone tap) has nowhere
   * else to go but the phone yet; a pending trust review, by contrast, was already saved to the
   * canonical Project and will still be there whichever screen the phone opens on first. The two
   * are not expected to coexist in practice (a fresh selectProject() - which a torn-down/
   * reattached session for the same Project always forces - resets evidenceAcceptedThisSession
   * before an OLDER pending review would ever be visible again), but if they ever did, showing
   * the user their own just-captured photos first is the more useful default.
   */
  fun phoneHandoff(generation: Long): ProjectHudPhoneHandoff? {
    if (!acceptAction(generation, "phone")) return null
    val projectId = selectedProjectId ?: return null
    return ProjectHudPhoneHandoff(
        projectId = projectId,
        destination =
            if (evidenceAcceptedThisSession) {
              ProjectHudPhoneDestination.ACTIVE_INVESTIGATION
            } else if (lastReadyContent?.attentionSummary != null) {
              ProjectHudPhoneDestination.PROJECT_REVIEW
            } else {
              ProjectHudPhoneDestination.PROJECT_DETAIL
            },
    )
  }

  fun acceptRefresh(generation: Long): ProjectHudLoadRequest? {
    if (!acceptAction(generation, "refresh")) return null
    return refresh()
  }

  /**
   * Accepts one HUD-triggered capture tap. Duplicate-press safe the same way [acceptAction]
   * already guards Refresh/Details/Back: a second tap at the same [generation], or any tap while
   * a capture is already in flight, is ignored rather than queued. Returns false in either case
   * so the controller performs no side effect.
   */
  fun acceptCapture(generation: Long): Boolean {
    if (!acceptAction(generation, "capture")) return false
    if (captureStatus == ProjectHudCaptureStatus.Capturing) return false
    captureStatus = ProjectHudCaptureStatus.Capturing
    advanceRender()
    return true
  }

  /**
   * Called once the in-flight capture this HUD requested has completed successfully. Moves to
   * [ProjectHudCaptureStatus.AwaitingConfirmation] rather than back to Idle - the captured photo
   * stays pending until the user explicitly taps Use or Retake (see those methods below).
   */
  fun captureSucceeded(): Boolean {
    if (captureStatus !is ProjectHudCaptureStatus.Capturing) return false
    captureStatus = ProjectHudCaptureStatus.AwaitingConfirmation
    advanceRender()
    return true
  }

  /**
   * Called when either the capture itself failed, or a confirmed Use could not be applied (e.g.
   * the Investigation's 5-photo capacity filled between capture and Use). Surfaces the failure
   * honestly next to the existing content rather than retrying automatically - the user must tap
   * Capture again (a fresh [acceptCapture] at the new render generation) to retry.
   */
  fun captureFailed(message: String): Boolean {
    if (captureStatus !is ProjectHudCaptureStatus.Capturing &&
        captureStatus !is ProjectHudCaptureStatus.AwaitingConfirmation
    ) {
      return false
    }
    captureStatus = ProjectHudCaptureStatus.Failed(message.ifBlank { "Capture failed." })
    advanceRender()
    return true
  }

  /**
   * Accepts one Use tap while a captured photo is pending confirmation. Duplicate-press safe like
   * [acceptCapture]. Deliberately does not change [captureStatus] itself - appending the pending
   * photo to Investigation evidence is a local operation the session owner performs (see
   * [ProjectContinuityHudController]'s onUseRequested), which then reports back through
   * [captureAccepted] or [captureFailed].
   */
  fun acceptUse(generation: Long): Boolean {
    if (!acceptAction(generation, "use")) return false
    return captureStatus is ProjectHudCaptureStatus.AwaitingConfirmation
  }

  /** Called once the pending photo this HUD's Use tap requested has been added as evidence. */
  fun captureAccepted(): Boolean {
    if (captureStatus !is ProjectHudCaptureStatus.AwaitingConfirmation) return false
    captureStatus = ProjectHudCaptureStatus.Idle
    // The deterministic Continue-on-phone signal - see its own doc. Set here, synchronously,
    // rather than waiting for the Investigation ViewModel's asynchronous eligibility push.
    evidenceAcceptedThisSession = true
    advanceRender()
    return true
  }

  /**
   * Accepts one Retake tap while a captured photo is pending confirmation. Unlike Use, discarding
   * a pending photo is purely local and cannot fail - it moves straight back to Idle so the
   * Capture action is immediately available again, with no evidence slot consumed.
   */
  fun acceptRetake(generation: Long): Boolean {
    if (!acceptAction(generation, "retake")) return false
    if (captureStatus !is ProjectHudCaptureStatus.AwaitingConfirmation) return false
    captureStatus = ProjectHudCaptureStatus.Idle
    advanceRender()
    return true
  }

  /**
   * Pushed by the session owner whenever the Investigation ViewModel's own eligibility signal
   * changes - see [ProjectHudAnalysisEligibility]'s doc. Only a genuine change triggers a render;
   * this is an availability signal, not a user action, so it is not generation-guarded like
   * [acceptAction]-backed methods.
   */
  fun setAnalysisEligibility(eligibility: ProjectHudAnalysisEligibility): Boolean {
    if (analysisEligibility == eligibility) return false
    analysisEligibility = eligibility
    advanceRender()
    return true
  }

  /**
   * Accepts one HUD-triggered Analyze tap. Only valid while analysis is actually offered
   * ([ProjectHudAnalysisEligibility.canAnalyze]) and nothing is already Working - duplicate-press
   * safe the same way [acceptCapture] is.
   */
  fun acceptAnalyze(generation: Long): Boolean {
    if (!acceptAction(generation, "analyze")) return false
    if (!analysisEligibility.canAnalyze || analysisStatus is ProjectHudAnalysisStatus.Working) return false
    analysisStatus = ProjectHudAnalysisStatus.Working
    advanceRender()
    return true
  }

  /**
   * Accepts one trust-decision tap while a completed analysis is awaiting one -
   * [ProjectHudContent.pendingTrustReview] on the current Ready content. Returns the session_id to
   * submit the decision against, or null if there is nothing to decide on (defense against a stale
   * tap - e.g. content moved on) or one is already being submitted. Mirrors [phoneHandoff] in
   * returning the payload directly rather than a bare Boolean.
   */
  fun acceptTrustDecision(generation: Long, action: ProjectHudTrustAction): String? {
    if (!acceptAction(generation, "trust:${action.name}")) return null
    val pending = (uiState as? ProjectHudUiState.Ready)?.content?.pendingTrustReview ?: return null
    if (analysisStatus is ProjectHudAnalysisStatus.Working) return null
    analysisStatus = ProjectHudAnalysisStatus.Working
    advanceRender()
    return pending.sessionId
  }

  /**
   * Called once a HUD-requested Analyze or trust decision has finished successfully. Both simply
   * return to Idle - the actual result (a fresh [ProjectHudPendingTrustReview], or its absence
   * once a trust decision has been recorded) arrives through the canonical Project refresh the
   * controller performs right after calling this, not through this method itself. That refresh is
   * what makes "Project state should refresh after a validated change" true without this state
   * machine needing to know anything about Investigation internals.
   */
  fun analysisSucceeded(): Boolean {
    if (analysisStatus !is ProjectHudAnalysisStatus.Working) return false
    analysisStatus = ProjectHudAnalysisStatus.Idle
    advanceRender()
    return true
  }

  /**
   * Called when either an Analyze attempt or a trust-decision submission failed. Shared with
   * [captureFailed]'s same reasoning: honest failure next to existing content, no automatic retry
   * - the next attempt is always a fresh explicit tap.
   */
  fun analysisFailed(message: String): Boolean {
    if (analysisStatus !is ProjectHudAnalysisStatus.Working) return false
    analysisStatus = ProjectHudAnalysisStatus.Failed(message.ifBlank { "Analyze failed." })
    advanceRender()
    return true
  }

  fun phoneActionLabel(): String =
      if (lastReadyContent?.attentionSummary != null) "Review on phone" else "Continue on phone"

  private fun isCurrent(request: ProjectHudLoadRequest): Boolean =
      request.token == requestToken && request.projectId == selectedProjectId

  private fun nextRequest(projectId: String, projectName: String): ProjectHudLoadRequest {
    requestToken += 1
    return ProjectHudLoadRequest(projectId, projectName, requestToken)
  }

  private fun acceptAction(generation: Long, action: String): Boolean {
    if (generation != renderGeneration) return false
    return consumedActions.add("$generation:$action")
  }

  private fun advanceRender() {
    renderGeneration += 1
    consumedActions = mutableSetOf()
  }

  companion object {
    fun mapOverview(overview: ProjectOverview, fallbackProjectName: String): ProjectHudContent {
      val proposalCount = overview.pendingProposals.size
      val attention =
          when {
            proposalCount == 1 -> "1 suggested Project change is waiting for review on your phone."
            proposalCount > 1 -> "$proposalCount suggested Project changes are waiting for review on your phone."
            else -> null
          }
      val investigation = overview.latestInvestigation
      val latestGuidance =
          investigation?.hypothesis?.trim()?.takeIf(String::isNotEmpty)?.let {
            // A trust decision records the user's assessment of an inference; it does not make
            // the inferred RESULT a canonically confirmed fact. SavedInvestigationReview does
            // not currently expose confirmation_status, so the HUD must retain the honest
            // unconfirmed label for every AI hypothesis it projects.
            "AI suggestion — unconfirmed: $it"
          }
      // A HUD decision point only while no trust decision has been recorded yet - once one has,
      // this naturally disappears on the next refresh without the HUD needing to track "already
      // decided" itself; it is simply reading the same canonical field the phone already does.
      val pendingTrustReview =
          investigation?.takeIf { it.trustDecision == null }?.let {
            ProjectHudPendingTrustReview(
                sessionId = it.sessionId,
                hypothesis = it.hypothesis,
                recommendedNextAction = it.recommendedNextAction,
            )
          }
      return ProjectHudContent(
          projectId = overview.project.projectId,
          projectName = overview.project.name.ifBlank { fallbackProjectName },
          whereWeLeftOff = overview.checkpoint.whereWeLeftOff?.trim()?.takeIf(String::isNotEmpty),
          nextAction = overview.checkpoint.nextAction?.trim()?.takeIf(String::isNotEmpty),
          evidenceCount = investigation?.evidenceCount ?: 0,
          latestGuidance = latestGuidance,
          attentionSummary = attention,
          pendingTrustReview = pendingTrustReview,
      )
    }
  }
}
