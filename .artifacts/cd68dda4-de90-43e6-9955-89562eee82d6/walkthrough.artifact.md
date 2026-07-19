# Walkthrough: Contact Directory Fix

This walkthrough documents the fix for the Contact Directory issue where contacts were not being correctly identified or linked to their calls.

## Summary of Changes

### 1. Firestore `@DocumentId` Integration
Modified the data models to use the `@DocumentId` annotation from the Firebase Firestore library.

- **[Contact.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/model/Contact.kt)**: Added `@DocumentId` to the `contact_id` field.
- **[User.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/model/User.kt)**: Ensured `id` field uses `@DocumentId`.

### 2. Login ID Propagation
Updated the navigation logic in the login flow to ensure the unique Firestore Document ID is correctly passed to subsequent screens.

- **[LoginFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/LoginFragment.kt)**: Updated `verifyPin` to pass `document.id` as the `user_id` argument when navigating to the `DashboardFragment`.

## Why This Fixed The Issue

The issue was caused by a mismatch between the fields defined in the Kotlin data classes and the actual structure of the Firestore documents:

1.  **Implicit IDs**: Firestore documents often use their auto-generated Document ID as the primary identifier rather than a field *inside* the document JSON. Without `@DocumentId`, the `contact_id` field in the `Contact` class remained empty when Firestore's `toObject()` method was called, as there was no matching "contact\_id" field in the document data.
2.  **Broken Navigation**: Because `contact_id` was empty, the `DashboardFragment` was passing null or empty strings to the `CallRoomFragment`, leading to failures in initializing calls.
3.  **Correct Mapping**: The `@DocumentId` annotation tells Firestore to map the document's metadata (its unique ID) directly into the annotated field during deserialization.

## Verification

### Automated Verification
- **Build Status**: Successfully executed `./gradlew assembleDebug`. The project compiles without errors, confirming that the Firestore annotations and navigation arguments are correctly typed and integrated.

## Instructions to the User

To fully verify this fix:
1.  **Deploy the App**: Install the latest build on a test kiosk device or emulator.
2.  **Login**: Use a valid PIN to log in.
3.  **Check Contacts**: Ensure the dashboard loads the contact list for the logged-in inmate.
4.  **Initiate Call**: Tap on a contact's audio or video call icon. Verify that the app navigates to the calling screen and that the contact's name/details are correctly displayed (indicating the ID was passed successfully).
