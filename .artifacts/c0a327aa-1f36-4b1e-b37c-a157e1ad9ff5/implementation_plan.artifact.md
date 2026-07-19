# Implementation Plan - Fix SecurityException: Unknown calling package name 'com.google.android.gms'

The application is encountering a `java.lang.SecurityException` with the message "Unknown calling package name 'com.google.android.gms'" when interacting with Google Play Services (GMS). This is a common issue when targeting Android 11 (API 30) or higher due to package visibility restrictions, or when there is a mismatch/misconfiguration in the GMS client libraries.

## User Review Required

> [!IMPORTANT]
> The fix primarily involves adding a `<queries>` declaration to the `AndroidManifest.xml`. This allows the application to "see" and interact with the Google Play Services package on devices running Android 11+.

## Proposed Changes

### [Component] Android Manifest

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/AndroidManifest.xml)
- Add a `<queries>` block to explicitly declare dependency on `com.google.android.gms`.
- This ensures that the GMS client library can properly identify and bind to the Google Play Services process.

### [Component] Dependencies

#### [MODIFY] [libs.versions.toml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/gradle/libs.versions.toml)
- Ensure Firebase and GMS versions are stable and compatible with API 35. (Currently they seem okay, but I will check for minor updates if needed).

### [Component] Initialization & Error Handling

#### [MODIFY] [LoginFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/LoginFragment.kt)
- Add a check for Google Play Services availability before initiating Firebase Authentication. This provides better error feedback if GMS is missing or outdated on the device.

## Verification Plan

### Automated Tests
- N/A (This is a runtime environment/configuration issue).

### Manual Verification
1. Build and deploy the app to an Android 11+ device or emulator with Google Play Services.
2. Observe the `LoginFragment` initialization.
3. Verify that `signInAnonymously()` completes successfully without the `SecurityException`.
4. Check Logcat for any "Failed to get service from broker" messages.
