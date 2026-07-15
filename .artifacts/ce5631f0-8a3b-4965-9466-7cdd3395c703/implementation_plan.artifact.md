# Fix AGP 9.3 Upgrade Failure

The project is currently failing to sync after an upgrade to AGP 9.3.0, Gradle 9.5.0, and Kotlin 2.2.10. The main error is a KSP class-loading issue, likely caused by version incompatibility and conflicts with the new "Built-in Kotlin" infrastructure in AGP 9.0+.

## User Review Required

> [!IMPORTANT]
> **Built-in Kotlin Migration**: AGP 9.0 introduces native support for Kotlin, making the `org.jetbrains.kotlin.android` plugin redundant and potentially causing conflicts. I propose migrating to this new system.
>
> **KSP Version**: KSP versions must be strictly aligned with the Kotlin version. I will update it to a compatible `2.2.10-x` version.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/glen/StudioProjects/3mail/gradle/libs.versions.toml)
- Update `hilt` to `2.60.1` for better compatibility with Kotlin 2.2.10.
- Fix `ksp` version to `2.2.10-2.0.21` (or latest matching 2.2.10).
- Ensure `kotlin` is consistently `2.2.10`.

#### [MODIFY] [gradle.properties](file:///C:/Users/glen/StudioProjects/3mail/gradle.properties)
- Enable `android.newDsl=true` and `android.builtInKotlin=true` to follow AGP 9.3 best practices and resolve plugin conflicts.
- Remove redundant legacy flags.

#### [MODIFY] [build.gradle.kts (root)](file:///C:/Users/glen/StudioProjects/3mail/build.gradle.kts)
- Remove `alias(libs.plugins.kotlin.android)` as it's now built into AGP 9.3.
- Remove `alias(libs.plugins.kotlin.compose)` as Compose support is also integrated via the new `kotlin.compose` plugin or built-in flags.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/glen/StudioProjects/3mail/app/build.gradle.kts)
- Remove `alias(libs.plugins.kotlin.android)`.
- Update the `android` block to use the new DSL if necessary (e.g. `kotlinOptions` is now often handled differently).
- Ensure `compileSdk` and `targetSdk` are appropriate (34 is fine, but maybe 35 if available).

## Verification Plan

### Automated Tests
- Run `gradle sync` to verify the build configuration is valid.
- Run `./gradlew testDebugUnitTest` to ensure no regressions.

### Manual Verification
- Verify that the app can be assembled: `./gradlew assembleDebug`.
