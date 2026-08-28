package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-testable core of the Create Project button's gating rule: the button must stay disabled
 * until both required fields (matching NewProjectViewModel's own blank-name/blank-goal checks)
 * have non-blank content. See isNewProjectFormValid in NewProjectScreen.kt.
 */
class NewProjectScreenLogicTest {

  @Test
  fun blankNameAndBlankGoalIsInvalid() {
    assertFalse(isNewProjectFormValid(name = "", goal = ""))
  }

  @Test
  fun whitespaceOnlyNameIsTreatedAsBlank() {
    assertFalse(isNewProjectFormValid(name = "   ", goal = "Ship the MVP"))
  }

  @Test
  fun whitespaceOnlyGoalIsTreatedAsBlank() {
    assertFalse(isNewProjectFormValid(name = "Garage Door Sensor", goal = "   "))
  }

  @Test
  fun nameWithoutGoalIsInvalid() {
    assertFalse(isNewProjectFormValid(name = "Garage Door Sensor", goal = ""))
  }

  @Test
  fun goalWithoutNameIsInvalid() {
    assertFalse(isNewProjectFormValid(name = "", goal = "Ship the MVP"))
  }

  @Test
  fun nameAndGoalBothPresentIsValid() {
    assertTrue(isNewProjectFormValid(name = "Garage Door Sensor", goal = "Ship the MVP"))
  }
}
