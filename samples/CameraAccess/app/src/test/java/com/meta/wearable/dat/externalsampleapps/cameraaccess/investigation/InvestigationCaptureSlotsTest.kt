package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationCaptureSlotsTest {
  @Test
  fun fiveCapturesAppendInDeterministicOrder() {
    var images = InvestigationCaptureSlots.emptySlots()
    repeat(5) { index ->
      val result =
          InvestigationCaptureSlots.appendEvidence(
              images,
              evidence(slotIndex = 99, filename = "capture_${index + 1}.jpg"),
          )
      assertEquals(index, result.appendedSlotIndex)
      images = result.images
    }

    val ordered = InvestigationCaptureSlots.orderedEvidence(images)
    assertEquals(5, ordered.size)
    assertEquals((1..5).map { "capture_$it.jpg" }, ordered.map { it.filename })
    assertEquals((0..4).toList(), ordered.map { it.slotIndex })
  }

  @Test
  fun sixthCaptureIsPreventedWhenFull() {
    val slots = InvestigationCaptureSlots.emptySlots().mapIndexed { index, slot ->
      slot.copy(evidence = evidence(index, "capture_${index + 1}.jpg"))
    }

    val result = InvestigationCaptureSlots.appendEvidence(slots, evidence(99, "six.jpg"))

    assertNull(result.appendedSlotIndex)
    assertEquals(slots, result.images)
    assertFalse(InvestigationCaptureSlots.hasCapacity(result.images))
  }

  @Test
  fun selectedCountAndCapacityReflectPartialPopulation() {
    val oneSelected = InvestigationCaptureSlots.emptySlots().mapIndexed { index, slot ->
      if (index == 0) slot.copy(evidence = evidence(0, "one.jpg")) else slot
    }

    assertEquals(1, InvestigationCaptureSlots.selectedCount(oneSelected))
    assertTrue(InvestigationCaptureSlots.hasCapacity(oneSelected))
  }

  @Test
  fun localPickerUriIsCountedAsOccupiedSlot() {
    val withUri = InvestigationCaptureSlots.emptySlots().mapIndexed { index, slot ->
      if (index == 0) slot.copy(uriString = "content://picker/one", displayName = "one.jpg") else slot
    }

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
