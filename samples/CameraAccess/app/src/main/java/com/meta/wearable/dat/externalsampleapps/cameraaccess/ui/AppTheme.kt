/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AppTheme - Project Assistant product identity
//
// Wraps app content in a dark Material3 ColorScheme built from AppColor's product palette, so
// every composable that already reads MaterialTheme.colorScheme.* (CameraAccessScaffold,
// BackendInvestigationPanel, MockDeviceKitScreen) picks up the new look automatically. This is
// presentation only - it does not change what any screen shows or how the DAT SDK / Investigation
// backend integration behaves.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ProjectAssistantColorScheme =
    darkColorScheme(
        background = AppColor.Graphite,
        onBackground = AppColor.InkPrimary,
        surface = AppColor.Surface,
        onSurface = AppColor.InkPrimary,
        surfaceVariant = AppColor.Surface,
        onSurfaceVariant = AppColor.InkSecondary,
        primary = AppColor.Accent,
        onPrimary = AppColor.AccentInk,
        secondary = AppColor.InkSecondary,
        tertiary = AppColor.Success,
        error = Color(0xFFFF9B9B),
        onError = Color(0xFF3A1416),
        errorContainer = Color(0xFF3A1B1D),
        onErrorContainer = Color(0xFFFF9B9B),
        outline = Color(0xFF4A4C52),
        outlineVariant = Color(0xFF35373C),
    )

@Composable
fun ProjectAssistantTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = ProjectAssistantColorScheme, content = content)
}
