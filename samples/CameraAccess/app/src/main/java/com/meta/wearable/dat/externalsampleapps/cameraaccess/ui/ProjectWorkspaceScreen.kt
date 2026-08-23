/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectWorkspaceScreen - Project Workspace v1
//
// The real "desk" a user lands on after "Continue Project" - not the placeholder this replaces.
// Backend state (checkpoint, Active Project, recent activity) is fetched by the SAME
// ProjectDetailViewModel Project Detail uses (see the `viewModel(key = project.projectId, ...)`
// call below) rather than a duplicate ViewModel - Active Project set/clear, isActive, and the
// checkpoint/activity fetch all come from that one already-proven implementation. The only thing
// unique to this screen is temporary composer draft text, kept as plain Compose state (same
// pattern NewProjectScreen already uses for its form fields) - never sent anywhere, never turned
// into Project Memory, and gone the moment this screen leaves composition.
//
// Checkpoint field mapping is deliberately IDENTICAL to ProjectDetailScreen: "Where We Left Off"
// -> checkpoint.current_work, "Next Action" -> checkpoint.next_action (see ProjectSection reuse
// below). The backend also has a distinct checkpoint.current_objective field, which the web
// dashboard's own "Where you left off" happens to read instead of current_work - a pre-existing
// cross-client label difference from an earlier Android slice, not something this slice
// introduces or silently resolves. Reusing Project Detail's existing, already-accurate mapping
// keeps this screen self-consistent with the rest of the Android app; changing that mapping (or
// the web dashboard, which lives in the read-only backend repo) is out of scope here.
//
// Project Actions exposes exactly one real action - "Capture / Test Glasses", reusing the exact
// same Capture entry point Projects Home already offers (no project-scoped capture attribution
// is implemented yet - see AppRoot). The composer's camera affordance is still visually reserved
// but disabled; the microphone affordance is now real (see Voice-to-Text below) - the camera one
// still represents later inline capture-while-typing, not a shortcut into the full-screen Capture
// flow (that already has its own clearly-labeled entry point in Project Actions).
//
// Voice-to-Text: the mic button reuses the EXISTING Android SpeechRecognizer plumbing built for
// the Investigation flow - InvestigationSpeechRecognizerController (ui/
// InvestigationSpeechRecognizerController.kt) and the pure reduceInvestigationSpeechState/
// InvestigationSpeechEvent/InvestigationSpeechUiState state machine (investigation/
// InvestigationSpeechState.kt) - rather than a second, duplicate speech implementation. Neither
// of those types has anything Investigation-specific in it (confirmed by inspection before
// reusing them): the reducer is a plain event->state function, and the controller is a generic
// on-device-SpeechRecognizer wrapper. Only the destination differs: Investigation pipes its
// transcript into an explanation field; Workspace pipes it into the SAME composer real typed text
// already uses (see onSpeechEvent below), with the exact append-don't-destroy behavior Phase 5 of
// the Voice-to-Text slice requires. Voice is purely an input method: a transcript only ever
// updates local draft state (draftText) - it never calls askProject() itself and never touches
// the backend. speechControllerFactory is the test seam this slice adds: production code defaults
// it to the real createInvestigationSpeechRecognizerController factory, and instrumented tests
// inject a fake InvestigationSpeechRecognizerController instead of driving the real on-device
// recognizer (which cannot be reliably scripted in an automated test).
//
// Ask Project (Project-Aware Ask): the composer is now a real interaction, not just draft text.
// "Ask Project" sends the typed question to the backend's existing, read-only
// POST /projects/{project_id}/ask via ProjectDetailViewModel.askProject - always THIS Workspace's
// own project.projectId, never the separate Active Project pointer (see ActiveProjectControl
// above; the two are deliberately independent). The primary UI only ever shows the plain answer
// text under a "PROJECT ASSISTANT" heading - question_class/grounding_status/references/
// provider/model_call_count are preserved on ProjectAskAnswer for a possible future debug/Details
// view but are never surfaced here, matching the product goal of feeling like "I asked my
// Project a question" rather than exposing backend Q&A internals. Submitting a question never
// touches the composer text destructively on failure (the question stays editable for retry) and
// is cleared only once a real answer comes back, so there is nothing to "premature-clear" on the
// unhappy path.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechUiPhase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.reduceInvestigationSpeechState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectActivityEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectAskAnswer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectAskState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectDetailViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

// internal (not public): speechControllerFactory's parameter type (InvestigationSpeechRecognizerController,
// the test seam - see file header) is itself internal, and Kotlin forbids an internal type in a
// public signature. AppRoot.kt (the only caller) is in the same module/package, so this loses
// nothing - matches BackendInvestigationPanel's own `internal fun` for the same reason.
@Composable
internal fun ProjectWorkspaceScreen(
    project: ProjectSummary,
    onBack: () -> Unit,
    onOpenCapture: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectDetailViewModel =
        viewModel(
            // Same key convention as ProjectDetailScreen: guarantees a per-project instance, and
            // deliberately the SAME instance as Project Detail for this project_id (both screens
            // reuse ProjectDetailViewModel), so Active Project state never drifts between them.
            key = project.projectId,
            factory =
                ProjectDetailViewModel.Factory(
                    application = LocalContext.current.applicationContext as Application,
                    projectId = project.projectId,
                ),
        ),
    // Test seam: production uses the real on-device SpeechRecognizer via the existing
    // Investigation controller factory; instrumented tests pass a fake instead.
    speechControllerFactory: (Context) -> InvestigationSpeechRecognizerController? =
        ::createInvestigationSpeechRecognizerController,
) {
  val uiState by viewModel.uiState.collectAsState()
  val activeActionState by viewModel.activeActionState.collectAsState()
  val askState by viewModel.askState.collectAsState()

  // Composer text - reset whenever this composable is instantiated fresh for a different
  // project_id (a brand-new `remember` scope), so Project A's typed question can never bleed
  // into Project B's Workspace. askState itself is scoped per-project too, since viewModel is
  // keyed by project.projectId below - a brand-new ProjectDetailViewModel (and Idle askState)
  // is created per distinct Project automatically.
  var draftText by remember(project.projectId) { mutableStateOf("") }

  // Cleared only once a real answer comes back - never on submit (nothing to lose if it fails)
  // and never on failure (the question must stay editable for retry).
  LaunchedEffect(askState) {
    if (askState is ProjectAskState.Answered) {
      draftText = ""
    }
  }

  // Voice-to-text plumbing - see file header. Scoped per-project the same way draftText is: a
  // fresh Workspace mount for a different project_id gets a fresh controller/state, so one
  // Project's in-flight/listening voice session can never bleed into another's.
  val context = LocalContext.current
  val speechController = remember(project.projectId, context) { speechControllerFactory(context) }
  var speechUiState by remember(project.projectId) { mutableStateOf(InvestigationSpeechUiState()) }

  // Stops/releases the recognizer whenever this Workspace (or this specific Project's instance
  // of it) leaves composition - navigating away must never leak a live recognizer/Activity
  // reference, and reopening Workspace must always get a fresh, working recognizer.
  DisposableEffect(speechController) {
    onDispose { speechController?.destroy() }
  }

  // Voice is an input method only: a transcript ever does exactly one thing - update draftText.
  // It never calls askProject() and never touches the backend (Phase 6/7 of the Voice-to-Text
  // slice). Append rather than overwrite when the composer already has text (Phase 5).
  val onSpeechEvent: (InvestigationSpeechEvent) -> Unit = { event ->
    val transition = reduceInvestigationSpeechState(speechUiState, event)
    speechUiState = transition.state
    transition.transcript?.let { transcript -> draftText = appendTranscriptToDraft(draftText, transcript) }
  }

  val startSpeechCapture: () -> Unit = {
    if (speechController == null) {
      onSpeechEvent(InvestigationSpeechEvent.UnknownError("Speech recognition is unavailable on this device."))
    } else {
      speechController.startListening(onSpeechEvent)
    }
  }

  val microphonePermissionLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
          startSpeechCapture()
        } else {
          onSpeechEvent(InvestigationSpeechEvent.PermissionDenied)
        }
      }

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .background(AppColor.Graphite)
              .systemBarsPadding()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 20.dp),
  ) {
    TextButton(
        onClick = onBack,
        colors = ButtonDefaults.textButtonColors(contentColor = AppColor.InkPrimary),
    ) {
      Text("‹ Overview")
    }

    Text(
        text = project.name,
        color = AppColor.InkPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )

    when (val state = uiState) {
      is ProjectDetailUiState.Loading ->
          Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColor.Accent)
          }
      is ProjectDetailUiState.Error ->
          Column(modifier = Modifier.padding(top = 24.dp)) {
            Text(
                text = "Couldn't load this project's state.",
                color = AppColor.InkPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.message, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(
                onClick = viewModel::loadOverview,
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
            ) {
              Text("Retry")
            }
          }
      is ProjectDetailUiState.Loaded -> {
        val overview = state.overview

        ActiveProjectControl(
            isActive = state.isActive,
            actionState = activeActionState,
            onWorkOnProject = viewModel::setActiveProject,
            onStopWorking = viewModel::clearActiveProject,
        )

        ProjectSection(
            title = "Where We Left Off",
            body = overview.checkpoint.whereWeLeftOff ?: "No current work recorded.",
        )
        ProjectSection(
            title = "Next Action",
            body = overview.checkpoint.nextAction ?: "No next action recorded.",
        )

        WorkspaceComposer(
            text = draftText,
            onTextChange = { draftText = it },
            askState = askState,
            onAskProject = { viewModel.askProject(draftText) },
            speechUiState = speechUiState,
            onMicClick = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onCancelListening = {
              speechController?.cancel()
              onSpeechEvent(InvestigationSpeechEvent.Cancelled)
            },
        )

        val answeredState = askState as? ProjectAskState.Answered
        if (answeredState != null) {
          AskAnswerCard(question = answeredState.question, answer = answeredState.answer)
        }

        WorkspaceActions(onOpenCapture = onOpenCapture)

        RecentActivityPreview(overview.recentActivity)

        SecondarySections(onOpenProjectDetails = onBack)
      }
    }
  }
}

/**
 * Pure, unit-testable merge of a voice transcript into the composer's current draft text (Phase 5
 * of the Voice-to-Text slice): an empty (or whitespace-only) draft is replaced outright; a
 * non-empty draft is preserved and the transcript is appended on a new line rather than
 * destroying what the user already typed.
 */
internal fun appendTranscriptToDraft(currentDraft: String, transcript: String): String =
    if (currentDraft.isBlank()) transcript else "$currentDraft\n$transcript"

@Composable
private fun WorkspaceComposer(
    text: String,
    onTextChange: (String) -> Unit,
    askState: ProjectAskState,
    onAskProject: () -> Unit,
    speechUiState: InvestigationSpeechUiState,
    onMicClick: () -> Unit,
    onCancelListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val isSubmitting = askState is ProjectAskState.Submitting
  // Whitespace-only input can never submit - trimmed the same way the ViewModel itself checks.
  val canSubmit = text.isNotBlank() && !isSubmitting
  val isListening = speechUiState.phase == InvestigationSpeechUiPhase.LISTENING
  // Can't start a new voice session while one is already listening or a question is in flight -
  // and can't submit Ask while voice is listening, since the transcript hasn't landed yet.
  val micEnabled = !isListening && !isSubmitting

  Column(modifier = modifier.padding(top = 28.dp)) {
    Text(
        text = "WHAT ARE YOU WORKING ON?",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        // Not editable while a question is in flight - keeps exactly what was asked visible and
        // unambiguous for the duration of the request, matching what the backend actually
        // received. Failure re-enables editing automatically (isSubmitting becomes false).
        enabled = !isSubmitting,
        placeholder = { Text("Ask your Project anything...", color = AppColor.InkSecondary) },
        minLines = 3,
        // Capped rather than unbounded - a very long question scrolls inside the field instead of
        // growing it indefinitely (the backend's own question limit is 1000 characters anyway).
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("workspace_composer_input"),
        shape = RoundedCornerShape(16.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColor.InkPrimary,
                unfocusedTextColor = AppColor.InkPrimary,
                disabledTextColor = AppColor.InkPrimary,
                focusedContainerColor = AppColor.Surface,
                unfocusedContainerColor = AppColor.Surface,
                disabledContainerColor = AppColor.Surface,
                focusedBorderColor = AppColor.Accent,
                unfocusedBorderColor = AppColor.Surface,
                disabledBorderColor = AppColor.Surface,
                cursorColor = AppColor.Accent,
            ),
    )

    // The mic is now real (see file header); the camera/evidence affordance stays reserved but
    // disabled - it represents a later inline capture shortcut, not this slice's scope.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
    ) {
      IconButton(
          onClick = onMicClick,
          enabled = micEnabled,
          modifier = Modifier.testTag("workspace_mic_button"),
      ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = if (isListening) "Listening" else "Voice input",
            tint = if (isListening) AppColor.Accent else AppColor.InkSecondary,
        )
      }
      Text(
          text = speechUiState.speakButtonLabel,
          color = if (isListening) AppColor.Accent else AppColor.InkSecondary,
          fontSize = 12.sp,
      )
      if (speechUiState.canCancel) {
        TextButton(onClick = onCancelListening, modifier = Modifier.testTag("workspace_mic_cancel")) {
          Text("Cancel", color = AppColor.Accent, fontSize = 12.sp)
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
      IconButton(onClick = {}, enabled = false) {
        Icon(Icons.Filled.CameraAlt, contentDescription = "Evidence capture - coming soon", tint = AppColor.InkSecondary)
      }
      Text(
          text = "Evidence capture - coming soon",
          color = AppColor.InkSecondary,
          fontSize = 12.sp,
      )
    }

    speechUiState.feedbackMessage?.let { message ->
      Text(
          text = message,
          color = if (speechUiState.phase == InvestigationSpeechUiPhase.ERROR) Color(0xFFFF9B9B) else AppColor.InkSecondary,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 4.dp).testTag("workspace_mic_status"),
      )
    }

    Button(
        onClick = onAskProject,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 12.dp).testTag("workspace_ask_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
    ) {
      if (isSubmitting) {
        CircularProgressIndicator(color = AppColor.AccentInk, modifier = Modifier.size(20.dp))
      } else {
        Text("Ask Project", fontWeight = FontWeight.SemiBold)
      }
    }

    val failure = askState as? ProjectAskState.Failed
    if (failure != null) {
      Text(
          text = failure.message,
          color = Color(0xFFFF9B9B),
          modifier = Modifier.padding(top = 10.dp).testTag("workspace_ask_error"),
      )
    }
  }
}

@Composable
private fun AskAnswerCard(question: String, answer: ProjectAskAnswer, modifier: Modifier = Modifier) {
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .padding(top = 20.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(AppColor.Surface)
              .padding(16.dp)
              .testTag("workspace_ask_answer"),
  ) {
    Text(
        text = "\"$question\"",
        color = AppColor.InkSecondary,
        fontSize = 13.sp,
    )
    Text(
        text = "PROJECT ASSISTANT",
        color = AppColor.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        text = answer.answer,
        color = AppColor.InkPrimary,
        modifier = Modifier.padding(top = 6.dp),
    )
  }
}

@Composable
private fun WorkspaceActions(onOpenCapture: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(top = 24.dp)) {
    Text(
        text = "PROJECT ACTIONS",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    OutlinedButton(
        onClick = onOpenCapture,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
    ) {
      Text("Capture / Test Glasses", fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun RecentActivityPreview(recentActivity: List<ProjectActivityEntry>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(top = 24.dp)) {
    Text(
        text = "RECENT",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
      if (recentActivity.isEmpty()) {
        Text(text = "No recent activity.", color = AppColor.InkSecondary)
      } else {
        // A short glance, not the full history - Project Detail already shows up to 5.
        recentActivity.take(3).forEach { entry ->
          Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier.padding(top = 7.dp).size(5.dp).clip(RoundedCornerShape(50)).background(AppColor.InkSecondary),
            )
            Text(
                text = entry.summary,
                color = AppColor.InkPrimary,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SecondarySections(onOpenProjectDetails: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.padding(top = 24.dp, bottom = 24.dp)) {
    // Photos & Evidence and Investigation History have no real destination or real backend count
    // yet (Android's Project Activity parsing doesn't currently distinguish activity types/media
    // - see file header) - clearly non-functional placeholder rows rather than fabricated counts
    // or fake navigation. Project Details is real: it opens this same Project's already-built
    // Detail screen (the same destination "‹ Overview" above uses).
    SecondarySectionRow(label = "Photos & Evidence", subtitle = "Coming soon")
    SecondarySectionRow(label = "Investigation History", subtitle = "Coming soon")
    SecondarySectionRow(label = "Project Details", subtitle = "View full project state", onClick = onOpenProjectDetails)
  }
}

@Composable
private fun SecondarySectionRow(
    label: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
  var rowModifier =
      modifier
          .fillMaxWidth()
          .padding(top = 8.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(AppColor.Surface)
  if (onClick != null) {
    rowModifier = rowModifier.clickable(onClick = onClick)
  }
  Row(
      modifier = rowModifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = label, color = AppColor.InkPrimary, fontWeight = FontWeight.Medium)
      Text(text = subtitle, color = AppColor.InkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    if (onClick != null) {
      Text(text = "›", color = AppColor.InkSecondary, fontSize = 18.sp)
    }
  }
}
