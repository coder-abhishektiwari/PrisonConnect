# Implementation Plan - PrisonConnect Refactoring & Call Separation

This plan outlines the refactoring of the PrisonConnect application, including the separation of Audio and Video call logic into distinct Activities to ensure maximum reliability and lifecycle safety.

## User Review Required

> [!IMPORTANT]
> **Architectural Shift**: We will transition from a single `CallRoomFragment` to two dedicated activities: `VideoCallActivity` and `AudioCallActivity`. This isolates the WebRTC hardware resources (Camera/Mic) and prevents state conflicts between different call types.

> [!WARNING]
> **Web Compatibility**: The signaling protocol (Supabase broadcast events) will remain 100% compatible with the existing web client. The separation is internal to the Android app's presentation layer.

## Open Questions
- Should the `SecureHardwareRecordingService` be started before or after the activity transition? (Proposed: Inside the respective Call Activity's `onCreate`).

## Proposed Changes

### 1. Presentation Layer (Activities & Fragments)
- [MODIFY] [KioskMainActivity](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/KioskMainActivity.kt): Update navigation to launch dedicated Activities for calls.
- [NEW] `VideoCallActivity.kt`: Dedicated activity for video calls, containing logic from `CallRoomFragment` optimized for video.
- [NEW] `AudioCallActivity.kt`: Dedicated activity for audio-only calls, optimized for low power and high audio clarity.
- [DELETE] [CallRoomFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/CallRoomFragment.kt): Its logic will be distributed into the new activities and their respective ViewModels.

### 2. Domain & Data Layers (Same as before)
- [NEW] `UserRepository.kt`, `CallRepository.kt`, `ContactRepository.kt`: Repository pattern implementation.
- [NEW] `CallViewModel.kt`: Shared base logic for call handling, with specialized implementations for Video and Audio.

### 3. WebRTC & Signaling Optimization
- [MODIFY] [WebRtcManager.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/webrtc/WebRtcManager.kt): Enhance to explicitly support "Audio-Only" mode without initializing camera hardware.
- [MODIFY] [SignalingClient.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/webrtc/SignalingClient.kt): Ensure the "web-ready" and "otp-verified" events are handled reliably across Activity recreations.

### 4. Manifest Update
- [MODIFY] [AndroidManifest.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/AndroidManifest.xml): Register `VideoCallActivity` and `AudioCallActivity` with appropriate `configChanges` to prevent unwanted restarts.

---

## Refactoring Workflow

1.  **Repository Setup**: Implement `UserRepository` and `ContactRepository`.
2.  **Base Call Logic**: Create a shared `BaseCallViewModel` to handle signaling and room polling.
3.  **Video Call Implementation**: Create `VideoCallActivity` and its layout.
4.  **Audio Call Implementation**: Create `AudioCallActivity` and its layout.
5.  **Dashboard Update**: Update `DashboardFragment` to launch these activities instead of replacing fragments.
6.  **Verification**: Test both call types and ensure signaling matches the web client's expectations.

## Verification Plan

### Automated Tests
- Verify compilation and Android Manifest integrity.
- Monitor Logcat for WebRTC state transitions (NEW -> CHECKING -> CONNECTED).

### Manual Verification
- **Video Call**: Verify camera switch, local/remote video rendering, and recording.
- **Audio Call**: Verify proximity sensor behavior (if applicable), mic muting, and speaker toggle.
- **Web Compatibility**: Verify that a web client can still connect to either call type using the same signaling flow.
