/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// ProjectWorkspaceScreen - minimal "Continue Project" placeholder
//
// Proves "Continue Project" on ProjectDetailScreen carries the selected project through
// explicit navigation state (AppRoot.TopLevelScreen.ProjectWorkspace(project)). Deliberately no
// composer, no capture, no AI behavior yet - that is a later slice, once this shell is accepted.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

@Composable
fun ProjectWorkspaceScreen(
    project: ProjectSummary,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier =
          modifier
              .fillMaxSize()
              .background(AppColor.Graphite)
              .systemBarsPadding()
              .padding(horizontal = 20.dp),
  ) {
    TextButton(
        onClick = onBack,
        colors = ButtonDefaults.textButtonColors(contentColor = AppColor.InkPrimary),
    ) {
      Text("‹ Projects")
    }

    Text(
        text = "Project Workspace — ${project.name}",
        color = AppColor.InkPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )

    Text(
        text = "The composer, capture, and AI-assisted workspace behavior aren't implemented yet.",
        color = AppColor.InkSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
  }
}
