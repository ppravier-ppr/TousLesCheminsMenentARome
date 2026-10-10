# Implementation Plan - Fix KSP "unexpected jvm signature V" Error

The project is failing to build with a KSP error: `[ksp] java.lang.IllegalStateException: unexpected jvm signature V`. This error typically occurs when KSP (specifically KSP 2.0+ or certain versions of KSP 1.x) encounters a `suspend` function returning `Unit` (JVM signature `V`) in a Room DAO, and the processor fails to handle the signature correctly.

## User Review Required

> [!IMPORTANT]
> The fix involves changing the return type of some Room DAO methods from `Unit` to `Int`. This is a common workaround for this KSP issue and also provides the benefit of knowing how many rows were affected by the operation.

## Proposed Changes

### Database Layer

#### [MODIFY] [TrackDao.kt](file:///C:/Users/pprav/.gemini/antigravity/scratch/EveryStreetTracker/app/src/main/java/com/example/everystreettracker/data/db/TrackDao.kt)
- Change `suspend fun updateTrack(track: TrackEntity)` to return `Int`.
- Change `suspend fun deleteTrack(trackId: Long)` to return `Int`.

## Verification Plan

### Automated Tests
- Run the KSP task specifically to verify it passes:
  ```bash
  ./gradlew :app:kspDebugKotlin
  ```

### Manual Verification
- Ensure the project builds successfully.
