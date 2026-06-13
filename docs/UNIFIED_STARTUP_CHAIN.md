# Unified Background Startup Coordination Chain

## Overview

This document describes the unified background startup coordination chain implemented for Audio Share Android. The new architecture consolidates the previously fragmented playback startup logic from `BootService` and `QsTileService` into a single, observable, and system-restriction-aware coordinator.

## Problem Statement

### Before
The original implementation had two separate startup flows:

1. **BootService**: 
   - Directly created `MediaController` and called `play()`
   - No system restriction checking
   - No failure handling or feedback
   - Could fail silently on Android 12+ due to background service restrictions

2. **QsTileService**:
   - Checked `canStartForegroundService()` before starting
   - Fell back to opening `MainActivity` when restricted
   - No structured error reporting
   - No state synchronization on failure
   - Tile state could become inconsistent

### Issues
- **Behavior drift**: Different entry points had different behavior
- **Silent failures**: Users might click buttons with no feedback
- **Inconsistent state**: Tile state might not reflect actual playback state
- **Poor diagnostics**: No structured way to track failures
- **Android 12+ compatibility**: Background service restrictions not uniformly handled

## Solution: PlaybackCoordinator

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    PlaybackCoordinator                       │
│  (Singleton - Centralized startup logic)                    │
├─────────────────────────────────────────────────────────────┤
│  • System restriction checking                              │
│  • Unified startup flow                                     │
│  • Structured result feedback                               │
│  • Failure reason reporting                                 │
│  • State synchronization                                    │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │
            ┌───────────────┼───────────────┐
            │               │               │
    ┌───────┴──────┐ ┌─────┴──────┐ ┌─────┴──────────┐
    │  BootService │ │ QsTileService│ │   Foreground   │
    │   (Boot)     │ │  (QS Tile)  │ │  (Activity)    │
    └──────────────┘ └──────────────┘ └────────────────┘
```

### Key Components

#### 1. StartSource Enum
Identifies the origin of the startup request:
- `BOOT`: System boot completed
- `QS_TILE`: Quick settings tile clicked
- `FOREGROUND`: Normal foreground interaction

#### 2. StartResult Sealed Class
Represents the outcome of a startup attempt:
```kotlin
sealed class StartResult {
    data class Success(val source: StartSource) : StartResult()
    data class Failure(val source: StartSource, val reason: FailureReason) : StartResult()
}
```

#### 3. FailureReason Enum
Categorizes failure types:
- `SYSTEM_RESTRICTION`: Cannot start foreground service due to system restrictions
- `CONTROLLER_ERROR`: Failed to create or connect MediaController
- `PLAYBACK_ERROR`: Failed to start playback
- `CANCELLED`: Request was cancelled

#### 4. ResultCallback Interface
Optional callback for receiving results asynchronously:
```kotlin
interface ResultCallback {
    fun onResult(result: StartResult)
}
```

### Core Methods

#### `startPlayback(context, source, callback)`
Main entry point for starting playback:
1. Checks system restrictions based on source
2. Creates MediaController if allowed
3. Starts playback
4. Returns structured result
5. Invokes callback if provided

#### `canStartPlayback(context, source)`
Checks if playback can be started without actually starting it:
- Returns `true` for FOREGROUND source
- Checks system restrictions for BOOT and QS_TILE sources

## System Restriction Handling

### Android 12+ Background Service Restrictions
The coordinator uses `Context.canStartForegroundService()` which checks:

1. **Battery optimization exemption**: App is on battery optimization whitelist
2. **Android version**: Pre-Android 12 has fewer restrictions
3. **App visibility**: App has visible tasks (Android 13+)

### Source-Specific Behavior

| Source | Restriction Check | Fallback Behavior |
|--------|------------------|-------------------|
| BOOT | Yes | Log failure, continue |
| QS_TILE | Yes | Open MainActivity |
| FOREGROUND | No | N/A |

## Implementation Details

### BootService Changes
**Before:**
```kotlin
val mediaController = MediaController.Builder(this@BootService, sessionToken)
    .setConnectionHints(bundleOf("src" to "BootService"))
    .buildAsync().await()
mediaController.play()
delay(3.seconds)
mediaController.release()
```

**After:**
```kotlin
val result = PlaybackCoordinator.startPlayback(
    context = this@BootService,
    source = PlaybackCoordinator.StartSource.BOOT
)

when (result) {
    is PlaybackCoordinator.StartResult.Success -> {
        Log.d(tag, "Playback started successfully from boot")
    }
    is PlaybackCoordinator.StartResult.Failure -> {
        Log.w(tag, "Failed to start playback from boot: ${result.reason}")
    }
}
```

### QsTileService Changes
**Before:**
```kotlin
if (applicationContext.canStartForegroundService()) {
    play()
    delay(1.seconds)
} else {
    Log.d(tag, "can't start foreground service")
    val intent = Intent(applicationContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivityAndCollapse(intent)
}
```

**After:**
```kotlin
val result = PlaybackCoordinator.startPlayback(
    context = this@QsTileService,
    source = PlaybackCoordinator.StartSource.QS_TILE,
    callback = object : PlaybackCoordinator.ResultCallback {
        override fun onResult(result: PlaybackCoordinator.StartResult) {
            Log.d(tag, "Playback start result: $result")
        }
    }
)

when (result) {
    is PlaybackCoordinator.StartResult.Success -> {
        Log.d(tag, "Playback started successfully from QS tile")
        delay(1.seconds)
    }
    is PlaybackCoordinator.StartResult.Failure -> {
        Log.w(tag, "Failed to start playback: ${result.reason}")
        handleStartFailure(result.reason)
    }
}
```

## Failure Handling

### QsTileService Failure Handling
```kotlin
private fun handleStartFailure(reason: PlaybackCoordinator.FailureReason) {
    when (reason) {
        SYSTEM_RESTRICTION -> {
            // Open MainActivity to allow user to start from foreground
            openMainActivity()
        }
        CONTROLLER_ERROR -> {
            // Keep tile inactive, user can retry
            Log.e(tag, "Failed to create MediaController")
        }
        PLAYBACK_ERROR -> {
            // Keep tile inactive, user can retry
            Log.e(tag, "Failed to start playback")
        }
        CANCELLED -> {
            Log.d(tag, "Playback start was cancelled")
        }
    }
}
```

## Observability

### Logging
All startup attempts are logged with:
- Source of the request
- Success/failure outcome
- Failure reason (if applicable)

### Diagnostics
Failure reasons are structured and can be:
- Persisted for later analysis
- Reported to analytics
- Displayed to users (future enhancement)

### State Synchronization
- Tile state automatically updates via `PlaybackService` listener
- Tile requests listening state on playback changes
- Consistent behavior across all entry points

## Testing

### Unit Tests (`PlaybackCoordinatorTest.kt`)
- Enum value validation
- Result data class equality
- Callback invocation
- Pattern matching
- Source/reason descriptions

### Instrumented Tests (`PlaybackCoordinatorInstrumentedTest.kt`)
- System restriction detection
- Callback invocation in real environment
- Foreground startup behavior
- Singleton verification
- All sources and reasons defined

## Migration Guide

### For New Entry Points
To add a new entry point for playback startup:

1. Add new value to `StartSource` enum
2. Update `startPlayback()` restriction logic if needed
3. Call `PlaybackCoordinator.startPlayback(context, source, callback)`
4. Handle result appropriately for your UI

### Example: Widget Entry Point
```kotlin
class AudioWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        GlobalScope.launch {
            val result = PlaybackCoordinator.startPlayback(
                context = context,
                source = PlaybackCoordinator.StartSource.WIDGET, // Add to enum
                callback = null
            )
            
            // Update widget UI based on result
            when (result) {
                is StartResult.Success -> showPlayingState(context)
                is StartResult.Failure -> showErrorState(context, result.reason)
            }
        }
    }
}
```

## Benefits

1. **Consistency**: All entry points use the same logic
2. **Observability**: Structured results and logging
3. **Maintainability**: Single source of truth for startup logic
4. **Testability**: Easy to test different scenarios
5. **Extensibility**: Easy to add new entry points
6. **User Experience**: Clear feedback on failures
7. **Android 12+ Compatibility**: Proper handling of background restrictions

## Future Enhancements

Potential improvements:
1. **Persistent diagnostics log**: Track failure patterns over time
2. **User-facing error messages**: Show specific failure reasons
3. **Retry logic**: Automatic retry for transient failures
4. **Analytics integration**: Report failure rates and reasons
5. **Widget support**: Add WIDGET to StartSource enum
6. **Voice assistant integration**: Add VOICE_COMMAND source

## References

- [Android Background Execution Limits](https://developer.android.com/guide/components/foreground-services#background-start-restrictions)
- [Android 12 Foreground Service Restrictions](https://developer.android.com/about/versions/12/foreground-services)
- [MediaSession and MediaController](https://developer.android.com/guide/topics/media/media-controller)
