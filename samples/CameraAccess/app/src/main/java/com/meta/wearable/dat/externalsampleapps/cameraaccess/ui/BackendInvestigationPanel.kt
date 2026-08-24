package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.BackendTrustDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationProductPhase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.bitmapToInvestigationEvidence
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.deriveInvestigationProductState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.liveCaptureFilename
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.reduceInvestigationSpeechState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackendInvestigationPanel(
    modifier: Modifier = Modifier,
    prefillLiveEvidence: InvestigationEvidenceInput? = null,
    viewModel: InvestigationSessionDebugViewModel,
  sourceProjectName: String? = null,
  onReturnToProject: (() -> Unit)? = null,
  onCaptureAnotherView: (() -> Unit)? = null,
  onPrefillApplied: (() -> Unit)? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val productState = remember(uiState) { deriveInvestigationProductState(uiState) }
  val context = LocalContext.current
  val speechController = remember(context) { createInvestigationSpeechRecognizerController(context) }
  var showCameraPermissionAlert by remember { mutableStateOf(false) }
  var showDeveloperDetails by remember { mutableStateOf(false) }
  var showDisagreeInput by remember { mutableStateOf(false) }
  var speechUiState by remember { mutableStateOf(InvestigationSpeechUiState()) }
  val panelScrollState = rememberScrollState()

  DisposableEffect(speechController) {
    onDispose { speechController?.destroy() }
  }

  val onSpeechEvent: (InvestigationSpeechEvent) -> Unit = { event ->
    val transition = reduceInvestigationSpeechState(speechUiState, event)
    speechUiState = transition.state
    transition.transcript?.let { transcript -> viewModel.setExplanationText(transcript) }
  }

  val startSpeechCapture = {
    if (speechController == null) {
      onSpeechEvent(
          InvestigationSpeechEvent.UnknownError("Speech recognition is unavailable on this device."),
      )
    } else {
      speechController.startListening(onSpeechEvent)
    }
  }

  val phoneCameraCaptureLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
          val evidence =
              bitmapToInvestigationEvidence(
                  bitmap = it,
                  slotIndex = 0,
                  filename = liveCaptureFilename(slotIndex = 0, extension = "png"),
                  source = InvestigationEvidenceSource.LOCAL_PICKER,
              )
          viewModel.appendLiveEvidence(evidence)
        }
      }

  val cameraPermissionLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
          granted ->
        if (granted) {
          phoneCameraCaptureLauncher.launch(null)
        } else {
          showCameraPermissionAlert = true
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

  LaunchedEffect(prefillLiveEvidence) {
    prefillLiveEvidence?.let {
      viewModel.appendLiveEvidence(it)
      onPrefillApplied?.invoke()
    }
  }
  val imagePicker =
      rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        val displayName = uri?.lastPathSegment ?: uri?.toString()?.substringAfterLast('/')
        uri?.let { viewModel.appendPickedImage(it.toString(), displayName) }
      }

  Card(
      modifier = modifier.fillMaxWidth(),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.padding(12.dp).verticalScroll(panelScrollState),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
          text = "INVESTIGATION",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
      )

      sourceProjectName?.let { projectName ->
        Text(
            text = "Project: $projectName",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
      }

      Text(
          text =
              when (productState.phase) {
                InvestigationProductPhase.READY -> "Ready to capture your first view."
                InvestigationProductPhase.COLLECTING_EVIDENCE ->
                    "${productState.capturedViewCount} view(s) captured. Collect more or add context."
                InvestigationProductPhase.READY_TO_ANALYZE ->
                    "Ready to analyze with ${productState.capturedViewCount} view(s)."
                InvestigationProductPhase.ANALYZING -> "Analyzing investigation..."
                InvestigationProductPhase.COMPLETED -> "Investigation completed."
                InvestigationProductPhase.FAILED -> "Investigation failed. Review and retry."
              },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HorizontalDivider()

      Text(
          text = "Evidence",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          text =
              if (productState.capturedViewCount == 1) "1 photo added"
              else "${productState.capturedViewCount} photos added",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )

      if (onCaptureAnotherView != null) {
        Button(
            onClick = {
              if (productState.hasCaptureCapacity && !uiState.isRunning) {
                viewModel.clearInvestigationResultStatus()
                onCaptureAnotherView()
              }
            },
            enabled = productState.hasCaptureCapacity && !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(productState.captureActionLabel)
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.weight(1f),
            enabled = productState.hasCaptureCapacity && !uiState.isRunning,
        ) {
          Text("Capture with phone camera")
        }
      }
      TextButton(
          onClick = { imagePicker.launch("image/*") },
          enabled = productState.hasCaptureCapacity && !uiState.isRunning,
      ) {
        Text(
            if (productState.capturedViewCount == 0) "Add photo from device"
            else "Add another from device"
        )
      }

      HorizontalDivider()

      Text(
          text = "Context",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
      )
      OutlinedTextField(
          value = uiState.explanationText,
          onValueChange = viewModel::setExplanationText,
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Explanation") },
          placeholder = { Text("Describe what the captured views show") },
          minLines = 2,
          maxLines = 4,
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = !speechUiState.canCancel,
        ) {
          Text(speechUiState.speakButtonLabel)
        }
        if (speechUiState.canCancel) {
          TextButton(
              onClick = {
                speechController?.cancel()
                onSpeechEvent(InvestigationSpeechEvent.Cancelled)
              },
          ) {
            Text("Cancel")
          }
        }
      }

      speechUiState.feedbackMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
          onClick = viewModel::onStartInvestigationButtonClick,
          modifier = Modifier.fillMaxWidth(),
          enabled = productState.canAnalyze && !uiState.isRunning,
      ) {
        Text(
            when {
              uiState.isRunning -> "Analyzing..."
              productState.phase == InvestigationProductPhase.FAILED && productState.canAnalyze ->
                  "Retry analysis"
              else -> "Analyze investigation"
            }
        )
      }

      if (!productState.canAnalyze && !uiState.isRunning) {
        Text(
            text = "Add at least 1 view and explanation to analyze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (uiState.isRunning) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
          Text(
              text = "Analyzing investigation... ${productState.capturedViewCount} view(s) + ${if (productState.hasExplanation) "explanation" else "no explanation"}",
              style = MaterialTheme.typography.bodyMedium,
          )
        }
        Button(
            onClick = viewModel::cancelSubmission,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAA071E)),
        ) {
          Text("Cancel")
        }
      }

        val compactResult = uiState.compactResult
        if (productState.phase == InvestigationProductPhase.COMPLETED && compactResult != null) {
        HorizontalDivider()
        Text(
            text = "AI SUGGESTION — UNCONFIRMED",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This is an AI suggestion, not confirmed Project truth.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = compactResult.diagnosisShort,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "NEXT ACTION",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = compactResult.requiredNextActionShort,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (uiState.trustControlsAvailable) {
          HorizontalDivider()
          Text(
              text = "What should happen next?",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
          )
          Text(
              text = "Keeping this as a working hypothesis records your assessment. It does not update the Project. Any suggested Project change still requires Apply to Project.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
              onClick = { viewModel.submitTrustDecision(BackendTrustDecision.CONTINUE) },
              modifier = Modifier.fillMaxWidth(),
              enabled = !uiState.trustDecisionInFlight,
          ) {
            Text("Keep as working hypothesis")
          }
          Button(
              onClick = { viewModel.submitTrustDecision(BackendTrustDecision.MORE_EVIDENCE) },
              modifier = Modifier.fillMaxWidth(),
              enabled = !uiState.trustDecisionInFlight,
          ) {
            Text("Add more evidence")
          }
          TextButton(
              onClick = { showDisagreeInput = !showDisagreeInput },
              enabled = !uiState.trustDecisionInFlight,
          ) {
            Text("I disagree")
          }
          if (showDisagreeInput) {
            OutlinedTextField(
                value = uiState.trustCorrectionText,
                onValueChange = viewModel::setTrustCorrectionText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Optional explanation or correction") },
                placeholder = { Text("You can disagree without knowing the correct answer") },
                minLines = 2,
            )
            Button(
                onClick = { viewModel.submitTrustDecision(BackendTrustDecision.DISAGREE) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.trustDecisionInFlight,
            ) {
              Text("Save disagreement")
            }
          }
          if (uiState.trustDecisionInFlight) {
            Text("Saving decision…")
          }
        }
      }
      uiState.trustMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (uiState.trustDecision != null && onReturnToProject != null) {
        Text(
            text = "Investigation saved to ${sourceProjectName ?: "your Project"}. Return to review any suggested Project changes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onReturnToProject, modifier = Modifier.fillMaxWidth()) {
          Text("Return to ${sourceProjectName ?: "Project"}")
        }
      }

      if (productState.phase == InvestigationProductPhase.FAILED && uiState.statusMessage != null) {
        Text(
            text = uiState.statusMessage ?: "Investigation failed.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAA071E),
        )
      }

      TextButton(onClick = { showDeveloperDetails = !showDeveloperDetails }) {
        Text(if (showDeveloperDetails) "Hide technical details" else "Technical details")
      }

      if (showDeveloperDetails) {
        Text(
            text = "Base URL: ${uiState.backendBaseUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Session workflow: create, upload ordered evidence, initiate analysis, then poll when needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A4B00),
        )
        if (BuildConfig.DEBUG && BuildConfig.INVESTIGATION_BACKEND_BASE_URL.contains("cloudflare", ignoreCase = true)) {
          Text(
              text = "Temporary tunnel configured in BuildConfig.",
              color = Color(0xFF8A4B00),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        uiState.images.filter { it.evidence != null || it.uriString != null }.forEach { slot ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(slot.displayName ?: "Photo ${slot.slotIndex + 1}", modifier = Modifier.weight(1f))
            TextButton(
                onClick = { viewModel.clearImage(slot.slotIndex) },
                enabled = !uiState.isRunning,
            ) {
              Text("Clear")
            }
          }
          Text(
              text = slot.evidence?.source?.displayLabel ?: "Source: Local picker",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
              onClick = viewModel::onCheckBackendButtonClick,
              modifier = Modifier.weight(1f),
              enabled = !uiState.isRunning,
          ) {
            Text("Check backend")
          }
        }
        SessionStatusSummary(uiState = uiState)
      }
    }
  }

  if (showCameraPermissionAlert) {
    AlertDialog(
        onDismissRequest = { showCameraPermissionAlert = false },
        title = { Text("Camera permission required") },
        text = { Text("Allow Camera permission to capture an image from this phone.") },
        confirmButton = {
          TextButton(onClick = { showCameraPermissionAlert = false }) { Text("OK") }
        },
    )
  }
}

@Composable
private fun SessionStatusSummary(
    uiState: com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugUiState,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Client state: ${uiState.clientState}")
    Text("Session ID: ${uiState.sessionId ?: "-"}")
    Text("Backend status: ${uiState.backendStatus?.wireValue ?: "-"}")
    Text("Investigation ID: ${uiState.investigationId ?: "-"}")
    Text("Upload progress: ${uiState.uploadedImageCount}/${uiState.totalImageCount}")
    Text("Image count: ${uiState.imageCount}")
    Text("Explanation present: ${uiState.explanationPresent}")
    Text("Analyze accepted: ${uiState.analyzeAccepted?.toString() ?: "-"}")
    Text("Poll after ms: ${uiState.pollAfterMs?.toString() ?: "-"}")
    Text("Error category: ${uiState.backendErrorCategory ?: "-"}")
    Text("Status: ${uiState.statusMessage ?: "-"}")
    uiState.compactResult?.let { result ->
      Text("Compact result: ${result.diagnosisShort}")
      Text("Required action: ${result.requiredNextActionShort}")
      Text("Freshness: ${result.freshnessState}")
    }
  }
}
