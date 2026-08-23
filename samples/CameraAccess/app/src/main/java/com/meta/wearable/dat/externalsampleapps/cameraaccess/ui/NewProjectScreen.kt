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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectSubmitState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

@Composable
fun NewProjectScreen(
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
) {
  val submitState by viewModel.submitState.collectAsState()
  var name by remember { mutableStateOf("") }
  var goal by remember { mutableStateOf("") }
  var currentObjective by remember { mutableStateOf("") }
  var nextAction by remember { mutableStateOf("") }

  LaunchedEffect(submitState) {
    val state = submitState
    if (state is NewProjectSubmitState.Succeeded) {
      onCreated(state.project)
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
    )
    ProjectFormField(
        label = "Goal",
        value = goal,
        onValueChange = { goal = it },
        placeholder = "What are you trying to accomplish?",
        singleLine = false,
        modifier = Modifier.padding(top = 16.dp),
    )
    ProjectFormField(
        label = "Current Objective (optional)",
        value = currentObjective,
        onValueChange = { currentObjective = it },
        placeholder = "What are you focused on right now?",
        modifier = Modifier.padding(top = 16.dp),
    )
    ProjectFormField(
        label = "Next Action (optional)",
        value = nextAction,
        onValueChange = { nextAction = it },
        placeholder = "What's the next concrete step?",
        modifier = Modifier.padding(top = 16.dp),
    )

    val failure = submitState as? NewProjectSubmitState.Failed
    if (failure != null) {
      Text(
          text = failure.message,
          color = Color(0xFFFF9B9B),
          modifier = Modifier.padding(top = 16.dp),
      )
    }

    val isSubmitting = submitState is NewProjectSubmitState.Submitting
    Button(
        onClick = { viewModel.submit(name, goal, currentObjective, nextAction) },
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 24.dp),
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
  }
}

@Composable
private fun ProjectFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
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
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
  }
}
