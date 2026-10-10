# Task List - Drive Fixes, Clipboard, and Auto-Versioning

- `[x]` Implement Auto-Incrementing Version
    - `[x]` Create `version.properties`
    - `[x]` Update `app/build.gradle.kts` with version increment logic
- `[x]` Refine `GoogleDriveSyncManager.kt`
    - `[x]` Add detailed error info to `Result`
    - `[x]` Improve `getOrCreateFolder` robustness
- `[x]` Update `MapViewModel.kt`
    - `[x]` Add `CopyToClipboard` event
    - `[x]` Refine `syncToGoogleDrive` to handle "Connect" vs "Open"
    - `[x]` Update `onSignInResult` for immediate feedback
- `[x]` Update `MapScreen.kt`
    - `[x]` Display version number from `BuildConfig`
    - `[x]` Handle `CopyToClipboard` event
    - `[x]` Update bottom button text dynamically
- `[ ]` Verify build and functionality
