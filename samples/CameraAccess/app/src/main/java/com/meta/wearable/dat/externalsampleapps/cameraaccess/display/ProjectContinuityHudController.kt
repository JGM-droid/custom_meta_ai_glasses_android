/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import android.util.Log
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.FlexBoxScope
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presentation-only adapter over the existing camera DeviceSession. This class never creates or
 * starts a DeviceSession and never calls a mutating Project endpoint.
 */
internal class ProjectContinuityHudController(
    private val scope: CoroutineScope,
    private val repository: ProjectRepository,
    private val onPhoneHandoff: (ProjectHudPhoneHandoff) -> Unit,
    private val onDisplayError: (String) -> Unit,
    // Fired when the user taps Capture on the HUD. This class never owns the camera Stream or
    // calls its capture API directly (see class doc) - it only requests a capture; the session
    // owner (StreamViewModel) performs it and reports the outcome back via onCaptureSucceeded /
    // onCaptureFailed below, the same way it already owns start/stop of the DeviceSession itself.
    private val onCaptureRequested: () -> Unit,
    // Fired when the user taps Use on a pending captured photo. This class never appends
    // Investigation evidence itself (see class doc) - the owner performs that local append and
    // reports the outcome back via onCaptureAccepted (success) or onCaptureFailed (e.g. the
    // Investigation's 5-photo capacity filled in the meantime).
    private val onUseRequested: () -> Unit,
    // Fired when the user taps Retake on a pending captured photo. Purely a discard signal so the
    // owner can drop its held photo/preview state - it cannot fail, so there is no result callback.
    private val onRetakeRequested: () -> Unit,
    // Fired when the user taps Analyze. This class never calls the Analyze API itself (see class
    // doc) - the owner runs the existing Investigation analysis and reports the outcome back via
    // onAnalysisSucceeded/onAnalysisFailed. The result content itself is never pushed back through
    // this callback pair - it arrives through the canonical Project refresh onAnalysisSucceeded
    // triggers, the same single source of truth every other HUD screen already reads from.
    private val onAnalyzeRequested: () -> Unit,
    // Fired when the user taps one of the three required trust actions (Keep as hypothesis / Add
    // evidence / Return) against the session_id of the pending review currently shown. This class
    // never submits the trust decision itself - the owner does, through the existing Investigation
    // trust-decision capability, and reports back via onAnalysisSucceeded/onAnalysisFailed.
    private val onTrustDecisionRequested: (ProjectHudTrustAction, sessionId: String) -> Unit,
) {
  private companion object {
    private const val TAG = "CameraAccess:ProjectHUD"
    private const val MAX_BODY_CHARS = 180
    private const val LOAD_RETRY_DELAY_MS = 600L
    private const val RENDER_RETRY_DELAY_MS = 500L
  }

  private val lock = Any()
  // internal, not private: the sole seam the Stage 2 acceptance harness (see
  // display/ProjectContinuityHudTestHarness.kt under src/test) uses to read the real state
  // machine's outcome after driving this controller through fake Display/dispatch events - never
  // duplicated, never re-derived, the exact same instance production rendering reads from.
  internal val stateMachine = ProjectContinuityHudStateMachine()
  private val attaching = AtomicBoolean(false)
  private var session: DeviceSession? = null
  private var display: Display? = null
  private var displayStateJob: Job? = null
  private var loadJob: Job? = null
  private var displayReady = false

  fun selectProject(projectId: String, projectName: String) {
    val request = synchronized(lock) { stateMachine.selectProject(projectId, projectName) }
    render()
    load(request)
  }

  fun attachTo(session: DeviceSession) {
    synchronized(lock) {
      if (this.session === session && (display != null || attaching.get())) return
      if (this.session !== session) detachLocked()
      this.session = session
    }
    if (!attaching.compareAndSet(false, true)) return
    session.addDisplay().fold(
        onSuccess = { attached ->
          attaching.set(false)
          attachDisplay(attached)
        },
        onFailure = { error, _ ->
          attaching.set(false)
          Log.e(TAG, "Could not attach Project HUD: ${error.description}")
          onDisplayError("Project HUD unavailable: ${error.description}")
          synchronized(lock) { stateMachine.disconnected() }
        },
    )
  }

  private fun attachDisplay(attached: Display) {
    synchronized(lock) {
      display = attached
      displayReady = false
    }
    displayStateJob?.cancel()
    displayStateJob =
        scope.launch {
          attached.state.collect { state ->
            synchronized(lock) { displayReady = state == DisplayState.STARTED }
            when (state) {
              DisplayState.STARTED -> render()
              DisplayState.STOPPED -> {
                synchronized(lock) { stateMachine.disconnected() }
              }
              else -> Unit
            }
          }
        }
  }

  /**
   * Test-only seam: attaches an already-obtained [Display] directly, bypassing the real
   * real session-based SDK call [attachTo] makes. DeviceSession is a concrete, final SDK class
   * with an internal-only constructor - it cannot be constructed or faked outside a real device
   * connection - so this is how the Stage 2 acceptance harness drives this controller's real
   * Display-handling logic against a fake Display. [attachDisplay] is the exact same private
   * function [attachTo]'s onSuccess branch calls in production; nothing here is duplicated.
   */
  internal fun attachDisplayForTesting(display: Display) = attachDisplay(display)

  fun onSessionPaused() {
    synchronized(lock) { stateMachine.disconnected() }
  }

  fun onSessionReconnected() {
    val request = synchronized(lock) { stateMachine.refresh(reconnecting = true) }
    render()
    request?.let(::load)
  }

  fun detach() {
    synchronized(lock) { detachLocked() }
  }

  private fun detachLocked() {
    loadJob?.cancel()
    loadJob = null
    displayStateJob?.cancel()
    displayStateJob = null
    displayReady = false
    display = null
    session?.removeDisplay()
    session = null
    attaching.set(false)
  }

  private fun load(request: ProjectHudLoadRequest) {
    loadJob?.cancel()
    loadJob =
        scope.launch {
          try {
            val overview =
                withOneRetry(LOAD_RETRY_DELAY_MS) {
                  withContext(Dispatchers.IO) { repository.getProjectOverview(request.projectId) }
                }
            val accepted = synchronized(lock) { stateMachine.accept(request, overview) }
            if (accepted) render()
          } catch (error: Exception) {
            val accepted =
                synchronized(lock) {
                  stateMachine.fail(request, error.message ?: "Project state is unavailable.")
                }
            if (accepted) render()
          }
        }
  }

  private fun render() {
    scope.launch(Dispatchers.IO) { renderCurrentStateWithOneRetry() }
  }

  /**
   * Sends the Display's CURRENT state, retrying at most once after [RENDER_RETRY_DELAY_MS] if the
   * first send fails or times out.
   *
   * This exists because a transient DAT Display/session stall - observed physically overlapping
   * with an in-flight photo capture (DAT's own HeartbeatMonitor/DisplaySession logs show a
   * ~5-10s connectivity stall during capture) - previously left the glasses permanently showing
   * whatever was on screen *before* the failed sendContent() call, with buttons bound to an
   * older render generation than the state machine had already advanced to. Every subsequent tap
   * on that stale screen then failed acceptAction()'s (correct) generation-equality check, making
   * the whole HUD look frozen - not just Capture.
   *
   * [sendCurrentState] re-reads state from [stateMachine] fresh on every call (including the
   * retry - see [retryOnceThenReport]), so a retry never replays a stale snapshot captured back
   * when the original render() was requested - it always reflects whatever is authoritative by
   * the time it actually runs. Bounded to exactly one retry: if that also fails, this reports the
   * existing [onDisplayError] signal once and stops - it never loops, and it never touches
   * the capture workflow at all, only how a failed Display update is retried.
   */
  private suspend fun renderCurrentStateWithOneRetry() {
    retryOnceThenReport(
        delayMillis = RENDER_RETRY_DELAY_MS,
        onExhausted = { onDisplayError("Could not update the Project HUD.") },
        attempt = ::sendCurrentState,
    )
  }

  /** One attempt to push the state machine's current snapshot to the Display. Returns success. */
  private suspend fun sendCurrentState(): Boolean {
    val targetDisplay: Display
    val state: ProjectHudUiState
    val generation: Long
    val phoneActionLabel: String
    val captureStatus: ProjectHudCaptureStatus
    val analysisEligibility: ProjectHudAnalysisEligibility
    val analysisStatus: ProjectHudAnalysisStatus
    synchronized(lock) {
      if (!displayReady) return false
      targetDisplay = display ?: return false
      state = stateMachine.uiState ?: return false
      generation = stateMachine.renderGeneration
      phoneActionLabel = stateMachine.phoneActionLabel()
      captureStatus = stateMachine.captureStatus
      analysisEligibility = stateMachine.analysisEligibility
      analysisStatus = stateMachine.analysisStatus
    }
    var succeeded = true
    targetDisplay.sendContent { renderState(state, generation, phoneActionLabel, captureStatus, analysisEligibility, analysisStatus) }.onFailure { error, _ ->
      succeeded = false
      Log.e(TAG, "Could not render Project HUD: ${error.description}")
    }
    return succeeded
  }

  private fun ContentScope.renderState(
      state: ProjectHudUiState,
      generation: Long,
      phoneActionLabel: String,
      captureStatus: ProjectHudCaptureStatus,
      analysisEligibility: ProjectHudAnalysisEligibility,
      analysisStatus: ProjectHudAnalysisStatus,
  ) {
    // Awaiting a Use/Retake decision takes over the whole HUD rather than being folded into the
    // normal per-state screens below - it is a decision point, not routine Project content, and
    // keeping Use/Retake as the only two options on-screen avoids a mistap on a small HUD.
    if (captureStatus is ProjectHudCaptureStatus.AwaitingConfirmation) {
      captureConfirmationScreen(state.projectName, generation)
      return
    }
    // Same reasoning for an analysis result awaiting a trust decision - it is the OTHER decision
    // point this HUD can be in, so it takes over the whole screen the same way. Capture always
    // wins if somehow both are true at once (defense only - dispatchTrustDecision/dispatchAnalyze
    // cannot fire while a photo confirmation is showing, since that is a different screen).
    val pendingTrustReview = (state as? ProjectHudUiState.Ready)?.content?.pendingTrustReview
    if (pendingTrustReview != null) {
      trustReviewScreen(state.projectName, pendingTrustReview, analysisStatus, generation)
      return
    }
    when (state) {
      is ProjectHudUiState.Loading -> statusScreen(state.projectName, "Loading current Project…", generation, phoneActionLabel)
      is ProjectHudUiState.Disconnected ->
          statusScreen(state.projectName, "Glasses disconnected. Reconnect to refresh this Project.", generation, phoneActionLabel)
      is ProjectHudUiState.Error ->
          statusScreen(state.projectName, "Couldn’t load current Project state.", generation, phoneActionLabel)
      is ProjectHudUiState.Stale ->
          flexBox(direction = Direction.COLUMN, gap = 10) {
            text(short(state.projectName), style = TextStyle.HEADING)
            text("RECONNECTING", style = TextStyle.META, color = TextColor.SECONDARY)
            text(short(state.message), style = TextStyle.BODY)
            text("Showing the last loaded summary until refresh completes.", style = TextStyle.META)
            captureRow(captureStatus, generation)
            analysisRow(analysisEligibility, analysisStatus, generation, hasPriorSuggestion = state.content.latestGuidance != null)
            button("Refresh", onClick = { dispatchRefresh(generation) })
            button(phoneActionLabel, onClick = { dispatchPhone(generation) })
          }
      is ProjectHudUiState.Ready -> {
        if (state.content.isEmpty) emptyScreen(state.content, generation, phoneActionLabel, captureStatus, analysisEligibility, analysisStatus)
        else if (state.destination == ProjectHudDestination.DETAILS) details(state.content, generation, phoneActionLabel, captureStatus, analysisEligibility, analysisStatus)
        else overview(state.content, generation, phoneActionLabel, captureStatus, analysisEligibility, analysisStatus)
      }
    }
  }

  /**
   * The one action set this HUD offers for triggering a real photo capture from the glasses -
   * see class doc. Deliberately absent from Loading/Disconnected/Error: those states have no
   * settled Project content to attribute a capture to, or (Disconnected) no live session to
   * capture through. [ProjectHudCaptureStatus.Failed] is shown honestly next to the retry button
   * rather than retried automatically - the next attempt is always an explicit user tap.
   */
  private fun FlexBoxScope.captureRow(status: ProjectHudCaptureStatus, generation: Long) {
    when (status) {
      is ProjectHudCaptureStatus.Capturing -> text("CAPTURING…", style = TextStyle.META, color = TextColor.SECONDARY)
      is ProjectHudCaptureStatus.Failed -> {
        text(short("Couldn't capture that photo: ${status.message}"), style = TextStyle.META, color = TextColor.SECONDARY)
        button("Capture", onClick = { dispatchCapture(generation) })
      }
      ProjectHudCaptureStatus.Idle -> button("Capture", onClick = { dispatchCapture(generation) })
      // Never actually reached: renderState() short-circuits to captureConfirmationScreen()
      // before falling into whichever screen calls captureRow(). Listed only for when-exhaustiveness.
      ProjectHudCaptureStatus.AwaitingConfirmation -> Unit
    }
  }

  /** The Use/Retake decision screen for a pending captured photo - see renderState()'s doc. */
  private fun ContentScope.captureConfirmationScreen(projectName: String, generation: Long) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(projectName), style = TextStyle.HEADING)
      text("Photo captured — use this image?", style = TextStyle.BODY)
      button("Use", onClick = { dispatchUse(generation) })
      button("Retake", style = ButtonStyle.SECONDARY, onClick = { dispatchRetake(generation) })
    }
  }

  /**
   * Offers Analyze on the normal per-state screens, before any [ProjectHudPendingTrustReview]
   * exists - see renderState()'s precedence doc for the other half of this lifecycle
   * ([trustReviewScreen], once one does). Fully absent only when there is genuinely nothing to
   * analyze yet (no captured evidence) - matching [captureRow]'s own show-nothing convention.
   *
   * When evidence exists but Analyze still is not offered, this explains why instead of silently
   * omitting it - the proven gap where a HUD Capture -> Use just added evidence, but explanation
   * has no glasses-side input surface (only the phone's existing Investigation panel does). The
   * hint sits next to the phoneActionLabel button every one of these screens already offers, so it
   * points at an action already on screen rather than adding a second phone-handoff path.
   *
   * [hasPriorSuggestion] (this Project's [ProjectHudContent.latestGuidance] being non-null, the
   * same existing signal [details] already reads) chooses the button's wording via
   * [analyzeButtonLabel] - a bare "Analyze" reads as a first, one-time action, but once a prior AI
   * suggestion exists this button really means re-analyzing with whatever evidence has been added
   * since. No new state: this reuses a field the canonical Project overview already provides.
   */
  private fun FlexBoxScope.analysisRow(
      eligibility: ProjectHudAnalysisEligibility,
      status: ProjectHudAnalysisStatus,
      generation: Long,
      hasPriorSuggestion: Boolean,
  ) {
    when (status) {
      ProjectHudAnalysisStatus.Working -> text("ANALYZING…", style = TextStyle.META, color = TextColor.SECONDARY)
      is ProjectHudAnalysisStatus.Failed -> {
        text(short("Analyze failed: ${status.message}"), style = TextStyle.META, color = TextColor.SECONDARY)
        if (eligibility.canAnalyze) button(analyzeButtonLabel(hasPriorSuggestion), onClick = { dispatchAnalyze(generation) })
      }
      ProjectHudAnalysisStatus.Idle ->
          if (eligibility.canAnalyze) {
            button(analyzeButtonLabel(hasPriorSuggestion), onClick = { dispatchAnalyze(generation) })
          } else if (eligibility.hasEvidence && !eligibility.hasExplanation) {
            text("Continue on your phone to finish analyzing this project.", style = TextStyle.META, color = TextColor.SECONDARY)
          }
    }
  }

  /**
   * Smallest state-appropriate Analyze wording (see [analysisRow]'s doc): a fresh Project with no
   * prior AI suggestion gets a plain first-time label; once a suggestion already exists, tapping
   * this again really means re-analyzing with whatever evidence has changed since, so it says so
   * rather than repeating the same generic "Analyze".
   */
  private fun analyzeButtonLabel(hasPriorSuggestion: Boolean): String =
      if (hasPriorSuggestion) "Update suggestion" else "Analyze project"

  /**
   * The trust-decision screen for a completed analysis awaiting one of the three required
   * actions - see renderState()'s precedence doc. [status] here means something different than in
   * [analysisRow]: Working is this trust decision being submitted, and Failed is that submission
   * failing (still shows the three actions again, ready for a fresh explicit tap - never retried
   * automatically). The hypothesis is labeled clearly unconfirmed AI output, never canonical
   * Project truth, matching the same honesty convention
   * [ProjectContinuityHudStateMachine.mapOverview] already uses for latestGuidance - only the
   * wording here is friendlier, not the distinction itself. The three button labels below
   * (KEEP_AS_HYPOTHESIS/ADD_EVIDENCE/RETURN) are human-facing rewording only - see
   * [ProjectHudTrustAction]'s doc: the enum names, dispatch routing, and backend
   * BackendTrustDecision mapping are all unchanged.
   */
  private fun ContentScope.trustReviewScreen(
      projectName: String,
      pending: ProjectHudPendingTrustReview,
      status: ProjectHudAnalysisStatus,
      generation: Long,
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(projectName), style = TextStyle.HEADING)
      text("THE AI'S IDEA — NOT CONFIRMED YET", style = TextStyle.META, color = TextColor.SECONDARY)
      text(short(pending.hypothesis), style = TextStyle.BODY)
      text("SUGGESTED NEXT STEP", style = TextStyle.META, color = TextColor.SECONDARY)
      text(short(pending.recommendedNextAction), style = TextStyle.BODY)
      if (status is ProjectHudAnalysisStatus.Failed) {
        text(short("Failed: ${status.message}"), style = TextStyle.META, color = TextColor.SECONDARY)
      }
      if (status is ProjectHudAnalysisStatus.Working) {
        text("SUBMITTING…", style = TextStyle.META, color = TextColor.SECONDARY)
      } else {
        button("Looks right", onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.KEEP_AS_HYPOTHESIS) })
        button("Add more info", onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.ADD_EVIDENCE) })
        button("Go back", style = ButtonStyle.SECONDARY, onClick = { dispatchTrustDecision(generation, ProjectHudTrustAction.RETURN) })
      }
    }
  }

  private fun ContentScope.statusScreen(
      projectName: String,
      message: String,
      generation: Long,
      phoneActionLabel: String,
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(projectName), style = TextStyle.HEADING)
      text(message, style = TextStyle.BODY)
      button("Refresh", onClick = { dispatchRefresh(generation) })
      button(phoneActionLabel, style = ButtonStyle.SECONDARY, onClick = { dispatchPhone(generation) })
    }
  }

  private fun ContentScope.emptyScreen(
      content: ProjectHudContent,
      generation: Long,
      phoneActionLabel: String,
      captureStatus: ProjectHudCaptureStatus,
      analysisEligibility: ProjectHudAnalysisEligibility,
      analysisStatus: ProjectHudAnalysisStatus,
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(content.projectName), style = TextStyle.HEADING)
      text("NEW PROJECT", style = TextStyle.META, color = TextColor.SECONDARY)
      text("Nothing has been recorded yet.", style = TextStyle.BODY)
      text("Choose what you want to work on next from your phone.", style = TextStyle.BODY)
      captureRow(captureStatus, generation)
      analysisRow(analysisEligibility, analysisStatus, generation, hasPriorSuggestion = content.latestGuidance != null)
      button(phoneActionLabel, onClick = { dispatchPhone(generation) })
      button("Refresh", style = ButtonStyle.SECONDARY, onClick = { dispatchRefresh(generation) })
    }
  }

  private fun ContentScope.overview(
      content: ProjectHudContent,
      generation: Long,
      phoneActionLabel: String,
      captureStatus: ProjectHudCaptureStatus,
      analysisEligibility: ProjectHudAnalysisEligibility,
      analysisStatus: ProjectHudAnalysisStatus,
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(content.projectName), style = TextStyle.HEADING)
      text("WHERE WE LEFT OFF", style = TextStyle.META, color = TextColor.SECONDARY)
      text(short(content.whereWeLeftOff ?: "Nothing recorded yet."), style = TextStyle.BODY)
      text("NEXT", style = TextStyle.META, color = TextColor.SECONDARY)
      text(short(content.nextAction ?: "Choose the next action on your phone."), style = TextStyle.BODY)
      content.attentionSummary?.let {
        flexBox(padding = 12, background = FlexBoxBackground.CARD) {
          text("NEEDS ATTENTION", style = TextStyle.META)
          text(short(it), style = TextStyle.BODY)
        }
      }
      captureRow(captureStatus, generation)
      analysisRow(analysisEligibility, analysisStatus, generation, hasPriorSuggestion = content.latestGuidance != null)
      if (content.hasAdditionalDetails) {
        button("Show details", onClick = { dispatchDetails(generation) })
      }
      button(phoneActionLabel, style = ButtonStyle.SECONDARY, onClick = { dispatchPhone(generation) })
      button("Refresh", style = ButtonStyle.SECONDARY, onClick = { dispatchRefresh(generation) })
    }
  }

  private fun ContentScope.details(
      content: ProjectHudContent,
      generation: Long,
      phoneActionLabel: String,
      captureStatus: ProjectHudCaptureStatus,
      analysisEligibility: ProjectHudAnalysisEligibility,
      analysisStatus: ProjectHudAnalysisStatus,
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(content.projectName), style = TextStyle.HEADING)
      content.whereWeLeftOff?.let {
        text("CURRENT STATUS", style = TextStyle.META, color = TextColor.SECONDARY)
        text(short(it), style = TextStyle.BODY)
      }
      if (content.evidenceCount > 0) {
        text("RECENT EVIDENCE", style = TextStyle.META, color = TextColor.SECONDARY)
        text("${content.evidenceCount} saved evidence ${if (content.evidenceCount == 1) "item" else "items"}", style = TextStyle.BODY)
      }
      content.latestGuidance?.let {
        text("LATEST GUIDANCE", style = TextStyle.META, color = TextColor.SECONDARY)
        text(short(it), style = TextStyle.BODY)
      }
      content.attentionSummary?.let {
        text("NEEDS ATTENTION", style = TextStyle.META, color = TextColor.SECONDARY)
        text(short(it), style = TextStyle.BODY)
      }
      captureRow(captureStatus, generation)
      analysisRow(analysisEligibility, analysisStatus, generation, hasPriorSuggestion = content.latestGuidance != null)
      button("Back", onClick = { dispatchBack(generation) })
      button(phoneActionLabel, style = ButtonStyle.SECONDARY, onClick = { dispatchPhone(generation) })
    }
  }

  // The dispatch* functions below are internal, not private, purely for testability: the Stage 2
  // acceptance harness (display/ProjectContinuityHudTestHarness.kt under src/test) calls these
  // exact same functions a real button tap would, against a fake Display - see stateMachine's doc
  // above. Nothing about their behavior changes for production; they are simply reachable from a
  // real button's onClick closure either way.
  internal fun dispatchDetails(generation: Long) {
    val changed = synchronized(lock) { stateMachine.showDetails(generation) }
    if (changed) render()
  }

  internal fun dispatchBack(generation: Long) {
    val changed = synchronized(lock) { stateMachine.showOverview(generation) }
    if (changed) render()
  }

  internal fun dispatchPhone(generation: Long) {
    val handoff = synchronized(lock) { stateMachine.phoneHandoff(generation) } ?: return
    onPhoneHandoff(handoff)
  }

  internal fun dispatchRefresh(generation: Long) {
    val request = synchronized(lock) { stateMachine.acceptRefresh(generation) } ?: return
    render()
    load(request)
  }

  internal fun dispatchCapture(generation: Long) {
    val accepted = synchronized(lock) { stateMachine.acceptCapture(generation) }
    if (!accepted) return
    render()
    onCaptureRequested()
  }

  /** Called by the session owner once a HUD-requested capture has finished successfully. */
  fun onCaptureSucceeded() {
    val changed = synchronized(lock) { stateMachine.captureSucceeded() }
    if (changed) render()
  }

  internal fun dispatchUse(generation: Long) {
    // No render() here: acceptUse() never changes captureStatus by itself (see its doc) - the
    // screen stays exactly as-is (Use/Retake still visible but no longer tappable at this
    // generation) until the owner reports back via onCaptureAccepted/onCaptureFailed below.
    val accepted = synchronized(lock) { stateMachine.acceptUse(generation) }
    if (!accepted) return
    onUseRequested()
  }

  internal fun dispatchRetake(generation: Long) {
    val accepted = synchronized(lock) { stateMachine.acceptRetake(generation) }
    if (!accepted) return
    render()
    onRetakeRequested()
  }

  /** Called by the session owner once a HUD-requested Use has been added as evidence. */
  fun onCaptureAccepted() {
    val changed = synchronized(lock) { stateMachine.captureAccepted() }
    if (changed) render()
  }

  /**
   * Called by the session owner when a HUD-requested capture has failed, or when a HUD-requested
   * Use could not be applied (e.g. the Investigation's 5-photo capacity filled between capture and
   * Use). No retry is implied either way - the user retries by tapping Capture again.
   */
  fun onCaptureFailed(message: String) {
    val changed = synchronized(lock) { stateMachine.captureFailed(message) }
    if (changed) render()
  }

  /** Called by the session owner whenever Analyze eligibility changes - see its doc. */
  fun onAnalysisEligibilityChanged(eligibility: ProjectHudAnalysisEligibility) {
    val changed = synchronized(lock) { stateMachine.setAnalysisEligibility(eligibility) }
    if (changed) render()
  }

  internal fun dispatchAnalyze(generation: Long) {
    val accepted = synchronized(lock) { stateMachine.acceptAnalyze(generation) }
    if (!accepted) return
    render()
    onAnalyzeRequested()
  }

  internal fun dispatchTrustDecision(generation: Long, action: ProjectHudTrustAction) {
    val sessionId = synchronized(lock) { stateMachine.acceptTrustDecision(generation, action) } ?: return
    render()
    onTrustDecisionRequested(action, sessionId)
  }

  /**
   * Called by the session owner once a HUD-requested Analyze or trust decision has finished
   * successfully. Triggers the same canonical Project refresh Refresh itself uses - this is what
   * turns a completed analysis into a [ProjectHudPendingTrustReview] the HUD can act on, and what
   * turns a completed trust decision into that review disappearing, without this class needing to
   * understand Investigation internals at all - it only ever reflects what refreshing the Project
   * returns, exactly like every other HUD screen already does.
   */
  fun onAnalysisSucceeded() {
    val changed = synchronized(lock) { stateMachine.analysisSucceeded() }
    if (!changed) return
    render()
    val request = synchronized(lock) { stateMachine.refresh() } ?: return
    render()
    load(request)
  }

  /**
   * Called by the session owner when a HUD-requested Analyze or trust-decision submission failed.
   * No retry is implied - the user retries by tapping Analyze, or the trust action again.
   */
  fun onAnalysisFailed(message: String) {
    val changed = synchronized(lock) { stateMachine.analysisFailed(message) }
    if (changed) render()
  }

  private fun short(value: String): String =
      if (value.length <= MAX_BODY_CHARS) value else value.take(MAX_BODY_CHARS - 1).trimEnd() + "…"
}

/**
 * Runs [action] once, and exactly once more after [delayMillis] if the first attempt throws.
 * Bounded and non-repeating on purpose: it exists to absorb a brief connectivity blip around
 * DeviceSession establishment (see load() above), not to mask a genuinely unavailable backend
 * behind silent, unbounded retries.
 */
internal suspend fun <T> withOneRetry(delayMillis: Long, action: suspend () -> T): T =
    try {
      action()
    } catch (firstAttemptError: Exception) {
      delay(delayMillis)
      action()
    }

/**
 * Runs [attempt] once, retrying exactly once more after [delayMillis] if the first call returns
 * false, and calling [onExhausted] only if BOTH calls return false. Bounded and non-repeating on
 * purpose - see [ProjectContinuityHudController]'s renderCurrentStateWithOneRetry() doc for why
 * this exists (a transient DAT Display send failure must resync once, not loop, and must never be
 * confused with retrying the capture itself - [attempt] here only ever sends Display content).
 * Each call to [attempt] is a fresh invocation, so a retry naturally reflects whatever [attempt]
 * considers "current" at that later moment - this function holds no snapshot of its own.
 */
internal suspend fun retryOnceThenReport(
    delayMillis: Long,
    onExhausted: () -> Unit,
    attempt: suspend () -> Boolean,
) {
  if (attempt()) return
  delay(delayMillis)
  if (attempt()) return
  onExhausted()
}
