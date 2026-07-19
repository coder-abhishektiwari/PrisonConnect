# Refinement Loop: Tablet/Large Screen UI Improvement

Improve the layout for tablets to prevent stretched UI and implement adaptive patterns for PrisonConnect.

## User Review Required

> [!IMPORTANT]
> The changes involve using resource qualifiers (`-sw600dp`) to provide different values for tablets. I will also be modifying the `DashboardFragment.kt` to support a grid layout for contacts.

## Proposed Changes

### [Component Name] Resources

#### [MODIFY] [integers.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values/integers.xml)
- Ensure `contact_columns` is 1 for phone.

#### [MODIFY] [integers.xml (sw600dp)](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values-sw600dp/integers.xml)
- Set `contact_columns` to 3 for tablet.

#### [NEW] [dimens.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values/dimens.xml)
- Define `content_max_width` as 0 (no limit).
- Define `local_view_width` as 120dp.
- Define `local_view_height` as 160dp.

#### [NEW] [dimens.xml (sw600dp)](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/values-sw600dp/dimens.xml)
- Define `content_max_width` as 600dp.
- Define `local_view_width` as 240dp.
- Define `local_view_height` as 320dp.
- Define `login_max_width` as 480dp.

### [Component Name] Layouts

#### [MODIFY] [fragment_dashboard.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_dashboard.xml)
- Apply `app:layout_constraintWidth_max` to `card_balance` and header elements.
- Ensure `rv_contacts` respects the maximum width as well if necessary, or let it span full width but items in grid.

#### [MODIFY] [fragment_call_room.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_call_room.xml)
- Use `@dimen/local_view_width` and `@dimen/local_view_height` for `local_view`.
- Adjust `recording_indicator` constraints or padding for better visibility on tablets.

#### [MODIFY] [fragment_login.xml](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/res/layout/fragment_login.xml)
- Limit maximum width of the PIN pad area using `app:layout_constraintWidth_max`.

### [Component Name] Code

#### [MODIFY] [DashboardFragment.kt](file:///C:/Users/ASUS/AndroidStudioProjects/PrisonConnect/app/src/main/java/com/example/prisonconnect/DashboardFragment.kt)
- Update `setupRecyclerView()` to use `GridLayoutManager` with span count from resources.

## Verification Plan

### Automated Tests
- Build the app using `./gradlew assembleDebug` to ensure no resource errors.

### Manual Verification
- Verify the layout on a phone (Portrait) and a tablet (sw600dp+) to see the adaptive changes.
- Check if Dashboard shows 1 column on phone and 3 columns on tablet.
- Check if Login PIN pad is centered and not overly stretched on tablet.
- Check if Call Room local feed is larger on tablet.
