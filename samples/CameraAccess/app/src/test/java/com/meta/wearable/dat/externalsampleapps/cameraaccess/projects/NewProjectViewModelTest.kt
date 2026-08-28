package com.meta.wearable.dat.externalsampleapps.cameraaccess.projects

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers NewProjectViewModel's synchronous validation/gating paths only - the paths that return
 * before ever reaching viewModelScope.launch, so they need no coroutine test dispatcher or
 * instrumentation. submit() checks blank name/goal and the in-flight guard synchronously and
 * returns immediately in each case (see NewProjectViewModel.kt), which is also why
 * UnreachableProjectRepository below is safe to use as a hard failure signal: if any of these
 * tests reach the repository, the required-field/duplicate-press gate has regressed.
 *
 * The success/backend-failure paths (which do reach viewModelScope.launch and therefore need a
 * live Main dispatcher) are covered by the instrumented ui/NewProjectScreenTest.kt instead,
 * matching how ProjectWorkspaceScreenTest.kt already covers ProjectDetailViewModel's async paths.
 */
class NewProjectViewModelTest {

  private class UnreachableProjectRepository : ProjectRepository by MockProjectRepository() {
    override suspend fun createProject(request: NewProjectRequest): ProjectSummary =
        error("createProject must not be called when the form is invalid or already submitting")
  }

  private fun newViewModel(): NewProjectViewModel =
      NewProjectViewModel(Application(), UnreachableProjectRepository())

  @Test
  fun blankNameFailsWithoutCallingRepository() {
    val viewModel = newViewModel()

    viewModel.submit(name = "  ", goal = "Ship the MVP", currentObjective = "", nextAction = "")

    val state = viewModel.submitState.value
    assertTrue(state is NewProjectSubmitState.Failed)
    assertEquals("Project name is required.", (state as NewProjectSubmitState.Failed).message)
  }

  @Test
  fun blankGoalFailsWithoutCallingRepository() {
    val viewModel = newViewModel()

    viewModel.submit(name = "Garage Door Sensor", goal = "   ", currentObjective = "", nextAction = "")

    val state = viewModel.submitState.value
    assertTrue(state is NewProjectSubmitState.Failed)
    assertEquals("Goal is required.", (state as NewProjectSubmitState.Failed).message)
  }

  @Test
  fun blankNameIsCheckedBeforeBlankGoal() {
    val viewModel = newViewModel()

    viewModel.submit(name = "", goal = "", currentObjective = "", nextAction = "")

    val state = viewModel.submitState.value
    assertEquals("Project name is required.", (state as NewProjectSubmitState.Failed).message)
  }

  @Test
  fun dismissErrorReturnsFailedStateToIdle() {
    val viewModel = newViewModel()
    viewModel.submit(name = "", goal = "Ship the MVP", currentObjective = "", nextAction = "")
    assertTrue(viewModel.submitState.value is NewProjectSubmitState.Failed)

    viewModel.dismissError()

    assertEquals(NewProjectSubmitState.Idle, viewModel.submitState.value)
  }

  @Test
  fun dismissErrorIsANoOpWhenNotFailed() {
    val viewModel = newViewModel()

    viewModel.dismissError()

    assertEquals(NewProjectSubmitState.Idle, viewModel.submitState.value)
  }

  @Test
  fun acknowledgeSuccessIsANoOpWhenNotSucceeded() {
    val viewModel = newViewModel()
    viewModel.submit(name = "", goal = "Ship the MVP", currentObjective = "", nextAction = "")

    viewModel.acknowledgeSuccess()

    // Must not clear an unrelated Failed state - acknowledgeSuccess only ever consumes Succeeded.
    assertTrue(viewModel.submitState.value is NewProjectSubmitState.Failed)
  }
}
