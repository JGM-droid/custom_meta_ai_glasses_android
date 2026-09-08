/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-061 conversation handoff (Phase 2 Blocker 1) - focused, directly-testable coverage for the
 * pure legacy-UI gating decisions StreamScreen makes. See shouldShowShareDialog/
 * shouldShowInvestigationPanel/shouldShowInvestigationReopenAffordance's own doc in
 * StreamScreen.kt for why: StreamScreen itself needs a live DAT DeviceSession/Compose host and
 * isn't practically unit-testable at this level, but these decisions are.
 *
 * Root cause this closes: capturePhoto()'s success path always sets isShareDialogVisible for
 * ANY capture (phone button or HUD-triggered), and hasActiveInvestigation() becomes true the
 * moment a HUD Use appends evidence - both of which used to surface the legacy Share/Continue-to-
 * Investigation dialog and the floating "Resume Investigation" affordance for a
 * ProjectConversation glasses bridge session, letting the user wander into the old Investigation
 * trust/Analyze workflow ADR-061 replaces for this path.
 */
class StreamScreenLegacyUiGateTest {

  // --- shouldShowShareDialog ---

  @Test
  fun shareDialogIsSuppressedInConversationBridgeModeEvenWhenTheUnderlyingSignalIsTrue() {
    assertFalse(shouldShowShareDialog(returnToConversation = true, isShareDialogVisible = true))
  }

  @Test
  fun shareDialogStillShowsForTheLegacyEntryPointWhenTheUnderlyingSignalIsTrue() {
    assertTrue(shouldShowShareDialog(returnToConversation = false, isShareDialogVisible = true))
  }

  @Test
  fun shareDialogNeverShowsWhenTheUnderlyingSignalIsFalseRegardlessOfMode() {
    assertFalse(shouldShowShareDialog(returnToConversation = false, isShareDialogVisible = false))
    assertFalse(shouldShowShareDialog(returnToConversation = true, isShareDialogVisible = false))
  }

  // --- shouldShowInvestigationPanel ---

  @Test
  fun investigationPanelIsSuppressedInConversationBridgeModeEvenWhenTheUnderlyingSignalIsTrue() {
    assertFalse(shouldShowInvestigationPanel(returnToConversation = true, isInvestigationPanelVisible = true))
  }

  @Test
  fun investigationPanelStillShowsForTheLegacyEntryPointWhenTheUnderlyingSignalIsTrue() {
    assertTrue(shouldShowInvestigationPanel(returnToConversation = false, isInvestigationPanelVisible = true))
  }

  // --- shouldShowInvestigationReopenAffordance ---

  @Test
  fun reopenAffordanceIsSuppressedInConversationBridgeModeEvenWithActiveInvestigation() {
    assertFalse(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = true,
            isInvestigationPanelVisible = false,
            isShareDialogVisible = false,
            hasActiveInvestigation = true,
        ),
    )
  }

  @Test
  fun reopenAffordanceStillShowsForTheLegacyEntryPointWithActiveInvestigation() {
    assertTrue(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = false,
            isInvestigationPanelVisible = false,
            isShareDialogVisible = false,
            hasActiveInvestigation = true,
        ),
    )
  }

  @Test
  fun reopenAffordanceNeverShowsWithoutActiveInvestigationRegardlessOfMode() {
    assertFalse(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = false,
            isInvestigationPanelVisible = false,
            isShareDialogVisible = false,
            hasActiveInvestigation = false,
        ),
    )
  }

  @Test
  fun reopenAffordanceNeverShowsWhileAnotherSheetOrDialogIsAlreadyUp() {
    assertFalse(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = false,
            isInvestigationPanelVisible = true,
            isShareDialogVisible = false,
            hasActiveInvestigation = true,
        ),
    )
    assertFalse(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = false,
            isInvestigationPanelVisible = false,
            isShareDialogVisible = true,
            hasActiveInvestigation = true,
        ),
    )
  }

  // --- shouldShowConversationCapturePreview ---
  //
  // Root cause this closes (a corrected regression, not new scope): suppressing the legacy Share
  // dialog for a conversation-originated capture (above) also suppressed the ONLY surface that
  // rendered capturedPhoto at all, silently dropping the required phone-side capture preview the
  // product has always needed - the user must be able to visually verify what the glasses just
  // captured before Use/Retake. This gate is deliberately the mirror image of
  // shouldShowShareDialog: true exactly where that one is false but a photo is actually pending.

  @Test
  fun conversationCapturePreviewShowsInConversationBridgeModeWhenAPhotoIsPending() {
    assertTrue(shouldShowConversationCapturePreview(returnToConversation = true, hasCapturedPhoto = true))
  }

  @Test
  fun conversationCapturePreviewNeverShowsForTheLegacyEntryPoint() {
    // The legacy entry point already gets its own preview via the Share dialog - never both.
    assertFalse(shouldShowConversationCapturePreview(returnToConversation = false, hasCapturedPhoto = true))
  }

  @Test
  fun conversationCapturePreviewNeverShowsWithoutAPendingPhoto() {
    assertFalse(shouldShowConversationCapturePreview(returnToConversation = true, hasCapturedPhoto = false))
    assertFalse(shouldShowConversationCapturePreview(returnToConversation = false, hasCapturedPhoto = false))
  }

  // --- resolvePhoneCaptureSurface ---
  //
  // ADR-061 cutover: the single authoritative, exhaustive decision composing the two predicates
  // above - a sealed `when` over PhoneCaptureSurface, not two independently-evaluated booleans, so
  // the compiler (not just careful authorship) guarantees the legacy dialog and the conversation
  // preview can never both resolve true for the same pending photo.

  @Test
  fun resolverPicksTheConversationPreviewInConversationBridgeMode() {
    assertEquals(
        PhoneCaptureSurface.ConversationPreview,
        resolvePhoneCaptureSurface(returnToConversation = true, isShareDialogVisible = false),
    )
    // Even if isShareDialogVisible is somehow still true (should never happen - capturePhoto()
    // only ever sets it false in this mode - but this proves the resolver, not just that
    // upstream invariant, is what keeps the legacy dialog from winning).
    assertEquals(
        PhoneCaptureSurface.ConversationPreview,
        resolvePhoneCaptureSurface(returnToConversation = true, isShareDialogVisible = true),
    )
  }

  @Test
  fun resolverPicksTheLegacyShareDialogForTheLegacyEntryPoint() {
    assertEquals(
        PhoneCaptureSurface.LegacyShareDialog,
        resolvePhoneCaptureSurface(returnToConversation = false, isShareDialogVisible = true),
    )
  }

  @Test
  fun resolverPicksNeitherSurfaceForTheLegacyEntryPointWithNoVisibleSignal() {
    assertEquals(
        PhoneCaptureSurface.None,
        resolvePhoneCaptureSurface(returnToConversation = false, isShareDialogVisible = false),
    )
  }

  // --- shouldShowConversationAcceptedCaptureBanner ---
  //
  // ADR-061 architect review, Item 2: required UX gap this closes - between Use and
  // Continue-on-phone, the phone previously showed nothing. This gate is the mirror image of
  // shouldShowInvestigationReopenAffordance: true exactly where that one is false (returnToConversation)
  // but the same hasActiveInvestigation signal is true, so the two banners can never both show.

  @Test
  fun acceptedCaptureBannerShowsInConversationBridgeModeOnceEvidenceIsAccepted() {
    assertTrue(shouldShowConversationAcceptedCaptureBanner(returnToConversation = true, hasActiveInvestigation = true))
  }

  @Test
  fun acceptedCaptureBannerNeverShowsForTheLegacyEntryPoint() {
    // The legacy entry point already gets its own "Resume Investigation" affordance - never both.
    assertFalse(shouldShowConversationAcceptedCaptureBanner(returnToConversation = false, hasActiveInvestigation = true))
  }

  @Test
  fun acceptedCaptureBannerNeverShowsBeforeAnythingIsAccepted() {
    assertFalse(shouldShowConversationAcceptedCaptureBanner(returnToConversation = true, hasActiveInvestigation = false))
    assertFalse(shouldShowConversationAcceptedCaptureBanner(returnToConversation = false, hasActiveInvestigation = false))
  }
}
