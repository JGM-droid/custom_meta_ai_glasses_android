/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.ui.graphics.Color

object AppColor {
  val Green = Color(0xFF61BC63)
  val Red = Color(0xFFFF3B30)
  val Yellow = Color(0xFFFFCC00)
  val DeepBlue = Color(0xFF0064E0)
  val DestructiveBackground = Color(0xFFFFD8DB)
  val DestructiveForeground = Color(0xFFAA071E)

  // Project Assistant product palette - shared visual language with the web dashboard
  // (dashboard.html's :root tokens). Additive: existing tokens above are untouched so nothing
  // that already references them (e.g. MockDeviceKitScreen) needs to change in this slice.
  val Graphite = Color(0xFF17181B)
  val Surface = Color(0xFF1E2024)
  val InkPrimary = Color(0xFFECECEE)
  val InkSecondary = Color(0xFFA1A1AA)
  val Accent = Color(0xFF8EA1FF)
  val AccentInk = Color(0xFF11131C)
  val Success = Color(0xFF7FD9AC)
  val Amber = Color(0xFFE8B04B)
}
