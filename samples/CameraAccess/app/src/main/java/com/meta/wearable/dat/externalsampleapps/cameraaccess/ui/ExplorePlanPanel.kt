/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ExplorePlanPanel - Rich Project Intelligence V1 (ADR-059) phone UI: Room Redesign EXPLORE_PLAN
//
// Renders ONLY typed backend fields (ExplorePlanResult/ExplorePlanOption - see ProjectModels.kt)
// - never idea.details, the backend-private persistence encoding. SELECT and Apply both call
// through to ExplorePlanViewModel, which calls the backend's own SELECT->Proposal->Apply
// endpoints unchanged; this file builds no proposed_checkpoint_patch and no Project Memory
// semantics of its own. AI inference (observations/recommendation) is always labeled unconfirmed;
// selecting an option is visibly distinct from the Project change becoming canonical (Apply).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.CheckpointProposalReview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanApplyState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanOption
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanSelectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactUiState

private val CardBackground = Color(0xFF24262B)
private val ErrorColor = Color(0xFFFF8A80)

@Composable
internal fun ExplorePlanPanel(
    planState: ExplorePlanUiState,
    selectionState: ExplorePlanSelectionState,
    applyState: ExplorePlanApplyState,
    onRequestPlan: (String) -> Unit,
    onSelectOption: (String) -> Unit,
    onApply: () -> Unit,
    onDismissProposal: () -> Unit,
    onRetry: () -> Unit,
    visualStates: Map<String, VisualArtifactUiState> = emptyMap(),
    onVisualizeOption: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(top = 24.dp)) {
    Text("DESIGN & PLANNING GUIDANCE", color = AppColor.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    when (planState) {
      is ExplorePlanUiState.Idle -> ExplorePlanIntentComposer(busy = false, onRequestPlan = onRequestPlan)
      is ExplorePlanUiState.Requesting ->
          Row(modifier = Modifier.padding(top = 12.dp)) {
            CircularProgressIndicator(color = AppColor.Accent, modifier = Modifier.size(20.dp))
            Text("Getting design ideas…", color = AppColor.InkSecondary, modifier = Modifier.padding(start = 10.dp))
          }
      is ExplorePlanUiState.InformationRequested -> {
        Text(planState.prompt, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 10.dp))
        ExplorePlanIntentComposer(busy = false, onRequestPlan = onRequestPlan, placeholder = "Add the missing details")
      }
      is ExplorePlanUiState.Failed -> {
        Text(planState.message, color = ErrorColor, modifier = Modifier.padding(top = 10.dp))
        // onRetry re-loads a previously known plan (relevant when reopening a Project whose
        // saved plan failed to reconstruct); the composer below is always offered too, since a
        // FIRST request that failed (no known result_id yet) has no plan for onRetry to reload -
        // requesting fresh is the only recovery path in that case, and is always valid either way.
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 10.dp)) { Text("Retry loading") }
        ExplorePlanIntentComposer(busy = false, onRequestPlan = onRequestPlan, placeholder = "Or ask again: describe what you need design/planning guidance on")
      }
      is ExplorePlanUiState.Ready ->
          ExplorePlanReadyContent(
              result = planState.result,
              selectionState = selectionState,
              applyState = applyState,
              onSelectOption = onSelectOption,
              onApply = onApply,
              onDismissProposal = onDismissProposal,
              visualStates = visualStates,
              onVisualizeOption = onVisualizeOption,
          )
    }
  }
}

@Composable
private fun ExplorePlanIntentComposer(
    busy: Boolean,
    onRequestPlan: (String) -> Unit,
    placeholder: String = "For example: Give me three directions to make this room warmer and more modern",
) {
  var intent by remember { mutableStateOf("") }
  Text(
      "Ask for design or planning guidance. AI suggestions stay unconfirmed until you explicitly select and apply one.",
      color = AppColor.InkSecondary,
      fontSize = 12.sp,
      modifier = Modifier.padding(top = 6.dp),
  )
  OutlinedTextField(
      value = intent,
      onValueChange = { intent = it },
      enabled = !busy,
      placeholder = { Text(placeholder) },
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
  )
  Button(
      onClick = { onRequestPlan(intent) },
      enabled = intent.isNotBlank() && !busy,
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
  ) {
    Text("Get design ideas", fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun ExplorePlanReadyContent(
    result: ExplorePlanResult,
    selectionState: ExplorePlanSelectionState,
    applyState: ExplorePlanApplyState,
    onSelectOption: (String) -> Unit,
    onApply: () -> Unit,
    onDismissProposal: () -> Unit,
    visualStates: Map<String, VisualArtifactUiState>,
    onVisualizeOption: (String, String) -> Unit,
) {
  Column(modifier = Modifier.padding(top = 10.dp)) {
    result.title?.let { Text(it, color = AppColor.InkPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    result.summary?.let { Text(it, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 4.dp)) }
    if (!result.complete) {
      Text(
          "This set of options is still being finalized. Some fields may be incomplete.",
          color = AppColor.Amber,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 8.dp),
      )
    }
    result.recommendedOption?.let { recommended ->
      Text(
          "AI suggestion — unconfirmed: recommends \"${recommended.title}\"" +
              (result.recommendationReason?.let { ". $it" } ?: "."),
          color = AppColor.Accent,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp),
      )
    }

    result.options.forEach { option ->
      val selectionForThisOption =
          (selectionState as? ExplorePlanSelectionState.ProposalReady)?.takeIf { it.ideaId == option.ideaId }
      val selectingThisOption = selectionState is ExplorePlanSelectionState.Selecting && selectionState.ideaId == option.ideaId
      val failureForThisOption = (selectionState as? ExplorePlanSelectionState.Failed)?.takeIf { it.ideaId == option.ideaId }
      ExplorePlanOptionCard(
          option = option,
          selecting = selectingThisOption,
          selected = option.disposition == "select" || selectionForThisOption != null,
          failureMessage = failureForThisOption?.message,
          onSelect = { onSelectOption(option.ideaId) },
          visualState = visualStates[option.ideaId] ?: VisualArtifactUiState.Idle,
          onVisualize = { onVisualizeOption(result.resultId, option.ideaId) },
      )
      selectionForThisOption?.let { ready ->
        ProposalReviewCard(
            proposal = ready.proposal,
            applyState = applyState,
            onApply = onApply,
            onDismiss = onDismissProposal,
        )
      }
    }

    if (result.observations.isNotEmpty() || result.nextSteps.isNotEmpty() || result.followUpQuestions.isNotEmpty()) {
      Column(
          modifier = Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp))
              .background(CardBackground).padding(14.dp),
      ) {
        if (result.observations.isNotEmpty()) {
          Text("OBSERVATIONS", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          result.observations.forEach { Text("• $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (result.nextSteps.isNotEmpty()) {
          Text("NEXT STEPS", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
          result.nextSteps.forEach { Text("• $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (result.followUpQuestions.isNotEmpty()) {
          Text("FOLLOW-UP QUESTIONS", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
          result.followUpQuestions.forEach { Text("• $it", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 4.dp)) }
        }
      }
    }
  }
}

@Composable
private fun ExplorePlanOptionCard(
    option: ExplorePlanOption,
    selecting: Boolean,
    selected: Boolean,
    failureMessage: String?,
    onSelect: () -> Unit,
    visualState: VisualArtifactUiState,
    onVisualize: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
          .background(CardBackground).padding(14.dp),
  ) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      Text(
          "${option.ordinal}. ${option.title}",
          color = AppColor.InkPrimary,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
      )
      if (option.recommended) {
        Text("RECOMMENDED", color = AppColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
    Text(option.summary, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 4.dp))
    // Collapsed card per the approved UX: "title, short summary/concept". concept is a distinct
    // typed field from summary (see ProjectModels.kt) - shown only when present and different, so
    // a plan without one never shows a redundant blank/duplicate line.
    option.concept?.takeIf { it != option.summary }?.let {
      Text(it, color = AppColor.InkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
    option.estimatedCost?.let { cost ->
      val range = when {
        cost.minAmount != null && cost.maxAmount != null -> "${cost.minAmount.toInt()}–${cost.maxAmount.toInt()} ${cost.currency}"
        cost.minAmount != null -> "From ${cost.minAmount.toInt()} ${cost.currency}"
        cost.maxAmount != null -> "Up to ${cost.maxAmount.toInt()} ${cost.currency}"
        else -> cost.currency
      }
      Text(
          "Estimated cost: $range" + (cost.qualifier?.let { " ($it)" } ?: ""),
          color = AppColor.InkSecondary,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
    if (option.rationale != null || option.tradeoffs != null || option.proposedChanges != null) {
      TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
        Text(if (expanded) "Hide details" else "Show details")
      }
    }
    if (expanded) {
      option.rationale?.let {
        Text("RATIONALE", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(it, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 2.dp))
      }
      option.proposedChanges?.let {
        Text("PROPOSED CHANGES", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(it, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 2.dp))
      }
      option.tradeoffs?.let {
        Text("TRADEOFFS", color = AppColor.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(it, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 2.dp))
      }
      VisualArtifactControls(state = visualState, onVisualize = onVisualize)
    }
    failureMessage?.let { Text(it, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
      if (selecting) {
        CircularProgressIndicator(color = AppColor.Accent, modifier = Modifier.size(18.dp))
      } else if (selected) {
        Text("Selected — awaiting your review below", color = AppColor.Success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
      } else {
        Button(
            onClick = onSelect,
            colors = ButtonDefaults.buttonColors(containerColor = AppColor.Accent, contentColor = AppColor.AccentInk),
        ) { Text("Select this direction") }
      }
    }
  }
}

@Composable
private fun VisualArtifactControls(
    state: VisualArtifactUiState,
    onVisualize: () -> Unit,
) {
  var comparisonVisible by remember { mutableStateOf(false) }
  Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
    when (state) {
      VisualArtifactUiState.Idle ->
          OutlinedButton(onClick = onVisualize, modifier = Modifier.fillMaxWidth()) {
            Text("Visualize this option")
          }
      VisualArtifactUiState.Requesting ->
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(color = AppColor.Accent, modifier = Modifier.size(18.dp))
            Text("Creating AI visualization…", color = AppColor.InkSecondary, modifier = Modifier.padding(start = 10.dp))
          }
      is VisualArtifactUiState.Failed -> {
        Text(state.message, color = ErrorColor, fontSize = 12.sp)
        OutlinedButton(onClick = onVisualize, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
          Text("Retry visualization")
        }
      }
      is VisualArtifactUiState.Ready -> {
        val bitmap = remember(state.images.visualization) {
          BitmapFactory.decodeByteArray(state.images.visualization, 0, state.images.visualization.size)
        }
        Text("AI visualization", color = AppColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        bitmap?.let {
          Image(
              bitmap = it.asImageBitmap(),
              contentDescription = "AI visualization thumbnail",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 6.dp).clip(RoundedCornerShape(12.dp)),
          )
        }
        OutlinedButton(onClick = { comparisonVisible = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
          Text("View comparison")
        }
        if (comparisonVisible) {
          VisualComparisonDialog(state = state, onDismiss = { comparisonVisible = false })
        }
      }
    }
  }
}

@Composable
private fun VisualComparisonDialog(
    state: VisualArtifactUiState.Ready,
    onDismiss: () -> Unit,
) {
  var showingSource by remember { mutableStateOf(true) }
  val bytes = if (showingSource) state.images.source else state.images.visualization
  val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
  Dialog(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.fillMaxSize().background(AppColor.Graphite).padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
      Text(
          if (showingSource) "Source photo" else "AI visualization",
          color = AppColor.InkPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
      )
      bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = if (showingSource) "Source photo" else "AI visualization full screen",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { showingSource = true }, modifier = Modifier.weight(1f)) { Text("Source") }
        OutlinedButton(onClick = { showingSource = false }, modifier = Modifier.weight(1f)) { Text("Visualization") }
      }
      TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
    }
  }
}

@Composable
private fun ProposalReviewCard(
    proposal: CheckpointProposalReview,
    applyState: ExplorePlanApplyState,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
  val busy = applyState is ExplorePlanApplyState.Applying
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF2A2432)).padding(14.dp),
  ) {
    Text("PROPOSED PROJECT UPDATE — REVIEW REQUIRED", color = AppColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Text(
        "This has not changed your Project yet. Apply to make it canonical.",
        color = AppColor.InkSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(proposal.reason, color = AppColor.InkPrimary, modifier = Modifier.padding(top = 8.dp))
    proposal.proposedFields.filterValues { it != null }.forEach { (field, value) ->
      Text("${field.replace('_', ' ')} → $value", color = AppColor.InkPrimary, modifier = Modifier.padding(top = 6.dp))
    }
    when (applyState) {
      is ExplorePlanApplyState.Applied -> Text("Applied to your Project.", color = AppColor.Success, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
      is ExplorePlanApplyState.Failed -> Text(applyState.message, color = ErrorColor, modifier = Modifier.padding(top = 10.dp))
      else -> Unit
    }
    if (applyState !is ExplorePlanApplyState.Applied) {
      Button(
          onClick = onApply,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      ) {
        if (busy) CircularProgressIndicator(color = AppColor.AccentInk, modifier = Modifier.size(18.dp)) else Text("Apply to Project")
      }
      OutlinedButton(
          onClick = onDismiss,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) { Text("Reject change") }
      Text("Rejecting keeps this selection in Project history without changing the checkpoint.", color = AppColor.InkSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
  }
}
