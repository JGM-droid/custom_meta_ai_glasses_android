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

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.util.Log
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.window.Dialog
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationInteractionContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.deriveInvestigationProductState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.conversationAcceptedCaptureLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.hasActiveInvestigation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationReopenAffordanceLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

private const val TAG = "CameraAccess:StreamScreen"

/**
 * ADR-061 conversation handoff (Blocker 1): whether StreamScreen's legacy per-capture
 * Share/Continue-to-Investigation dialog should be shown. Extracted as a small, pure,
 * directly-testable unit for the same reason StreamViewModel's own resolveIfStillPending is (see
 * its doc) - StreamScreen itself needs a live DAT DeviceSession/Compose host and isn't practically
 * unit-testable at this level, but this decision is. [isShareDialogVisible] is StreamViewModel's
 * own existing signal (a photo was just captured and not yet consumed by the HUD's Use/Retake
 * flow) - [returnToConversation] simply forces it off regardless, since a Project-conversation
 * glasses session must never surface this legacy phone dialog at all.
 */
internal fun shouldShowShareDialog(returnToConversation: Boolean, isShareDialogVisible: Boolean): Boolean =
    !returnToConversation && isShareDialogVisible

/** Same reasoning as [shouldShowShareDialog], for the Investigation trust/Analyze bottom sheet. */
internal fun shouldShowInvestigationPanel(returnToConversation: Boolean, isInvestigationPanelVisible: Boolean): Boolean =
    !returnToConversation && isInvestigationPanelVisible

/**
 * Same reasoning as [shouldShowShareDialog], for the floating "Resume Investigation" affordance
 * that would otherwise appear once a glasses HUD Use has appended evidence
 * (hasActiveInvestigation becomes true) - see StreamScreen's showInvestigationReopenAffordance.
 * Without this, tapping that affordance would be a second, unwanted way into the exact legacy
 * screen [shouldShowInvestigationPanel] already keeps closed in this mode.
 */
internal fun shouldShowInvestigationReopenAffordance(
    returnToConversation: Boolean,
    isInvestigationPanelVisible: Boolean,
    isShareDialogVisible: Boolean,
    hasActiveInvestigation: Boolean,
): Boolean =
    !returnToConversation &&
        !isInvestigationPanelVisible &&
        !isShareDialogVisible &&
        hasActiveInvestigation

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
    // ADR-061 conversation handoff (Blocker 1): true only when this Capture/Stream session was
    // entered via "Use Glasses" from ProjectConversationScreen (see AppRoot.kt's
    // TopLevelScreen.Capture.returnToConversation and CameraAccessScaffold's own doc on this same
    // param). The legacy per-capture phone surfaces below (SharePhotoDialog's Share/Continue-to-
    // Investigation choice, the Investigation trust/Analyze bottom sheet, and its floating "Resume
    // Investigation" affordance) are never shown in that mode - see shouldShowShareDialog/
    // shouldShowInvestigationPanel/shouldShowInvestigationReopenAffordance below for why. Evidence
    // is still appended internally via the exact same InvestigationSessionDebugViewModel/
    // appendLiveEvidence call as always (see LaunchedEffect(hudCaptureAcceptRequest) below) - only
    // the PHONE-FACING legacy UI is suppressed, never the underlying storage. False (the existing
    // behavior, unchanged) for every other Capture entry point - the global "Capture / Test
    // Glasses" entry, Workspace's "Use glasses for this Project", and "Add more evidence" all keep
    // showing these exact same screens as before.
    returnToConversation: Boolean = false,
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
            //
            // ADR-061 identity collision fix: returnToConversation selects the interaction
            // context too, not just whether legacy UI is suppressed - a conversation-originated
            // capture must never resolve the SAME instance a legacy Capture entry for the same
            // Project would (see investigationViewModelKey's doc). The conversation context
            // still matches ProjectConversationScreen's own glassesEvidenceViewModel key exactly,
            // which is what lets the glasses-to-conversation evidence handoff keep working.
            key = investigationViewModelKey(
                sourceProjectId,
                continuationSessionId,
                if (returnToConversation) InvestigationInteractionContext.CONVERSATION else InvestigationInteractionContext.LEGACY,
            ),
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
      remember(returnToConversation, streamUiState.isInvestigationPanelVisible, streamUiState.isShareDialogVisible, investigationUiState) {
        shouldShowInvestigationReopenAffordance(
            returnToConversation = returnToConversation,
            isInvestigationPanelVisible = streamUiState.isInvestigationPanelVisible,
            isShareDialogVisible = streamUiState.isShareDialogVisible,
            hasActiveInvestigation = hasActiveInvestigation(investigationUiState),
        )
      }
  val investigationReopenLabel = remember(investigationUiState) {
    investigationReopenAffordanceLabel(investigationUiState)
  }
  // ADR-061 architect review, Item 2: the conversation-mode mirror of
  // showInvestigationReopenAffordance/investigationReopenLabel above - same underlying
  // hasActiveInvestigation(investigationUiState) signal (the SAME InvestigationSessionDebugViewModel
  // a conversation-originated capture's accepted evidence is appended into - see
  // ConversationAcceptedCaptureBanner's doc), mutually exclusive with the legacy affordance by
  // construction (shouldShowConversationAcceptedCaptureBanner requires returnToConversation,
  // shouldShowInvestigationReopenAffordance requires !returnToConversation).
  val showConversationAcceptedCaptureBanner =
      remember(returnToConversation, investigationUiState) {
        shouldShowConversationAcceptedCaptureBanner(
            returnToConversation = returnToConversation,
            hasActiveInvestigation = hasActiveInvestigation(investigationUiState),
        )
      }
  val conversationAcceptedCaptureLabelText = remember(investigationUiState) {
    conversationAcceptedCaptureLabel(investigationUiState)
  }
  val investigationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner, streamViewModel) {
    val observer = createStreamLifecycleStopObserver { streamViewModel.stopStream() }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(sourceProjectId, sourceProjectName, returnToConversation) {
    // Configure the explicit Project before starting the one shared DeviceSession, so the HUD
    // can attach to that session as soon as it reaches STARTED. returnToConversation forwarded
    // through so a historical, still-undecided legacy trust review can never hijack a Project
    // Conversation glasses session's HUD - see StreamViewModel.configureProjectHud's doc.
    streamViewModel.configureProjectHud(sourceProjectId, sourceProjectName, returnToConversation)
    streamViewModel.startStream()
  }

  LaunchedEffect(projectHudPhoneHandoff) {
    projectHudPhoneHandoff?.let { handoff ->
      Log.d(TAG, "LaunchedEffect(projectHudPhoneHandoff): received $handoff, sourceProjectId=$sourceProjectId")
      // Defense in depth: an event from an old Project can never navigate the current Project.
      if (handoff.projectId == sourceProjectId) {
        streamViewModel.stopStream()
        onProjectHudPhoneHandoff?.invoke(handoff.destination, continuationSessionId)
      } else {
        Log.e(TAG, "LaunchedEffect(projectHudPhoneHandoff): projectId mismatch, ignoring navigation")
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
      Log.d(TAG, "LaunchedEffect(hudCaptureAcceptRequest): live consumer received evidence, appending")
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

    if (shouldShowInvestigationPanel(returnToConversation, streamUiState.isInvestigationPanelVisible)) {
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

    if (showConversationAcceptedCaptureBanner) {
      // Required UX (a previously reported, now corrected gap): the phone must visibly represent
      // an accepted capture between Use and Continue-on-phone, not go silent - see the composable's
      // own doc for why this reuses the SAME capability/state the legacy Resume affordance above
      // does, routed into ProjectConversation instead of legacy Investigation.
      ConversationAcceptedCaptureBanner(
          label = conversationAcceptedCaptureLabelText,
          onContinueToConversation = { streamViewModel.continueToConversationFromPhone() },
          modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
      )
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    // ADR-061 cutover: ONE authoritative, exhaustive decision for which phone surface (if any)
    // renders a pending capturedPhoto - see resolvePhoneCaptureSurface's doc. Replaces two
    // independently-evaluated booleans with a sealed `when`, so the compiler - not just careful
    // authorship of each condition - guarantees the legacy dialog and the conversation preview can
    // never both render for the same pending photo.
    when (resolvePhoneCaptureSurface(returnToConversation, streamUiState.isShareDialogVisible)) {
      PhoneCaptureSurface.LegacyShareDialog ->
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
      PhoneCaptureSurface.ConversationPreview ->
          // Required UX: the legacy Share/Continue-to-Investigation CHOICE is suppressed for a
          // conversation-originated capture, but the phone must still let the user visually
          // verify what the glasses just captured, AND be able to accept/reject it itself - phone
          // and glasses are both controllers over the identical capture lifecycle (see
          // StreamViewModel.useCurrentCaptureFromPhone's doc). This preview disappears on its own
          // the moment capturedPhoto is cleared - onHudCaptureAccepted (Use, from either device)
          // or onHudRetakeRequested (Retake, from either device) - exactly mirroring the glasses
          // HUD's own AwaitingConfirmation -> Idle transition, never a second image store.
          ConversationCapturePreview(photo, streamViewModel)
      PhoneCaptureSurface.None -> Unit
    }
  }
}

/**
 * ADR-061 cutover: the single authoritative decision for which phone surface, if any, renders a
 * pending [StreamUiState.capturedPhoto] - a sealed, exhaustive replacement for what used to be two
 * independently-evaluated booleans ([shouldShowShareDialog], [shouldShowConversationCapturePreview]
 * - both kept, and still directly tested, as the small pure predicates this composes). Small,
 * pure, and directly testable for the same reason those two are.
 */
internal enum class PhoneCaptureSurface { None, LegacyShareDialog, ConversationPreview }

internal fun resolvePhoneCaptureSurface(returnToConversation: Boolean, isShareDialogVisible: Boolean): PhoneCaptureSurface =
    when {
      shouldShowShareDialog(returnToConversation, isShareDialogVisible) -> PhoneCaptureSurface.LegacyShareDialog
      shouldShowConversationCapturePreview(returnToConversation, hasCapturedPhoto = true) -> PhoneCaptureSurface.ConversationPreview
      else -> PhoneCaptureSurface.None
    }

/**
 * Same reasoning as [shouldShowShareDialog]/[shouldShowInvestigationPanel] - a small, pure,
 * directly-testable unit for the decision StreamScreen itself isn't practically unit-testable at.
 * True exactly when a conversation-originated capture has a pending photo the legacy Share dialog
 * is NOT showing (mutually exclusive with it - enforced structurally by [resolvePhoneCaptureSurface]).
 */
internal fun shouldShowConversationCapturePreview(returnToConversation: Boolean, hasCapturedPhoto: Boolean): Boolean =
    returnToConversation && hasCapturedPhoto

/**
 * Visual confirmation of a glasses capture pending a Use/Retake decision, with the phone's OWN
 * Use/Retake controls - see the call site's doc and StreamViewModel.useCurrentCaptureFromPhone's
 * doc for why a phone tap here is exactly as authoritative as a glasses tap. Deliberately
 * non-dismissible by outside tap/back (no-op onDismissRequest): the only way to proceed is one of
 * these two buttons or the glasses HUD's own Use/Retake, never an accidental dismiss.
 */
@Composable
private fun ConversationCapturePreview(photo: Bitmap, streamViewModel: StreamViewModel) {
  Dialog(onDismissRequest = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight().testTag("conversation_capture_preview"),
        shape = RoundedCornerShape(16.dp),
    ) {
      Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(text = stringResource(R.string.photo_captured))
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = stringResource(R.string.captured_photo),
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(
              onClick = { streamViewModel.retakeCurrentCaptureFromPhone() },
              modifier = Modifier.testTag("conversation_capture_retake"),
          ) { Text("Retake") }
          Button(
              onClick = { streamViewModel.useCurrentCaptureFromPhone() },
              modifier = Modifier.testTag("conversation_capture_use"),
          ) { Text("Use") }
        }
      }
    }
  }
}

/**
 * ADR-061 architect review, Item 2 - same reasoning as [shouldShowInvestigationReopenAffordance]'s
 * legacy equivalent, mirrored for conversation mode: true exactly when a conversation-originated
 * capture has at least one accepted photo pending ([hasActiveInvestigation] on the SAME
 * InvestigationSessionDebugViewModel the legacy affordance reads). Mutually exclusive with
 * [shouldShowInvestigationReopenAffordance] by construction - that one requires
 * `!returnToConversation`, this one requires `returnToConversation` - so the two banners can never
 * both show for the same capture.
 */
internal fun shouldShowConversationAcceptedCaptureBanner(returnToConversation: Boolean, hasActiveInvestigation: Boolean): Boolean =
    returnToConversation && hasActiveInvestigation

/**
 * Required UX (corrected regression - a previously reported, previously-working phone behavior
 * that the ADR-061 conversation cutover had silently dropped): between Use and Continue-on-phone,
 * the phone must visibly represent that a photo was accepted, not go back to showing only the live
 * camera feed. Reuses the SAME state the legacy "Resume Investigation" affordance already reads
 * ([conversationAcceptedCaptureLabel]/[hasActiveInvestigation] over the SAME
 * InvestigationSessionDebugViewModel a conversation-originated capture's evidence is appended
 * into) - a projection over existing capability state, never a new Evidence/store/workflow owner.
 * "Capture another" is deliberately represented as guidance text, not a button: the existing
 * lifecycle has no phone-triggerable remote-capture action (Capture is a glasses-only input source
 * in both modes), so a phone button here would invent a capability that doesn't exist rather than
 * projecting one that does.
 */
@Composable
private fun ConversationAcceptedCaptureBanner(
    label: String,
    onContinueToConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Card(
      modifier = modifier.fillMaxWidth().wrapContentHeight().testTag("conversation_accepted_capture_banner"),
      shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(label)
      Text("You can capture another photo on your glasses, or continue to your Project.")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = onContinueToConversation,
            modifier = Modifier.testTag("conversation_continue_to_project"),
        ) { Text("Continue to Project") }
      }
    }
  }
}
