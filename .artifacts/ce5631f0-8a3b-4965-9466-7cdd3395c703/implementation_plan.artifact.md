# Fix failing unit tests and GitHub check

The unit tests are failing due to a logic error in `FtsUtil` truncation and missing Android environment (Robolectric) for `PushControllerTest`.

## Proposed Changes

### [Util]

#### [MODIFY] [FtsUtil.kt](file:///C:/Users/glen/StudioProjects/3mail/app/src/main/java/com/threemail/android/util/FtsUtil.kt)
- Fix truncation logic to strictly respect `MAX_QUERY_LENGTH` even when no spaces are present in the truncated string.

#### [MODIFY] [FtsUtilTest.kt](file:///C:/Users/glen/StudioProjects/3mail/app/src/test/java/com/threemail/android/util/FtsUtilTest.kt)
- Update the test expectation to match the fixed truncation logic.

### [Build]

#### [MODIFY] [libs.versions.toml](file:///C:/Users/glen/StudioProjects/3mail/gradle/libs.versions.toml)
- Add Robolectric dependency.

#### [MODIFY] [build.gradle.kts](file:///C:/Users/glen/StudioProjects/3mail/app/build.gradle.kts)
- Add Robolectric to `testImplementation`.

### [Push]

#### [MODIFY] [PushControllerTest.kt](file:///C:/Users/glen/StudioProjects/3mail/app/src/test/java/com/threemail/android/push/PushControllerTest.kt)
- Annotate the test class with `@RunWith(RobolectricTestRunner::class)` to allow testing Android framework classes like `Intent`.

## Verification Plan

### Automated Tests
- Run `./gradlew testDebugUnitTest` to verify all 48 tests pass.
