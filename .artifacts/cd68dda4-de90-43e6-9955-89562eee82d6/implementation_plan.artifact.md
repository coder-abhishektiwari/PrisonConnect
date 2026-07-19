# Debug Contacts Not Appearing in Dashboard

The issue is likely due to a mismatch between the `user_id` passed to the `DashboardFragment` and the actual document ID of the user in Firestore, or a failure to correctly map the document ID to the `User` model.

## User Review Required

> [!IMPORTANT]
> I will be changing how the `user_id` is retrieved and passed during login. Instead of relying on a `user_id` field within the document, I will use the Firestore document ID directly, which is a more reliable identifier.

## Proposed Changes

### Model Layer

#### [MODIFY] [User.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/model/User.kt)
- Add `@DocumentId` annotation to `user_id` to ensure it always reflects the Firestore document ID when using `toObject()`.

#### [MODIFY] [Contact.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/model/Contact.kt)
- Add `@DocumentId` annotation to `contact_id`.

### UI Layer

#### [MODIFY] [LoginFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/LoginFragment.kt)
- Use `documents.documents[0].id` to pass the user identifier to `DashboardFragment`, ensuring consistency with how `DashboardFragment` fetches data.

## Verification Plan

### Automated Tests
- I will attempt to build the project to ensure no syntax errors were introduced.
- Since I don't have a live Firestore emulator with data, I will rely on code analysis and manual verification if possible.

### Manual Verification
- Verify that the `user_id` passed to `DashboardFragment` matches the document ID "VBvOLKaYxJeSGu7PZyNi" as mentioned in the user's screenshot.
- Verify that `fetchContacts` now uses the correct ID to query the `contacts` collection.
