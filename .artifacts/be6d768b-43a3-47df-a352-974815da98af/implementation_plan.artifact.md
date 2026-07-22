# Final Reliability, UI Polish & Balance Sync

Fix hardware diagnostic false positives, local video Z-ordering, persistent balance updates, and UI selection feedback.

## User Review Required

> [!CAUTION]
> The "Microphone Busy" error was a false positive triggered by WebRTC's own communication mode. I am removing the strict mode check to ensure calls start reliably.

## Proposed Changes

### [MODIFY] [CallDiagnosticHelper.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/webrtc/CallDiagnosticHelper.kt)
- **Relax Mic Check**: Remove the `MicBusy` check that detected `MODE_IN_COMMUNICATION`. This state is often entered as soon as the WebRTC stack initializes, causing a loop that prevents the call from starting.

### [MODIFY] [CallRoomFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/CallRoomFragment.kt)
- **Local Video Z-Order**: Ensure `binding.localView.setZOrderMediaOverlay(true)` is called before initialization so it stays on top of the remote video.
- **Button Feedback**:
    - Update `btn_video_mic` and `btn_audio_mic` background/icon tints when muted.
    - Update `btn_audio_speaker` alpha/tint when toggled.
- **Persistent Balance Update**: Use `GlobalScope` (or a similar long-lived scope) with `NonCancellable` to ensure the final balance update request to Supabase completes even if the Fragment is already destroyed.
- **Polling Glitch Fix**: Add local state checks to prevent `showCallUi` from being called repeatedly if the state hasn't changed.

### [MODIFY] [index.html](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/index.html)
- **Full Screen**: Force the container to `100vw`/`100vh` and remove all padding to make it truly responsive.
- **Hangup Icon**: Replace the current SVG with a standard phone-down icon for better clarity.

## Verification Plan

### Automated Tests
- Build APK to verify resource IDs.

### Manual Verification
1. **Connect Flow**: Verify the call screen doesn't "flicker" (cut and reappear) on start.
2. **PIP**: Verify local video is visible in the corner.
3. **Mute/Speaker**: Verify buttons visually change when tapped.
4. **Database**: End a call and verify the `users` table shows the updated balance.
