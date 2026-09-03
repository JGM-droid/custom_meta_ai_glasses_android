/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudAnalysisEligibility
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudPhoneDestination
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudTrustAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.BackendTrustDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationClientState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.deriveInvestigationProductState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.hasActiveInvestigation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationReopenAffordanceLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    // Explicit Project attribution carried in from Workspace via AppRoot/CameraAccessScaffold -
    // see CameraAccessScaffold.kt. Null for the existing global Capture entry point.
    sourceProjectId: String? = null,
    sourceProjectName: String? = null,
    continuationSessionId: String? = null,
    onReturnToSourceProject: (() -> Unit)? = null,
    // continuationSessionId carried alongside destination is exactly this composable's OWN
    // continuationSessionId param (see LaunchedEffect(projectHudPhoneHandoff) below) - the ProjectHudPhoneHandoff
    // itself only ever knows a bare Project id (ProjectContinuityHudController never sees the
    // Investigation ViewModel's session state - see class doc), so the caller re-attaches
    // whatever continuation this exact Capture entry was already using, letting the phone side
    // resolve the SAME investigationViewModelKey instance instead of a fresh, empty one.
    onProjectHudPhoneHandoff: ((destination: ProjectHudPhoneDestination, continuationSessionId: String?) -> Unit)? = null,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    investigationViewModel: InvestigationSessionDebugViewModel =
        viewModel(
            // Keyed by sourceProjectId (not just the class name) so a Capture session entered
            // from a different Project - or from the unscoped global entry point - always gets a
            // fresh ViewModel rather than reusing a stale one still carrying a PREVIOUS
            // sourceProjectId. Without this, Compose's default class-name-only key would let one
            // Capture session's Project attribution leak into the next (the same class of bug
            // already found and fixed for NewProjectViewModel in an earlier slice).
            key = investigationViewModelKey(sourceProjectId, continuationSessionId),
            factory =
                InvestigationSessionDebugViewModel.factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    sourceProjectId = sourceProjectId,
                    initialContinuationSessionId = continuationSessionId,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val projectHudPhoneHandoff by streamViewModel.projectHudPhoneHandoff.collectAsStateWithLifecycle()
  val hudCaptureAcceptRequest by streamViewModel.hudCaptureAcceptRequest.collectAsStateWithLifecycle()
  val hudAnalyzeTrigger by streamViewModel.hudAnalyzeTrigger.collectAsStateWithLifecycle()
  val hudTrustDecisionRequest by streamViewModel.hudTrustDecisionRequest.collectAsStateWithLifecycle()
  val investigationUiState by investigationViewModel.uiState.collectAsStateWithLifecycle()
  // hasEvidence/hasExplanation exist only so the HUD can explain a false canAnalyze instead of
  // silently omitting Analyze - see ProjectHudAnalysisEligibility's doc.
  val hudAnalysisEligibility = remember(investigationUiState) {
    val productState = deriveInvestigationProductState(investigationUiState)
    ProjectHudAnalysisEligibility(
        canAnalyze = productState.canAnalyze,
        hasEvidence = productState.capturedViewCount > 0,
        hasExplanation = productState.hasExplanation,
    )
  }
  val showInvestigationReopenAffordance =
      remember(streamUiState.isInvestigationPanelVisible, streamUiState.isShareDialogVisible, investigationUiState) {
        !streamUiState.isInvestigationPanelVisible &&
            !streamUiState.isShareDialogVisible &&
            hasActiveInvestigation(investigationUiState)
      }
  val investigationReopenLabel = remember(investigationUiState) {
    investigationReopenAffordanceLabel(investigationUiState)
  }
  val investigationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner, streamViewModel) {
    val observer = createStreamLifecycleStopObserver { streamViewModel.stopStream() }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(sourceProjectId, sourceProjectName) {
    // Configure the explicit Project before starting the one shared DeviceSession, so the HUD
    // can attach to that session as soon as it reaches STARTED.
    streamViewModel.configureProjectHud(sourceProjectId, sourceProjectName)
    streamViewModel.startStream()
  }

  LaunchedEffect(projectHudPhoneHandoff) {
    projectHudPhoneHandoff?.let { handoff ->
      // Defense in depth: an event from an old Project can never navigate the current Project.
      if (handoff.projectId == sourceProjectId) {
        streamViewModel.stopStream()
        onProjectHudPhoneHandoff?.invoke(handoff.destination, continuationSessionId)
      }
      streamViewModel.consumeProjectHudPhoneHandoff(handoff)
    }
  }

  LaunchedEffect(hudCaptureAcceptRequest) {
    // The one place both ViewModels are in scope together - see StreamViewModel's doc on
    // onHudUseRequested/onHudCaptureAccepted. Purely local (InvestigationSessionDebugViewModel's
    // evidence slots are in-memory Compose state; no backend call happens here), so this is a
    // synchronous hand-off, not a network round trip.
    hudCaptureAcceptRequest?.let { evidence ->
      val appended = investigationViewModel.appendLiveEvidence(evidence)
      streamViewModel.onHudCaptureAccepted(appended)
    }
  }

  LaunchedEffect(hudAnalysisEligibility) { streamViewModel.updateHudAnalysisEligibility(hudAnalysisEligibility) }

  LaunchedEffect(hudAnalyzeTrigger) {
    // 0 is the initial value, never a real request - see StreamViewModel's doc on
    // onHudAnalyzeRequested. submitInvestigation() reuses the exact same existing Analyze call the
    // phone panel's "Analyze investigation" button already makes; join() waits for THIS run's Job
    // specifically, so the result read afterward can never be a stale prior run's.
    if (hudAnalyzeTrigger == 0L) return@LaunchedEffect
    investigationViewModel.submitInvestigation()?.join()
    val finalState = investigationViewModel.uiState.value
    streamViewModel.onHudAnalyzeCompleted(
        success = finalState.clientState == InvestigationClientState.COMPLETED,
        message = finalState.statusMessage,
    )
  }

  LaunchedEffect(hudTrustDecisionRequest) {
    // Reuses the exact same existing submitTrustDecision() the phone panel's trust buttons call,
    // just with an explicit session_id (the one the HUD's pending review is actually about - see
    // InvestigationSessionDebugViewModel's doc on that parameter) instead of implicitly trusting
    // whatever session this ViewModel's own uiState currently happens to hold.
    hudTrustDecisionRequest?.let { request ->
      val decision =
          when (request.action) {
            ProjectHudTrustAction.KEEP_AS_HYPOTHESIS -> BackendTrustDecision.CONTINUE
            ProjectHudTrustAction.ADD_EVIDENCE -> BackendTrustDecision.MORE_EVIDENCE
            ProjectHudTrustAction.RETURN -> BackendTrustDecision.DISAGREE
          }
      investigationViewModel.submitTrustDecision(decision, request.sessionId)?.join()
      val finalState = investigationViewModel.uiState.value
      streamViewModel.onHudTrustDecisionCompleted(
          success = !finalState.trustDecisionInFlight && finalState.backendErrorCategory == null,
          message = finalState.trustMessage,
      )
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { videoFrame ->
      // Use key() to force recomposition when frame counter changes,
      // even if the bitmap reference is the same (due to caching optimization)
      key(streamUiState.videoFrameCount) {
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamState == StreamState.STARTING) {
      Column(
          modifier = Modifier.align(Alignment.Center),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator()
        Text("Connecting to glasses camera…")
      }
    }

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      sourceProjectName?.let { projectName ->
        Text(
            text = "Working on $projectName",
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )
      }
      // Visible, discoverable exit from this screen. The Android system Back button/gesture is
      // deliberately disabled for the whole time a stream is active (see AppRoot's
      // canGoBack/BackHandler) so a stream is never torn down implicitly - but that leaves
      // "Stop streaming" below (which only detaches the camera and drops into device selection,
      // not back to the Project) as the sole visible control, with no direct way back to the
      // Project that opened this screen. "Done" reuses the exact same stopStream() teardown
      // "Stop streaming" and the Investigation panel's own return-to-Project action already use -
      // no second teardown mechanism - then goes to onReturnToSourceProject when this session was
      // opened from a Project, or falls back to the existing unscoped device-selection behavior
      // otherwise, matching AppRoot's own Back semantics for the unscoped Capture entry point.
      TextButton(
          onClick = {
            streamViewModel.stopStream()
            val returnToProject = onReturnToSourceProject
            if (returnToProject != null) {
              returnToProject()
            } else {
              wearablesViewModel.navigateToDeviceSelection()
            }
          },
          modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("stream_done_button"),
      ) {
        Text(if (sourceProjectName != null) "Done" else "Back")
      }
      Column(
          modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        streamUiState.captureErrorMessage?.let { message ->
          Text(text = message)
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          SwitchButton(
              label = stringResource(R.string.stop_stream_button_title),
              onClick = {
                streamViewModel.stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              },
              isDestructive = true,
              modifier = Modifier.weight(1f),
          )

          // Photo capture button
          CaptureButton(
              onClick = { streamViewModel.capturePhoto() },
              enabled = streamUiState.streamState == StreamState.STREAMING,
          )
        }
      }
    }

    if (streamUiState.isInvestigationPanelVisible) {
      ModalBottomSheet(
          onDismissRequest = { streamViewModel.hideInvestigationPanel() },
          sheetState = investigationSheetState,
      ) {
        BackendInvestigationPanel(
            modifier = Modifier.fillMaxWidth(),
            prefillLiveEvidence = streamUiState.capturedInvestigationEvidence,
            viewModel = investigationViewModel,
            sourceProjectName = sourceProjectName,
            onReturnToProject =
                onReturnToSourceProject?.let { returnToProject ->
                  {
                    streamViewModel.stopStream()
                    returnToProject()
                  }
                },
            onCaptureAnotherView = {
              streamViewModel.prepareForAdditionalInvestigationCapture()
            },
            onPrefillApplied = {
              streamViewModel.consumeCapturedInvestigationEvidence()
            },
        )
      }
    }

    if (showInvestigationReopenAffordance) {
      Button(
          onClick = { streamViewModel.showInvestigationPanel() },
          modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
      ) { Text("Resume $investigationReopenLabel") }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
          onContinueToInvestigation = {
            streamViewModel.hideShareDialog()
            streamViewModel.showInvestigationPanel()
          },
      )
    }
  }
}
