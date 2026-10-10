# Implementation Plan - Drive Fixes, Clipboard, and Auto-Increment Version

Resolve Drive connection issues, add clipboard support for the local path, and implement an auto-incrementing version number displayed in the UI.

## Proposed Changes

### 1. Clipboard and Path Visibility

#### [MODIFY] [MapViewModel.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/ui/map/MapViewModel.kt)
- Add `CopyToClipboard(text: String)` to `MapUiEvent`.
- Update `showLocalPath()` to emit both a `ShowToast` and `CopyToClipboard` event.

#### [MODIFY] [MapScreen.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/ui/map/MapScreen.kt)
- Handle the `CopyToClipboard` event using `LocalClipboardManager.current`.

### 2. Google Drive Workflow and Robustness

#### [MODIFY] [MapViewModel.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/ui/map/MapViewModel.kt)
- Update `syncToGoogleDrive()` logic:
    - If `driveStatus` is not "Cloud Connecté", trigger the sign-in flow instead of opening Drive.
    - If `driveStatus` is "Cloud Connecté", open the folder URL.
- Improve `onSignInResult` to provide immediate feedback if the result is cancelled.

#### [MODIFY] [MapScreen.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/ui/map/MapScreen.kt)
- Update the bottom button text dynamically based on connection status.

#### [MODIFY] [GoogleDriveSyncManager.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/sync/GoogleDriveSyncManager.kt)
- Enhance error reporting in `getOrCreateFolder` to catch API-specific exceptions.

### 3. Auto-Incrementing Version Number

#### [NEW] [version.properties](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/version.properties)
- Create a simple properties file to store the current `buildNumber`.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/build.gradle.kts)
- Add logic to read `version.properties`, increment the `buildNumber`, and use it for `versionCode` and `versionName`.
- The increment will happen during the configuration phase or via a dedicated task.

#### [MODIFY] [MapScreen.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/ui/map/MapScreen.kt)
- Display `BuildConfig.VERSION_NAME` at the very top of the screen (e.g., in the status header).

## Verification Plan

### Manual Verification
1. **Clipboard:** Long-press the Drive button. Verify the path is copied and can be pasted.
2. **Connection:** Verify clicking "Connecter Drive" signs in and updates the status to "Cloud Connecté" without jumping away to the Drive app prematurely.
3. **Versioning:** Build the app twice. Verify the version number shown at the top increases (e.g., `1.0.42` -> `1.0.43`).
