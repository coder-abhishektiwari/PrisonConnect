# Implementation Plan: Fix WebRTC Audio-Only Connection

This plan addresses the WebRTC renegotiation issue where audio-only calls fail to connect. We will implement explicit media constraints, transceiver initialization, and fallback signaling logic.

## Proposed Changes

### [Android App]

#### [MODIFY] [WebRtcManager.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/webrtc/WebRtcManager.kt)
- In `setupPeerConnection(isVideo: Boolean)`, explicitly add an Audio Transceiver if `isVideo` is false. This ensures the SDP contains the necessary audio M-lines even when no tracks are added yet.

#### [MODIFY] [CallRoomFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/CallRoomFragment.kt)
- In `onWebReady()`, update `MediaConstraints` to explicitly set `OfferToReceiveAudio = true` and `OfferToReceiveVideo = (callType == "VIDEO")`.
- In `activateCallSession()`, implement a 1.5-second fallback delay that triggers `onWebReady()` if the web client hasn't emitted the event.

### [Web UI]

#### [MODIFY] [index.html](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/index.html)
- Ensure the `web-ready` event is broadcasted reliably after the session is authorized and local media is ready.
- Verify `RTCPeerConnection` handling of audio-only offers.

## Verification Plan

### Manual Verification
1. **Audio Call Test**:
   - Start an audio call from the Android app.
   - Join from the Web client.
   - Verify that the SDP Offer/Answer handshake completes.
   - Verify that ICE candidates are exchanged and the connection state moves to `CONNECTED`.
   - Verify that audio is transmitted in both directions.
2. **Video Call Regression**:
   - Verify that standard video calls still work correctly.
