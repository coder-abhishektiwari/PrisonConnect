# Task List - PrisonConnect Refactoring

## Phase 1: Foundation & Data Layer
- [ ] Create `UserRepository.kt` and `ContactRepository.kt`
- [ ] Refactor `DbService.kt` for better error handling and logging
- [ ] Update `SupabaseConfig.kt` with improved initialization

## Phase 2: Domain Layer & Repositories
- [ ] Create `CallRepository.kt` to manage room lifecycle
- [ ] Implement UseCases for login and contact fetching

## Phase 3: WebRTC & Signaling Cleanup
- [ ] Refactor `WebRtcManager.kt` for explicit Audio/Video separation
- [ ] Improve `SignalingClient.kt` reliability

## Phase 4: Presentation Layer (Activities & ViewModels)
- [ ] Create `BaseCallViewModel.kt`
- [ ] Create `VideoCallActivity.kt` and `layout/activity_video_call.xml`
- [ ] Create `AudioCallActivity.kt` and `layout/activity_audio_call.xml`
- [ ] Update `DashboardFragment.kt` to launch new activities
- [ ] Implement `LoginViewModel.kt` and refactor `LoginFragment.kt`
- [ ] Implement `DashboardViewModel.kt` and refactor `DashboardFragment.kt`

## Phase 5: Verification & Cleanup
- [ ] Update `AndroidManifest.xml`
- [ ] Remove `CallRoomFragment.kt` and `SecureCallViewModel.kt`
- [ ] Final performance and memory leak check
