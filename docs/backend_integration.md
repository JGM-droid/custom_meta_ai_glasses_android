# Android Backend Integration

## Scope

This document describes the current CameraAccess debug integration for canonical Investigation Session workflow validation against the backend contract.

Implemented now:
- Configurable backend base URL with emulator default.
- Session creation and ordered image evidence upload.
- Explanation submission through normalized_text on evidence upload.
- Canonical session analyze trigger call.
- Conditional session polling until terminal status.
- Structured error mapping into debug UI state.
- Duplicate-submission protection and cancellation-safe coroutine behavior.

Not implemented in this milestone:
- Real Meta DAT stream/photo capture wiring into this workflow.
- Production investigation UI outside the existing debug panel.

## Base URL Configuration

CameraAccess app module configuration:
- Property: investigation_backend_base_url
- BuildConfig field: INVESTIGATION_BACKEND_BASE_URL
- Default fallback: http://10.0.2.2:8001

Set via:
1. samples/CameraAccess/local.properties
2. Gradle property: -Pinvestigation_backend_base_url=...

Example:
- investigation_backend_base_url=http://10.0.2.2:8001

## Test Isolation

The default backend URL above is a real, potentially long-lived backend instance (a developer's
local `uvicorn api:app`, reachable from the emulator via `10.0.2.2`), not a disposable test
double. Confirmed live-data pollution in the backend's canonical acceptance Projects ("AC Repair",
"Room Redesign") traced to this: Investigation dry-run/demo activity and manual exploration ended
up written into real canonical Project storage rather than an isolated one, alongside disposable
test-created Projects.

`AppRootTest.kt`'s own Project-mutating tests are already gated behind an explicit
`-e allow_project_backend_mutation true` instrumentation argument for exactly this reason - do not
run them (or any other ad-hoc dry-run/demo Investigation call) against a backend instance that also
hosts real, named acceptance Projects you care about keeping clean. Point instrumented test runs,
and any interactive dry-run exploration, at a disposable backend instance with its own throwaway
`PROJECTS_ROOT` instead.

## Canonical Session Contract Used

Backend authority:
- custom_meta_ai_glasses/docs/project_constitution.md
- custom_meta_ai_glasses/docs/investigation_session_api_v1.md
- custom_meta_ai_glasses/code/prototype_v1/api.py
- custom_meta_ai_glasses/code/prototype_v1/investigations/models.py

Android sequence now implemented:
1. Validate 1 to 3 selected images.
2. POST /investigation-sessions
3. POST /investigation-sessions/{session_id}/evidence/image for each image, preserving selected order.
4. Send explanation text only through normalized_text on first image evidence upload.
5. POST /investigation-sessions/{session_id}/analyze with JSON body {} (or expected_revision when provided in future client flow).
6. If analyze response is already completed with result_available=true and compact_result present, return immediately.
7. Otherwise poll GET /investigation-sessions/{session_id}/poll until terminal state or bounded timeout.

The previous backend capability-gap stop is resolved.

## Analyze Request and Response

Request body:
- expected_revision: optional integer >= 0
- Current Android debug flow sends an empty JSON object, which matches backend optional payload semantics.

Analyze response fields used:
- session_id
- investigation_id (nullable)
- status
- accepted
- result_available
- compact_result (nullable)
- retryable
- error (nullable)
- poll_url

Synchronous behavior:
- The backend executes session analysis synchronously in-process for this milestone.
- Android still treats the response as possibly nonterminal and falls back to polling when required.

## Polling Behavior

Android polls only when analyze response is nonterminal or result is not yet available.

Stop conditions:
- completed
- failed
- cancelled
- non-retryable backend error
- coroutine cancellation
- bounded attempt limit or total timeout

Polling delay:
- Uses backend poll_after_ms when present.
- Delay is clamped client-side to [250, 60000] ms for safety.

## Explanation and Image Ordering Rules

- Image order is preserved exactly as selected in the debug panel.
- Explanation text is sent only through normalized_text in evidence upload.
- Analyze call does not resend image bytes.

## Error Mapping

Structured backend errors are mapped from detail.category and detail.message.

Handled categories include:
- session_not_found (404)
- invalid_state_transition (409)
- analysis_attempt_conflict (409)
- revision_conflict (409)
- insufficient_evidence / invalid_evidence / too_many_images (422)
- missing_explanation (422)
- provider_failure and other safe 500 categories

Client-side non-backend failures:
- network errors
- serialization/contract errors
- timeout
- cancellation

## Duplicate Submission and Cancellation

- Repository uses a submission mutex and returns DuplicateSubmission when a second submit arrives while one is active.
- Cancelled coroutine stops further polling and prevents extra workflow progress events from the cancelled path.

## Debug Verification Procedure

In MockDeviceKit debug screen:
1. Open Backend Investigation panel.
2. Select 1 to 3 images in order.
3. Enter explanation text.
4. Submit once.
5. Observe states: preparing, creating session, uploading evidence, initiating analysis, polling (if needed), completed/failed/cancelled.
6. Verify session_id, investigation_id, analyze accepted flag, backend status, poll timing, compact result, and error category/message.

## Remaining Work Before Real DAT Capture Wiring

- Connect CameraAccess capture outputs directly into InvestigationSubmissionDraft.
- Connect real speech-to-text pipeline to normalized explanation input.
- Add non-debug product UI flow around the same repository/client path.
