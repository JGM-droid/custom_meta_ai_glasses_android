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
) {
  private companion object {
    private const val TAG = "CameraAccess:ProjectHUD"
    private const val MAX_BODY_CHARS = 180
    private const val LOAD_RETRY_DELAY_MS = 600L
    private const val RENDER_RETRY_DELAY_MS = 500L
  }

  private val lock = Any()
  private val stateMachine = ProjectContinuityHudStateMachine()
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
          synchronized(lock) {
            display = attached
            displayReady = false
          }
          attaching.set(false)
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
        },
        onFailure = { error, _ ->
          attaching.set(false)
          Log.e(TAG, "Could not attach Project HUD: ${error.description}")
          onDisplayError("Project HUD unavailable: ${error.description}")
          synchronized(lock) { stateMachine.disconnected() }
        },
    )
  }

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
    synchronized(lock) {
      if (!displayReady) return false
      targetDisplay = display ?: return false
      state = stateMachine.uiState ?: return false
      generation = stateMachine.renderGeneration
      phoneActionLabel = stateMachine.phoneActionLabel()
      captureStatus = stateMachine.captureStatus
    }
    var succeeded = true
    targetDisplay.sendContent { renderState(state, generation, phoneActionLabel, captureStatus) }.onFailure { error, _ ->
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
  ) {
    // Awaiting a Use/Retake decision takes over the whole HUD rather than being folded into the
    // normal per-state screens below - it is a decision point, not routine Project content, and
    // keeping Use/Retake as the only two options on-screen avoids a mistap on a small HUD.
    if (captureStatus is ProjectHudCaptureStatus.AwaitingConfirmation) {
      captureConfirmationScreen(state.projectName, generation)
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
            button("Refresh", onClick = { dispatchRefresh(generation) })
            button(phoneActionLabel, onClick = { dispatchPhone(generation) })
          }
      is ProjectHudUiState.Ready -> {
        if (state.content.isEmpty) emptyScreen(state.content, generation, phoneActionLabel, captureStatus)
        else if (state.destination == ProjectHudDestination.DETAILS) details(state.content, generation, phoneActionLabel, captureStatus)
        else overview(state.content, generation, phoneActionLabel, captureStatus)
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
        text(short("Capture failed: ${status.message}"), style = TextStyle.META, color = TextColor.SECONDARY)
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
  ) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(content.projectName), style = TextStyle.HEADING)
      text("NEW PROJECT", style = TextStyle.META, color = TextColor.SECONDARY)
      text("Nothing has been recorded yet.", style = TextStyle.BODY)
      text("Choose what you want to work on next from your phone.", style = TextStyle.BODY)
      captureRow(captureStatus, generation)
      button(phoneActionLabel, onClick = { dispatchPhone(generation) })
      button("Refresh", style = ButtonStyle.SECONDARY, onClick = { dispatchRefresh(generation) })
    }
  }

  private fun ContentScope.overview(
      content: ProjectHudContent,
      generation: Long,
      phoneActionLabel: String,
      captureStatus: ProjectHudCaptureStatus,
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
      button("Back", onClick = { dispatchBack(generation) })
      button(phoneActionLabel, style = ButtonStyle.SECONDARY, onClick = { dispatchPhone(generation) })
    }
  }

  private fun dispatchDetails(generation: Long) {
    val changed = synchronized(lock) { stateMachine.showDetails(generation) }
    if (changed) render()
  }

  private fun dispatchBack(generation: Long) {
    val changed = synchronized(lock) { stateMachine.showOverview(generation) }
    if (changed) render()
  }

  private fun dispatchPhone(generation: Long) {
    val handoff = synchronized(lock) { stateMachine.phoneHandoff(generation) } ?: return
    onPhoneHandoff(handoff)
  }

  private fun dispatchRefresh(generation: Long) {
    val request = synchronized(lock) { stateMachine.acceptRefresh(generation) } ?: return
    render()
    load(request)
  }

  private fun dispatchCapture(generation: Long) {
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

  private fun dispatchUse(generation: Long) {
    // No render() here: acceptUse() never changes captureStatus by itself (see its doc) - the
    // screen stays exactly as-is (Use/Retake still visible but no longer tappable at this
    // generation) until the owner reports back via onCaptureAccepted/onCaptureFailed below.
    val accepted = synchronized(lock) { stateMachine.acceptUse(generation) }
    if (!accepted) return
    onUseRequested()
  }

  private fun dispatchRetake(generation: Long) {
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
