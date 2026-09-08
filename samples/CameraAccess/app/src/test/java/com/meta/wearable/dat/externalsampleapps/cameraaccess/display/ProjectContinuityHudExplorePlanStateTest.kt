/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.CheckpointProposalReview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ExplorePlanOverviewSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectCheckpoint
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rich Project Intelligence V1 (ADR-059): the bounded three-state EXPLORE_PLAN HUD projection -
 * see ProjectHudExplorePlanSummary's doc for why "applied" needs no dedicated state here.
 */
class ProjectContinuityHudExplorePlanStateTest {
  private val projectId = "11111111-1111-1111-1111-111111111111"

  @Test
  fun noExplorePlanYetProducesNoHudContent() {
    val content = ProjectContinuityHudStateMachine.mapOverview(baseOverview(latestExplorePlan = null), "Room Redesign")
    assertNull(content.explorePlan)
  }

  @Test
  fun beforeSelectionShowsBoundedRecommendationHeadlineOnly() {
    val plan = ExplorePlanOverviewSummary(
        resultId = "interaction-1",
        recommendedOptionTitle = "Warm Modern",
        selectedOptionTitle = null,
        hasPendingSelectionProposal = false,
    )
    val content = ProjectContinuityHudStateMachine.mapOverview(baseOverview(latestExplorePlan = plan), "Room Redesign")

    val summary = requireNotNull(content.explorePlan)
    assertEquals("3 design ideas ready. AI recommends Warm Modern. Review on phone.", summary.headline)
    assertTrue(!summary.awaitingApplyReview)
    // Never the option cards/rationale/tradeoffs themselves - only this one bounded line.
    assertTrue(summary.headline.length < 180)
  }

  @Test
  fun selectedButNotYetAppliedShowsAwaitingReviewState() {
    val plan = ExplorePlanOverviewSummary(
        resultId = "interaction-1",
        recommendedOptionTitle = "Warm Modern",
        selectedOptionTitle = "Warm Modern",
        hasPendingSelectionProposal = true,
    )
    val content = ProjectContinuityHudStateMachine.mapOverview(
        baseOverview(
            latestExplorePlan = plan,
            pendingProposals = listOf(
                CheckpointProposalReview(
                    proposalId = "proposal-1",
                    projectId = projectId,
                    status = "pending",
                    reason = "User selected \"Warm Modern\" as the preferred direction",
                    proposedFields = mapOf("current_work" to "Selected direction: Warm Modern."),
                    sourceActivityIds = listOf("idea-1"),
                ),
            ),
        ),
        "Room Redesign",
    )

    val summary = requireNotNull(content.explorePlan)
    assertEquals("Selected direction: Warm Modern — awaiting review on your phone.", summary.headline)
    assertTrue(summary.awaitingApplyReview)
  }

  @Test
  fun appliedSelectionShowsNoSeparateExplorePlanHeadline() {
    // Once Apply has run, hasPendingSelectionProposal is false and whereWeLeftOff/nextAction
    // (already asserted in ProjectContinuityHudStateTest) carry the selected direction instead -
    // a second, separately-worded Explore headline would just duplicate the canonical Checkpoint.
    val plan = ExplorePlanOverviewSummary(
        resultId = "interaction-1",
        recommendedOptionTitle = "Warm Modern",
        selectedOptionTitle = "Warm Modern",
        hasPendingSelectionProposal = false,
    )
    val content = ProjectContinuityHudStateMachine.mapOverview(baseOverview(latestExplorePlan = plan), "Room Redesign")
    assertNull(content.explorePlan)
  }

  @Test
  fun recommendationAloneNeverImpliesUserSelection() {
    // An AI recommendation is not a user selection - hasPendingSelectionProposal only becomes
    // true from an explicit SELECT (see ExplorePlanViewModel.selectOption), never inferred here.
    val plan = ExplorePlanOverviewSummary(
        resultId = "interaction-1",
        recommendedOptionTitle = "Warm Modern",
        selectedOptionTitle = null,
        hasPendingSelectionProposal = false,
    )
    val content = ProjectContinuityHudStateMachine.mapOverview(baseOverview(latestExplorePlan = plan), "Room Redesign")
    val summary = requireNotNull(content.explorePlan)
    assertTrue(!summary.awaitingApplyReview)
    assertTrue(summary.headline.contains("Review on phone"))
  }

  private fun baseOverview(
      latestExplorePlan: ExplorePlanOverviewSummary?,
      pendingProposals: List<CheckpointProposalReview> = emptyList(),
  ): ProjectOverview =
      ProjectOverview(
          project = ProjectSummary(projectId, "Room Redesign", "active"),
          checkpoint = ProjectCheckpoint(whereWeLeftOff = "Chose a design direction.", nextAction = "Explore options."),
          recentActivity = emptyList(),
          pendingProposals = pendingProposals,
          latestExplorePlan = latestExplorePlan,
      )
}
