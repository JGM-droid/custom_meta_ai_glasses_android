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
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {
  private companion object {
    private const val TAG = "CameraAccess:ProjectHUD"
    private const val MAX_BODY_CHARS = 180
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
            val overview = withContext(Dispatchers.IO) { repository.getProjectOverview(request.projectId) }
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
    val targetDisplay: Display
    val state: ProjectHudUiState
    val generation: Long
    val phoneActionLabel: String
    synchronized(lock) {
      if (!displayReady) return
      targetDisplay = display ?: return
      state = stateMachine.uiState ?: return
      generation = stateMachine.renderGeneration
      phoneActionLabel = stateMachine.phoneActionLabel()
    }
    scope.launch(Dispatchers.IO) {
      targetDisplay.sendContent { renderState(state, generation, phoneActionLabel) }.onFailure { error, _ ->
        Log.e(TAG, "Could not render Project HUD: ${error.description}")
        onDisplayError("Could not update the Project HUD.")
      }
    }
  }

  private fun ContentScope.renderState(
      state: ProjectHudUiState,
      generation: Long,
      phoneActionLabel: String,
  ) {
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
            button("Refresh", onClick = { dispatchRefresh(generation) })
            button(phoneActionLabel, onClick = { dispatchPhone(generation) })
          }
      is ProjectHudUiState.Ready -> {
        if (state.content.isEmpty) emptyScreen(state.content, generation, phoneActionLabel)
        else if (state.destination == ProjectHudDestination.DETAILS) details(state.content, generation, phoneActionLabel)
        else overview(state.content, generation, phoneActionLabel)
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

  private fun ContentScope.emptyScreen(content: ProjectHudContent, generation: Long, phoneActionLabel: String) {
    flexBox(direction = Direction.COLUMN, gap = 10) {
      text(short(content.projectName), style = TextStyle.HEADING)
      text("NEW PROJECT", style = TextStyle.META, color = TextColor.SECONDARY)
      text("Nothing has been recorded yet.", style = TextStyle.BODY)
      text("Choose what you want to work on next from your phone.", style = TextStyle.BODY)
      button(phoneActionLabel, onClick = { dispatchPhone(generation) })
      button("Refresh", style = ButtonStyle.SECONDARY, onClick = { dispatchRefresh(generation) })
    }
  }

  private fun ContentScope.overview(content: ProjectHudContent, generation: Long, phoneActionLabel: String) {
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
      if (content.hasAdditionalDetails) {
        button("Show details", onClick = { dispatchDetails(generation) })
      }
      button(phoneActionLabel, style = ButtonStyle.SECONDARY, onClick = { dispatchPhone(generation) })
      button("Refresh", style = ButtonStyle.SECONDARY, onClick = { dispatchRefresh(generation) })
    }
  }

  private fun ContentScope.details(content: ProjectHudContent, generation: Long, phoneActionLabel: String) {
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

  private fun short(value: String): String =
      if (value.length <= MAX_BODY_CHARS) value else value.take(MAX_BODY_CHARS - 1).trimEnd() + "…"
}
