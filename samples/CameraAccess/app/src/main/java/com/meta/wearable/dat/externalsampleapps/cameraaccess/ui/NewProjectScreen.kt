/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// NewProjectScreen - real Project creation (POST /projects)
//
// A deliberately simple mobile form: Project Name and Goal (required, matching the backend's
// ProjectCreateRequest), plus optional Current Objective / Next Action (matching
// ProjectCheckpoint.current_objective/next_action exactly - no invented fields). Submitting
// calls the real FastAPI backend via NewProjectViewModel -> ProjectRepository.createProject.
//
// On success, onCreated hands the backend-created ProjectSummary (with the backend's own
// project_id) to AppRoot, which navigates straight to that Project's real Project Detail screen
// - this screen never fabricates or locally injects a Project.
//
// Create Project gating: the button is disabled (not just clickable-then-rejected) until
// isNewProjectFormValid(name, goal) is true, and again while a voice session is listening (a
// transcript that lands mid-submit could silently change what gets created) - matching the same
// "don't let voice and submit race" rule ProjectWorkspaceScreen's Ask composer already uses.
// NewProjectViewModel's own blank-field checks stay in place as a defense-in-depth backstop, not
// as the primary gating mechanism.
//
// Voice-to-Text: mic buttons reuse the EXISTING Android SpeechRecognizer plumbing built for the
// Investigation flow - InvestigationSpeechRecognizerController and the pure
// reduceInvestigationSpeechState/InvestigationSpeechEvent/InvestigationSpeechUiState state
// machine (investigation/InvestigationSpeechState.kt) - the same reuse ProjectWorkspaceScreen's
// Ask composer and Record Progress fields already validated, never a second speech subsystem. A
// single shared speechController/speechUiState serves all four fields; voiceTarget
// (NewProjectVoiceTarget) records which one field the next transcript belongs to, so one
// recognizer session can safely serve four destinations without ever mixing them up. Leaving this
// screen (Back, or navigating into the newly created Project) destroys the recognizer
// (DisposableEffect below), so a transcript that lands after that point has nothing left to write
// into - it can never affect a field on a different screen.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectSubmitState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

// internal (not public): speechControllerFactory's parameter type (InvestigationSpeechRecognizerController,
// the test seam - see file header) is itself internal, and Kotlin forbids an internal type in a
// public signature. Matches ProjectWorkspaceScreen's own `internal fun` for the same reason;
// AppRoot.kt (the only caller) is in the same module/package, so this loses nothing.
@Composable
internal fun NewProjectScreen(
    onBack: () -> Unit,
    onCreated: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewProjectViewModel =
        viewModel(
            factory =
                NewProjectViewModel.factory(
                    LocalContext.current.applicationContext as Application,
                ),
        ),
    // Test seam, mirrors ProjectWorkspaceScreen's speechControllerFactory: production defaults to
    // the real on-device SpeechRecognizer via the existing Investigation controller factory.
    speechControllerFactory: (Context) -> InvestigationSpeechRecognizerController? =
        ::createInvestigationSpeechRecognizerController,
) {
  val submitState by viewModel.submitState.collectAsState()
  var name by remember { mutableStateOf("") }
  var goal by remember { mutableStateOf("") }
  var currentObjective by remember { mutableStateOf("") }
  var nextAction by remember { mutableStateOf("") }

  LaunchedEffect(submitState) {
    val state = submitState
    if (state is NewProjectSubmitState.Succeeded) {
      // Consume before navigating - this ViewModel is Activity-scoped and would otherwise
      // replay this exact stale Succeeded value (and re-fire onCreated with THIS project) the
      // next time this screen is remounted to create a different Project.
      viewModel.acknowledgeSuccess()
      onCreated(state.project)
    }
  }

  // Voice-to-text plumbing - see file header. Unkeyed (this screen has no project_id yet, unlike
  // ProjectWorkspaceScreen): a fresh mount of this screen always gets a fresh controller/state via
  // normal Compose disposal, and DisposableEffect below tears the recognizer down the moment this
  // screen leaves composition for any reason (Back, or a successful create navigating onward).
  val context = LocalContext.current
  val speechController = remember(context) { speechControllerFactory(context) }
  var speechUiState by remember { mutableStateOf(InvestigationSpeechUiState()) }

  // Which of this form's four fields the next transcript should land in. Set synchronously the
  // moment a mic button is tapped (before the permission request even resolves), so a transcript
  // that arrives later can never land in the wrong field - only one speech session can ever be in
  // flight at a time (the recognizer itself, and every mic button below, both enforce that).
  var voiceTarget by remember { mutableStateOf<NewProjectVoiceTarget?>(null) }

  DisposableEffect(speechController) {
    onDispose { speechController?.destroy() }
  }

  // Voice is an input method only: a transcript ever does exactly one thing - update the local
  // draft state for whichever field requested it. It never calls viewModel.submit() itself and
  // never touches the backend. Append rather than overwrite when that field already has text, so
  // a spoken addition never destroys what the user already typed.
  val onSpeechEvent: (InvestigationSpeechEvent) -> Unit = { event ->
    val transition = reduceInvestigationSpeechState(speechUiState, event)
    speechUiState = transition.state
    transition.transcript?.let { transcript ->
      when (voiceTarget) {
        NewProjectVoiceTarget.NAME -> name = appendTranscriptToDraft(name, transcript)
        NewProjectVoiceTarget.GOAL -> goal = appendTranscriptToDraft(goal, transcript)
        NewProjectVoiceTarget.CURRENT_OBJECTIVE ->
            currentObjective = appendTranscriptToDraft(currentObjective, transcript)
        NewProjectVoiceTarget.NEXT_ACTION ->
            nextAction = appendTranscriptToDraft(nextAction, transcript)
        null -> Unit
      }
    }
  }

  val startSpeechCapture: () -> Unit = {
    if (speechController == null) {
      onSpeechEvent(
          InvestigationSpeechEvent.UnknownError("Speech recognition is unavailable on this device."),
      )
    } else {
      speechController.startListening(onSpeechEvent)
    }
  }

  val microphonePermissionLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
          granted ->
        if (granted) {
          startSpeechCapture()
        } else {
          onSpeechEvent(InvestigationSpeechEvent.PermissionDenied)
        }
      }

  // Shared entry point for every mic button on this form: record which field is asking before
  // requesting permission/starting the recognizer, so onSpeechEvent above always has the right
  // destination.
  val requestVoiceCapture: (NewProjectVoiceTarget) -> Unit = { target ->
    voiceTarget = target
    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
  }

  val cancelListening: () -> Unit = {
    speechController?.cancel()
    onSpeechEvent(InvestigationSpeechEvent.Cancelled)
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
      Text("‹ Projects")
    }

    Text(
        text = "Create New Project",
        color = AppColor.InkPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = "Start a new project. You can add more detail later.",
        color = AppColor.InkSecondary,
        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    ProjectFormField(
        label = "Project Name",
        value = name,
        onValueChange = { name = it },
        placeholder = "e.g. Garage Door Sensor",
        voiceTarget = NewProjectVoiceTarget.NAME,
        activeVoiceTarget = voiceTarget,
        speechUiState = speechUiState,
        onMicClick = requestVoiceCapture,
        onCancelListening = cancelListening,
        tag = "new_project_name",
    )
    ProjectFormField(
        label = "Goal",
        value = goal,
        onValueChange = { goal = it },
        placeholder = "What are you trying to accomplish?",
        singleLine = false,
        voiceTarget = NewProjectVoiceTarget.GOAL,
        activeVoiceTarget = voiceTarget,
        speechUiState = speechUiState,
        onMicClick = requestVoiceCapture,
        onCancelListening = cancelListening,
        tag = "new_project_goal",
        modifier = Modifier.padding(top = 16.dp),
    )
    ProjectFormField(
        label = "Current Objective (optional)",
        value = currentObjective,
        onValueChange = { currentObjective = it },
        placeholder = "What are you focused on right now?",
        voiceTarget = NewProjectVoiceTarget.CURRENT_OBJECTIVE,
        activeVoiceTarget = voiceTarget,
        speechUiState = speechUiState,
        onMicClick = requestVoiceCapture,
        onCancelListening = cancelListening,
        tag = "new_project_current_objective",
        modifier = Modifier.padding(top = 16.dp),
    )
    ProjectFormField(
        label = "Next Action (optional)",
        value = nextAction,
        onValueChange = { nextAction = it },
        placeholder = "What's the next concrete step?",
        voiceTarget = NewProjectVoiceTarget.NEXT_ACTION,
        activeVoiceTarget = voiceTarget,
        speechUiState = speechUiState,
        onMicClick = requestVoiceCapture,
        onCancelListening = cancelListening,
        tag = "new_project_next_action",
        modifier = Modifier.padding(top = 16.dp),
    )

    val failure = submitState as? NewProjectSubmitState.Failed
    if (failure != null) {
      Text(
          text = failure.message,
          color = Color(0xFFFF9B9B),
          modifier = Modifier.padding(top = 16.dp).testTag("new_project_error"),
      )
    }

    val isFormValid = isNewProjectFormValid(name, goal)
    val isSubmitting = submitState is NewProjectSubmitState.Submitting
    // Blocked while voice is listening for the same reason ProjectWorkspaceScreen's Ask composer
    // blocks its own submit: a transcript that lands mid-submit could silently change what gets
    // created after the request already went out.
    val voiceSessionActive = speechUiState.phase == InvestigationSpeechUiPhase.LISTENING
    Button(
        onClick = { viewModel.submit(name, goal, currentObjective, nextAction) },
        enabled = isFormValid && !isSubmitting && !voiceSessionActive,
        modifier =
            Modifier.fillMaxWidth().height(52.dp).padding(top = 24.dp).testTag("new_project_create_button"),
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = AppColor.Accent,
                contentColor = AppColor.AccentInk,
            ),
    ) {
      if (isSubmitting) {
        CircularProgressIndicator(color = AppColor.AccentInk, modifier = Modifier.size(20.dp))
      } else {
        Text("Create Project", fontWeight = FontWeight.SemiBold)
      }
    }

    if (!isFormValid && !isSubmitting) {
      Text(
          text = "Project Name and Goal are required.",
          color = AppColor.InkSecondary,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 8.dp),
      )
    }
    Spacer(modifier = Modifier.height(24.dp))
  }
}

/** Every field on this form a voice transcript can land in. Exactly one is ever "active" (see
 * voiceTarget in NewProjectScreen above) - this is what lets one shared speech session safely
 * serve four text fields without ever mixing them up. */
internal enum class NewProjectVoiceTarget {
  NAME,
  GOAL,
  CURRENT_OBJECTIVE,
  NEXT_ACTION,
}

/**
 * Pure, unit-testable form-gating check for the Create Project button: both required fields
 * (matching NewProjectViewModel's own blank-name/blank-goal checks) must have non-blank content.
 */
internal fun isNewProjectFormValid(name: String, goal: String): Boolean =
    name.isNotBlank() && goal.isNotBlank()

@Composable
private fun ProjectFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    voiceTarget: NewProjectVoiceTarget,
    activeVoiceTarget: NewProjectVoiceTarget?,
    speechUiState: InvestigationSpeechUiState,
    onMicClick: (NewProjectVoiceTarget) -> Unit,
    onCancelListening: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
  val isThisFieldListening =
      activeVoiceTarget == voiceTarget && speechUiState.phase == InvestigationSpeechUiPhase.LISTENING
  // The on-device recognizer only serves one field at a time - every other field's mic stays
  // idle-styled and disabled while a session targeting a different field is already in flight.
  val micEnabled = speechUiState.phase != InvestigationSpeechUiPhase.LISTENING
  Column(modifier = modifier) {
    Text(
        text = label.uppercase(),
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = AppColor.InkSecondary) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        trailingIcon = {
          IconButton(
              onClick = { onMicClick(voiceTarget) },
              enabled = micEnabled,
              modifier = Modifier.testTag("${tag}_mic"),
          ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = if (isThisFieldListening) "Listening" else "Voice input for $label",
                tint = if (isThisFieldListening) AppColor.Accent else AppColor.InkSecondary,
            )
          }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(tag),
        shape = RoundedCornerShape(12.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColor.InkPrimary,
                unfocusedTextColor = AppColor.InkPrimary,
                focusedContainerColor = AppColor.Surface,
                unfocusedContainerColor = AppColor.Surface,
                focusedBorderColor = AppColor.Accent,
                unfocusedBorderColor = AppColor.Surface,
                cursorColor = AppColor.Accent,
            ),
    )
    if (activeVoiceTarget == voiceTarget) {
      speechUiState.feedbackMessage?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = message,
              color = if (speechUiState.phase == InvestigationSpeechUiPhase.ERROR) Color(0xFFFF9B9B) else AppColor.InkSecondary,
              fontSize = 12.sp,
              modifier = Modifier.padding(top = 2.dp).testTag("${tag}_mic_status"),
          )
          if (speechUiState.canCancel) {
            TextButton(onClick = onCancelListening, modifier = Modifier.testTag("${tag}_mic_cancel")) {
              Text("Cancel", color = AppColor.Accent, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}
