# Project Plan

A Native Android (Kotlin) Application for inmates (Working Demo). Features include PIN-pad login, read-only contact directory with live balance, WebRTC video/audio calling with Firestore signaling, native background media recording with a hardware-audit kill-switch, and secure Firebase Storage upload with local cache wipe. Architecture uses XML ViewBinding, Fragments, and a Single Activity.

## Project Brief

# Project Brief: PrisonConnect

## Features
- **Secure PIN-pad Login**: A specialized numeric authentication interface for secure inmate access control.
- **Contact Directory & Live Balance**: A read-only dashboard displaying approved contacts and real-time account balance updates.
- **WebRTC Video/Audio Calling**: Functional peer-to-peer calling capabilities using Firebase Firestore for signaling.
- **Native Call Recording & Firebase Upload**: Background media recording with a hardware-audit kill-switch, ensuring secure Firebase Storage upload and automated local cache wiping for privacy.

## High-Level Technical Stack
- **Kotlin**: Core development language.
- **XML Layouts & ViewBinding**: UI framework and type-safe view interaction.
- **Fragments & Single Activity**: Modular architecture using manual Fragment transactions (explicitly excluding Jetpack Navigation).
- **WebRTC**: Real-time communication protocol for audio and video streaming.
- **Firebase (Firestore & Storage)**: Firestore for signaling and call metadata; Cloud Storage for secure media persistence.
- **Coroutines**: Management of asynchronous operations and background tasks.
- **CameraX**: Native camera and media recording implementation.

## Implementation Steps

### Task_1_Setup_Auth: Initialize Android project with ViewBinding, setup Firebase (Firestore, Storage), and implement the PIN-pad Login Fragment with manual Fragment transactions.
- **Status:** COMPLETED
- **Updates:** Initialized project with ViewBinding, Fragments, and manual navigation. Created KioskMainActivity, LoginFragment with SHA-256 validation, and DashboardFragment placeholder. Setup Firebase dependencies and WebRTC. Project builds successfully.
- **Acceptance Criteria:**
  - Project builds successfully
  - Firebase initialized with google-services.json
  - PIN-pad login UI implemented in XML
  - Login transitions to Dashboard via manual transaction

### Task_2_Dashboard: Implement the Dashboard Fragment using ViewBinding to display a read-only contact directory and live account balance fetched from Firestore.
- **Status:** COMPLETED
- **Updates:** Implemented DashboardFragment with real-time balance updates and contact list. Added logic to block "FACILITY_EMERGENCY" calls. Created ContactAdapter and item layouts. Setup navigation to CallRoomFragment. Project builds successfully.
- **Acceptance Criteria:**
  - Contact list displays data from Firestore
  - Live balance updates in real-time
  - UI handles empty states for contacts

### Task_3_WebRTC_Recording: Implement WebRTC video/audio calling with Firestore signaling. Integrate CameraX for call recording, secure Firebase Storage upload, and automated local cache wiping with a hardware-audit kill-switch.
- **Status:** COMPLETED
- **Updates:** Implemented CallRoomFragment with WebRTC SurfaceViewRenderers and blinking surveillance indicator. Setup Firestore signaling for call rooms. Implemented SecureHardwareRecordingService with 2500ms tamper-check loop, Firebase Storage upload, and local cache wipe. Project builds successfully.
- **Acceptance Criteria:**
  - WebRTC connection established via Firestore signaling
  - Audio/Video streaming functional
  - Call recorded and uploaded to Firebase Storage
  - Local media cache wiped post-upload
  - Kill-switch stops recording if hardware audit fails

### Task_4_Run_Verify: Perform a final run of the application to ensure stability and alignment with all requirements. Instruct critic_agent to verify stability and UI.
- **Status:** COMPLETED
- **Updates:** Completed one-time UI refinement for tablets. Improved Dashboard (3-column grid, max-width cards), Login (centered PIN-pad), and Call Room (scaled local feed) layouts. Critic agent verified the fixes and marked the project as PASS. No crashes or missing features.
- **Acceptance Criteria:**
  - App builds and runs without crashes
  - All features (Login, Dashboard, Calling, Recording) work as expected
  - Existing tests pass
  - Critic agent verifies stability and requirement alignment
- **Duration:** N/A

