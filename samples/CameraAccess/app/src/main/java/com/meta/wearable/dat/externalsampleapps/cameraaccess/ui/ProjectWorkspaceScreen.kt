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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectIdeaOption
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectIdeasState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectProgressState
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
  val ideasState by viewModel.ideasState.collectAsState()
  val progressState by viewModel.progressState.collectAsState()

  // Composer text - reset whenever this composable is instantiated fresh for a different
  // project_id (a brand-new `remember` scope), so Project A's typed question can never bleed
  // into Project B's Workspace. askState itself is scoped per-project too, since viewModel is
  // keyed by project.projectId below - a brand-new ProjectDetailViewModel (and Idle askState)
  // is created per distinct Project automatically.
  var draftText by remember(project.projectId) { mutableStateOf("") }
  var showContinue by remember(project.projectId) { mutableStateOf(false) }
  var showIdeas by remember(project.projectId) { mutableStateOf(false) }
  var ideasIntent by remember(project.projectId) { mutableStateOf("") }
  var showProgress by remember(project.projectId) { mutableStateOf(false) }
  var progressSummary by remember(project.projectId) { mutableStateOf("") }
  var progressDetails by remember(project.projectId) { mutableStateOf("") }
  var progressCurrentWork by remember(project.projectId) { mutableStateOf("") }
  var progressBlockers by remember(project.projectId) { mutableStateOf("") }
  var progressNextAction by remember(project.projectId) { mutableStateOf("") }

  // Cleared only once a real answer comes back - never on submit (nothing to lose if it fails)
  // and never on failure (the question must stay editable for retry).
  LaunchedEffect(askState) {
    if (askState is ProjectAskState.Answered) {
      draftText = ""
    }
  }

  LaunchedEffect(progressState) {
    if (progressState is ProjectProgressState.Saved) {
      progressSummary = ""
      progressDetails = ""
      progressCurrentWork = ""
      progressBlockers = ""
      progressNextAction = ""
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

        WorkspaceActions(
            overview = overview,
            showContinue = showContinue,
            onContinue = { showContinue = true },
            onOpenCapture = onOpenCapture,
            onRecordProgress = {
              showProgress = true
              viewModel.editProgressDraft()
            },
            onGetIdeas = {
              showIdeas = true
              viewModel.loadIdeas()
            },
        )

        if (showProgress) {
          RecordProgressPanel(
              checkpoint = overview.checkpoint,
              summary = progressSummary,
              details = progressDetails,
              currentWork = progressCurrentWork,
              blockers = progressBlockers,
              nextAction = progressNextAction,
              state = progressState,
              onSummaryChange = {
                progressSummary = it
                viewModel.editProgressDraft()
              },
              onDetailsChange = {
                progressDetails = it
                viewModel.editProgressDraft()
              },
              onCurrentWorkChange = {
                progressCurrentWork = it
                viewModel.editProgressDraft()
              },
              onBlockersChange = {
                progressBlockers = it
                viewModel.editProgressDraft()
              },
              onNextActionChange = {
                progressNextAction = it
                viewModel.editProgressDraft()
              },
              onPreview = {
                viewModel.previewProgress(
                    progressSummary,
                    progressDetails,
                    progressCurrentWork,
                    progressBlockers,
                    progressNextAction,
                )
              },
              onSave = viewModel::saveProgress,
          )
        }

        if (showIdeas) {
          ProjectIdeasPanel(
              intent = ideasIntent,
              onIntentChange = { ideasIntent = it },
              state = ideasState,
              onGenerate = { viewModel.generateIdeas(ideasIntent) },
              onDisposition = viewModel::setIdeaDisposition,
              onPromote = viewModel::promoteIdea,
          )
        }

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
        text = "WHAT DO YOU NEED HELP WITH?",
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
        placeholder = { Text("Ask about this Project...", color = AppColor.InkSecondary) },
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

    // Voice is only another way to fill the same read-only Ask draft.
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
    Text(
        text = "Answers use information already saved with this Project. External research, media, and instructions are not available yet.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
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
        text = "AI ANSWER — BASED ON SAVED PROJECT INFORMATION",
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
    val groundingLabel =
        when (answer.groundingStatus) {
          "grounded" -> "Grounded in saved Project information"
          "partial" -> "Partially grounded — some information may be missing"
          "insufficient_context" -> "Not enough saved Project information"
          else -> "Grounding status unavailable"
        }
    Text(
        text = groundingLabel,
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp).testTag("workspace_ask_grounding"),
    )
    if (answer.insufficientContext || !answer.uncertaintyNote.isNullOrBlank()) {
      Text(
          text =
              answer.uncertaintyNote
                  ?: "This Project does not contain enough saved information for a complete answer.",
          color = AppColor.InkSecondary,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 8.dp).testTag("workspace_ask_uncertainty"),
      )
    }
    if (answer.referenceSummaries.isNotEmpty()) {
      Text(
          text = "Based on: ${answer.referenceSummaries.take(3).joinToString(" · ")}",
          color = AppColor.InkSecondary,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 8.dp).testTag("workspace_ask_provenance"),
      )
    }
    Text(
        text = "This answer does not change your Project.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp).testTag("workspace_ask_no_mutation"),
    )
  }
}

@Composable
private fun WorkspaceActions(
    overview: ProjectOverview,
    showContinue: Boolean,
    onContinue: () -> Unit,
    onOpenCapture: () -> Unit,
    onRecordProgress: () -> Unit,
    onGetIdeas: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(top = 24.dp)) {
    Text(
        text = "PROJECT ACTIONS",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_continue_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
    ) {
      Text("Continue where I left off", fontWeight = FontWeight.SemiBold)
    }
    OutlinedButton(
        onClick = onRecordProgress,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_record_progress_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
    ) {
      Text("Record progress", fontWeight = FontWeight.SemiBold)
    }
    OutlinedButton(
        onClick = onOpenCapture,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_add_photos_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
    ) {
      Text("Add photos", fontWeight = FontWeight.SemiBold)
    }
    OutlinedButton(
        onClick = onGetIdeas,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_get_ideas_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColor.Accent),
    ) {
      Text("Get ideas", fontWeight = FontWeight.SemiBold)
    }
    if (showContinue) {
      Text(
          text = "Where you left off: ${overview.checkpoint.whereWeLeftOff ?: "No current work recorded."}\nNext: ${overview.checkpoint.nextAction ?: "No next action recorded."}",
          color = AppColor.InkPrimary,
          modifier = Modifier.padding(top = 12.dp).testTag("workspace_continue_summary"),
      )
    }
  }
}

@Composable
private fun RecordProgressPanel(
    checkpoint: com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectCheckpoint,
    summary: String,
    details: String,
    currentWork: String,
    blockers: String,
    nextAction: String,
    state: ProjectProgressState,
    onSummaryChange: (String) -> Unit,
    onDetailsChange: (String) -> Unit,
    onCurrentWorkChange: (String) -> Unit,
    onBlockersChange: (String) -> Unit,
    onNextActionChange: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
  val locked = state is ProjectProgressState.Previewing || state is ProjectProgressState.Saving
  Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag("record_progress_panel")) {
    Text("RECORD PROJECT PROGRESS", color = AppColor.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Your note is saved as Project history. Suggested current work, blockers, or next action still require separate approval.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
    ProgressField("What happened?", summary, onSummaryChange, locked, "record_progress_summary")
    ProgressField("Details (optional)", details, onDetailsChange, locked, "record_progress_details")
    ProgressField("Update current work (optional)", currentWork, onCurrentWorkChange, locked, "record_progress_current_work")
    ProgressField("Update blockers (optional)", blockers, onBlockersChange, locked, "record_progress_blockers")
    ProgressField("Update next action (optional)", nextAction, onNextActionChange, locked, "record_progress_next_action")

    when (state) {
      ProjectProgressState.Idle -> Unit
      ProjectProgressState.Previewing -> Text("Preparing preview…", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 10.dp))
      is ProjectProgressState.PreviewReady, is ProjectProgressState.Saving -> {
        val preview = when (state) {
          is ProjectProgressState.PreviewReady -> state.preview
          is ProjectProgressState.Saving -> state.preview
          else -> error("unreachable")
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(AppColor.Surface).padding(14.dp).testTag("record_progress_preview")) {
          Text("Review before saving", color = AppColor.InkPrimary, fontWeight = FontWeight.SemiBold)
          Text("Project history: ${preview.summary}", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 6.dp))
          preview.details?.let { Text(it, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 4.dp)) }
          Text(
              if (preview.proposalRequired) "Suggested Project changes will be created for separate Apply or Reject review."
              else "No Project checkpoint change will be proposed.",
              color = AppColor.InkSecondary,
              modifier = Modifier.padding(top = 6.dp),
          )
          preview.effectiveCheckpointPatch?.let { patch ->
            patch.currentWork?.let {
              Text("Where we left off: ${checkpoint.whereWeLeftOff ?: "Not set"} → $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp))
            }
            patch.blockers?.let {
              Text("Blockers: ${checkpoint.blockers ?: "None"} → $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp))
            }
            patch.nextAction?.let {
              Text("Next action: ${checkpoint.nextAction ?: "Not set"} → $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp))
            }
          }
        }
      }
      is ProjectProgressState.Saved -> Text(
          if (state.reconstructed) "Progress was already saved; current Project state was reloaded."
          else "Progress saved. Current Project state was reloaded.",
          color = AppColor.Success,
          modifier = Modifier.padding(top = 10.dp).testTag("record_progress_saved"),
      )
      is ProjectProgressState.Failed -> Text(state.message, color = Color(0xFFFF9B9B), modifier = Modifier.padding(top = 10.dp))
    }

    val canPreview = summary.isNotBlank() && !locked && state !is ProjectProgressState.PreviewReady
    Button(
        onClick = onPreview,
        enabled = canPreview,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("record_progress_preview_button"),
    ) { Text("Preview") }
    val canSave = state is ProjectProgressState.PreviewReady || (state is ProjectProgressState.Failed && state.preview != null)
    Button(
        onClick = onSave,
        enabled = canSave,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("record_progress_save_button"),
    ) {
      if (state is ProjectProgressState.Saving) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Save progress")
    }
  }
}

@Composable
private fun ProgressField(label: String, value: String, onValueChange: (String) -> Unit, locked: Boolean, tag: String) {
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = !locked,
      label = { Text(label) },
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag),
  )
}

@Composable
private fun ProjectIdeasPanel(
    intent: String,
    onIntentChange: (String) -> Unit,
    state: ProjectIdeasState,
    onGenerate: () -> Unit,
    onDisposition: (String, String) -> Unit,
    onPromote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val busy = state is ProjectIdeasState.Loading
  Column(modifier = modifier.fillMaxWidth().padding(top = 24.dp).testTag("workspace_ideas_panel")) {
    Text("IDEAS FOR THIS PROJECT", color = AppColor.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "AI suggestions stay unconfirmed. A preference, Roadmap item, and applied Project change are separate.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
    OutlinedTextField(
        value = intent,
        onValueChange = onIntentChange,
        enabled = !busy,
        placeholder = { Text("For example: Give me three directions for this room") },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_ideas_input"),
    )
    Button(
        onClick = onGenerate,
        enabled = intent.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace_generate_ideas_button"),
    ) {
      if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Get ideas")
    }
    when (state) {
      ProjectIdeasState.Idle -> Unit
      ProjectIdeasState.Loading -> Text("Loading Project ideas…", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 10.dp))
      is ProjectIdeasState.Failed -> Text(state.message, color = Color(0xFFFF9B9B), modifier = Modifier.padding(top = 10.dp))
      is ProjectIdeasState.Ready -> {
        state.message?.let { Text(it, color = AppColor.Success, modifier = Modifier.padding(top = 10.dp)) }
        if (state.projection.options.isEmpty()) {
          Text("No saved suggestions yet.", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 10.dp))
        } else {
          state.projection.options.forEach { option ->
            ProjectIdeaCard(
                option = option,
                preferred = state.projection.preferredIdeaId == option.ideaId,
                onDisposition = onDisposition,
                onPromote = onPromote,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProjectIdeaCard(
    option: ProjectIdeaOption,
    preferred: Boolean,
    onDisposition: (String, String) -> Unit,
    onPromote: (String) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(AppColor.Surface).padding(14.dp)) {
    Text("${option.ordinal}. ${option.summary}${if (preferred) " · Preferred direction" else ""}", color = AppColor.InkPrimary, fontWeight = FontWeight.SemiBold)
    option.details?.let { Text(it, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 4.dp)) }
    Text("AI suggestion — unconfirmed · ${option.disposition ?: "No decision yet"}", color = AppColor.InkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
      TextButton(onClick = { onDisposition(option.ideaId, "keep") }) { Text("Keep for consideration") }
      TextButton(onClick = { onDisposition(option.ideaId, "dismiss") }) { Text("Dismiss") }
      TextButton(onClick = { onDisposition(option.ideaId, "select") }) { Text("Choose as preferred direction") }
    }
    OutlinedButton(onClick = { onPromote(option.ideaId) }, enabled = !option.promoted, modifier = Modifier.fillMaxWidth()) {
      Text(if (option.promoted) "Added to Roadmap" else "Add to Roadmap")
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
