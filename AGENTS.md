# Repository Guidelines

## Project Structure & Module Organization
`app/` is the only Android module. Kotlin and Java sources live in `app/src/main/java/com/close/hook/ads`, split by responsibility: `ui/` for screens and adapters, `hook/` for Xposed and request interception logic, `data/` for Room models and repositories, and `manager/`, `preference/`, and `util/` for shared services. API-specific compatibility code and Xposed metadata live in flavor folders such as `app/src/lsp100` and `app/src/lsp101`. Native interception code is in `app/src/main/cpp`, AIDL contracts in `app/src/main/aidl`, Android resources and translations live under `app/src/main/res`, bundled AARs under `app/libs`, and CI config under `.github/workflows/android.yml`.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root.

- `.\gradlew.bat :app:assembleLsp100Debug` builds the API 100 debug APK.
- `.\gradlew.bat :app:assembleLsp101Debug` builds the API 101 debug APK.
- `.\gradlew.bat :app:assembleLsp100Release :app:assembleLsp101Release` builds both release APKs; this matches CI.
- `.\gradlew.bat lint` runs Android lint checks.
- `.\gradlew.bat testDebugUnitTest` runs JVM unit tests when present.
- `.\gradlew.bat clean` clears Gradle build outputs.

On Linux or macOS, replace `.\gradlew.bat` with `./gradlew`.

## Coding Style & Naming Conventions
The project sets `kotlin.code.style=official`; follow Kotlin official style with 4-space indentation and minimal wildcard imports. Use `PascalCase` for classes (`MainActivity`), `camelCase` for methods and properties, and lowercase package names. Keep XML resource names in `snake_case` with existing prefixes such as `activity_`, `fragment_`, `item_`, `dialog_`, `menu_`, and `ic_`. For native code, keep helper functions in `snake_case` and constants/macros in `UPPER_SNAKE_CASE`. Place new code in the existing layer rather than mixing UI, hook, and persistence concerns in one file.

## Testing Guidelines
There is currently no committed `app/src/test` or `app/src/androidTest` suite. Add JVM tests under `app/src/test/java` for repositories, parsers, serializers, and other pure logic. Add instrumented tests under `app/src/androidTest/java` for Android-specific behavior when needed. Name test files `ThingTest.kt` and prefer method names that describe the scenario and expected result, such as `parseEmptyBody_returnsNull`.

## Commit & Pull Request Guidelines
Recent history uses short imperative subjects such as `fix build.` and `optimize.`; keep commit titles brief, action-oriented, and scoped when useful, for example `hook: guard null loader`. Pull requests should describe the affected area, list local verification steps, link related issues, and include screenshots for UI/resource changes. If a change touches `hook/` or `cpp/`, call out regression risk, supported apps, and any ABI or LSPosed behavior you validated.

## Security & Configuration Tips
Do not commit new secrets, alternate keystores, or local signing changes. Keep Gradle, SDK, and NDK updates aligned with the current module settings in `app/build.gradle.kts`, and review bundled binaries in `app/libs` and `app/src/main/cpp/libs` carefully before upgrading them.
