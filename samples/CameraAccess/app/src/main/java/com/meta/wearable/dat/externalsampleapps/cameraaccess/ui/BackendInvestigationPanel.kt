package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackendInvestigationPanel(
    modifier: Modifier = Modifier,
    prefillLiveEvidence: InvestigationEvidenceInput? = null,
    viewModel: InvestigationSessionDebugViewModel =
        viewModel(
            factory =
                InvestigationSessionDebugViewModel.factory(
                    (LocalActivity.current as ComponentActivity).application,
                ),
        ),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(prefillLiveEvidence) {
    prefillLiveEvidence?.let { viewModel.setEvidence(0, it) }
  }
  val firstImagePicker =
      rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        val displayName = uri?.lastPathSegment ?: uri?.toString()?.substringAfterLast('/')
        viewModel.setImage(0, uri?.toString(), displayName)
      }
  val secondImagePicker =
      rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        val displayName = uri?.lastPathSegment ?: uri?.toString()?.substringAfterLast('/')
        viewModel.setImage(1, uri?.toString(), displayName)
      }
  val thirdImagePicker =
      rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        val displayName = uri?.lastPathSegment ?: uri?.toString()?.substringAfterLast('/')
        viewModel.setImage(2, uri?.toString(), displayName)
      }
  val slotPickers = listOf(firstImagePicker, secondImagePicker, thirdImagePicker)

  Card(
      modifier = modifier.fillMaxWidth(),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
          text = "Investigation Session Backend",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          text = "Base URL: ${uiState.backendBaseUrl}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (BuildConfig.DEBUG && BuildConfig.INVESTIGATION_BACKEND_BASE_URL.contains("cloudflare", ignoreCase = true)) {
        Text(
            text = "Temporary tunnel configured in BuildConfig.",
            color = Color(0xFF8A4B00),
            style = MaterialTheme.typography.bodySmall,
        )
      }
      HorizontalDivider()
      Text(
          text = "Session workflow: create, upload ordered evidence, initiate analysis, then poll only when needed.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF8A4B00),
      )

      Text(
          text = "Images must stay ordered.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      uiState.images.forEach { slot ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
              onClick = { slotPickers[slot.slotIndex].launch("image/*") },
              modifier = Modifier.weight(1f),
          ) {
            Text(
                text = slot.displayName?.let { "Image ${slot.slotIndex + 1}: $it" }
                    ?: "Pick image ${slot.slotIndex + 1}",
            )
          }
          TextButton(
              onClick = { viewModel.clearImage(slot.slotIndex) },
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

      OutlinedTextField(
          value = uiState.explanationText,
          onValueChange = viewModel::setExplanationText,
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Explanation text") },
          placeholder = { Text("One spoken or typed explanation") },
          minLines = 2,
          maxLines = 4,
      )
        Text(
          text = "Explanation text is sent only through the supported multipart field normalized_text on evidence upload.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = viewModel::runConnectivityCheck,
            modifier = Modifier.weight(1f),
            enabled = !uiState.isRunning,
        ) {
          Text("Check backend")
        }
        Button(
            onClick = viewModel::submitInvestigation,
            modifier = Modifier.weight(1f),
            enabled = !uiState.isRunning,
        ) {
          Text("Submit")
        }
      }
      if (uiState.isRunning) {
        Button(
            onClick = viewModel::cancelSubmission,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAA071E)),
        ) {
          Text("Cancel")
        }
      }

      HorizontalDivider()
      SessionStatusSummary(uiState = uiState)
    }
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
