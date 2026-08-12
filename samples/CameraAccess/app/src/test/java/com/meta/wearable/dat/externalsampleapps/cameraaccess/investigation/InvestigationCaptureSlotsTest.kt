package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationCaptureSlotsTest {
  @Test
  fun firstSecondThirdCaptureAppendInOrder() {
    val empty =
        listOf(
            InvestigationImageSlotUiState(slotIndex = 0),
            InvestigationImageSlotUiState(slotIndex = 1),
            InvestigationImageSlotUiState(slotIndex = 2),
        )

    val first =
        InvestigationCaptureSlots.appendEvidence(
            empty,
            evidence(slotIndex = 99, filename = "capture_1.jpg"),
        )
    assertEquals(0, first.appendedSlotIndex)

    val second =
        InvestigationCaptureSlots.appendEvidence(
            first.images,
            evidence(slotIndex = 99, filename = "capture_2.jpg"),
        )
    assertEquals(1, second.appendedSlotIndex)

    val third =
        InvestigationCaptureSlots.appendEvidence(
            second.images,
            evidence(slotIndex = 99, filename = "capture_3.jpg"),
        )
    assertEquals(2, third.appendedSlotIndex)

    val ordered = InvestigationCaptureSlots.orderedEvidence(third.images)
    assertEquals(3, ordered.size)
    assertEquals("capture_1.jpg", ordered[0].filename)
    assertEquals("capture_2.jpg", ordered[1].filename)
    assertEquals("capture_3.jpg", ordered[2].filename)
    assertEquals(0, ordered[0].slotIndex)
    assertEquals(1, ordered[1].slotIndex)
    assertEquals(2, ordered[2].slotIndex)
  }

  @Test
  fun fourthCaptureIsPreventedWhenFull() {
    val slots =
        listOf(
            InvestigationImageSlotUiState(slotIndex = 0, evidence = evidence(0, "one.jpg")),
            InvestigationImageSlotUiState(slotIndex = 1, evidence = evidence(1, "two.jpg")),
            InvestigationImageSlotUiState(slotIndex = 2, evidence = evidence(2, "three.jpg")),
        )

    val result = InvestigationCaptureSlots.appendEvidence(slots, evidence(99, "four.jpg"))

    assertNull(result.appendedSlotIndex)
    assertEquals(slots, result.images)
    assertFalse(InvestigationCaptureSlots.hasCapacity(result.images))
  }

  @Test
  fun selectedCountAndCapacityReflectPartialPopulation() {
    val oneSelected =
        listOf(
            InvestigationImageSlotUiState(slotIndex = 0, evidence = evidence(0, "one.jpg")),
            InvestigationImageSlotUiState(slotIndex = 1),
            InvestigationImageSlotUiState(slotIndex = 2),
        )

    assertEquals(1, InvestigationCaptureSlots.selectedCount(oneSelected))
    assertTrue(InvestigationCaptureSlots.hasCapacity(oneSelected))
  }

  @Test
  fun localPickerUriIsCountedAsOccupiedSlot() {
    val withUri =
        listOf(
            InvestigationImageSlotUiState(slotIndex = 0, uriString = "content://picker/one", displayName = "one.jpg"),
            InvestigationImageSlotUiState(slotIndex = 1),
            InvestigationImageSlotUiState(slotIndex = 2),
        )

    assertEquals(1, InvestigationCaptureSlots.selectedCount(withUri))
    assertTrue(InvestigationCaptureSlots.hasCapacity(withUri))
  }

  private fun evidence(slotIndex: Int, filename: String): InvestigationEvidenceInput {
    return InvestigationEvidenceInput(
        slotIndex = slotIndex,
        filename = filename,
        mimeType = "image/jpeg",
        bytes = byteArrayOf(1, 2, 3),
        source = InvestigationEvidenceSource.LIVE_GLASSES,
    )
  }
}
