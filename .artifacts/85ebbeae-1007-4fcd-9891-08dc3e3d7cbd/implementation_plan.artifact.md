# Implementation Plan - Tablet/Large Screen UI Improvements

Improve the adaptive layout for PrisonConnect on tablets and large screens, focusing on the Dashboard, Call Room, and Login screens.

## User Review Required

> [!NOTE]
> I will be using resource qualifiers (`sw600dp`) and programmatic checks to implement adaptive layouts. This is the standard Android approach for View-based applications.

## Proposed Changes

### Configuration Resources

#### [NEW] [integers.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values/integers.xml)
- Define `contact_columns` (default 1).

#### [NEW] [integers.xml (sw600dp)](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values-sw600dp/integers.xml)
- Define `contact_columns` (2 for tablets).

#### [NEW] [dimens.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values/dimens.xml)
- Define `max_content_width` (default 0dp/match_parent).
- Define `call_local_view_width` (120dp).
- Define `call_local_view_height` (160dp).

#### [NEW] [dimens.xml (sw600dp)](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values-sw600dp/dimens.xml)
- Define `max_content_width` (600dp).
- Define `call_local_view_width` (240dp).
- Define `call_local_view_height` (320dp).

### Dashboard Component

#### [MODIFY] [fragment_dashboard.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_dashboard.xml)
- Use `app:layout_constraintWidth_max` with `@dimen/max_content_width` for the balance card and header background (if applicable).
- Center the balance card if width is limited.

#### [MODIFY] [DashboardFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/DashboardFragment.kt)
- Update `setupRecyclerView` to use `GridLayoutManager` with the column count from `@integer/contact_columns`.

### Call Room Component

#### [MODIFY] [fragment_call_room.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_call_room.xml)
- Update `local_view` dimensions to use `@dimen/call_local_view_width` and `@dimen/call_local_view_height`.
- Ensure `recording_indicator` stays appropriately positioned.

### Login Component

#### [MODIFY] [fragment_login.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_login.xml)
- Limit the maximum width of the PIN display and button grid using `app:layout_constraintWidth_max="@dimen/max_content_width"`.

## Verification Plan

### Automated Tests
- Build the project: `./gradlew assembleDebug` to ensure no layout/resource errors.

### Manual Verification
- The user can verify on a tablet emulator or device:
    - Dashboard should show 2 columns for contacts.
    - Balance card and Login PIN pad should be centered and not too wide.
    - Call room local feed should be larger on tablets.
