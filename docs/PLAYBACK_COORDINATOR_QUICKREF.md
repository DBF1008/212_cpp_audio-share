# PlaybackCoordinator Quick Reference

## Overview
`PlaybackCoordinator` is a singleton object that provides unified, system-restriction-aware playback startup for Audio Share Android.

## Usage Examples

### Basic Usage (No Callback)
```kotlin
val result = PlaybackCoordinator.startPlayback(
    context = this,
    source = PlaybackCoordinator.StartSource.BOOT
)

when (result) {
    is PlaybackCoordinator.StartResult.Success -> {
        Log.d("MyClass", "Playback started successfully")
    }
    is PlaybackCoordinator.StartResult.Failure -> {
        Log.w("MyClass", "Failed to start: ${result.reason}")
    }
}
```

### With Callback
```kotlin
PlaybackCoordinator.startPlayback(
    context = this,
    source = PlaybackCoordinator.StartSource.QS_TILE,
    callback = object : PlaybackCoordinator.ResultCallback {
        override fun onResult(result: PlaybackCoordinator.StartResult) {
            when (result) {
                is PlaybackCoordinator.StartResult.Success -> {
                    updateUI(true)
                }
                is PlaybackCoordinator.StartResult.Failure -> {
                    showError(result.reason)
                }
            }
        }
    }
)
```

### Check Before Starting
```kotlin
if (PlaybackCoordinator.canStartPlayback(context, source)) {
    PlaybackCoordinator.startPlayback(context, source, callback)
} else {
    showRestrictionMessage()
}
```

## Start Sources

| Source | Description | Restriction Check | Typical Use |
|--------|-------------|-------------------|-------------|
| `BOOT` | System boot completed | Yes | BootService |
| `QS_TILE` | Quick settings tile clicked | Yes | QsTileService |
| `FOREGROUND` | Normal foreground interaction | No | MainActivity |

## Failure Reasons

| Reason | Description | Recommended Action |
|--------|-------------|-------------------|
| `SYSTEM_RESTRICTION` | Cannot start foreground service | Open MainActivity or inform user |
| `CONTROLLER_ERROR` | Failed to create MediaController | Log error, allow retry |
| `PLAYBACK_ERROR` | Failed to start playback | Log error, allow retry |
| `CANCELLED` | Request was cancelled | No action needed |

## System Restrictions Checked

The coordinator checks these Android restrictions:

1. **Battery Optimization** (Android 6+)
   - App on battery optimization whitelist
   - Uses `PowerManager.isIgnoringBatteryOptimizations()`

2. **Android Version**
   - Pre-Android 12: Fewer restrictions
   - Android 12+: Background service restrictions apply

3. **App Visibility** (Android 13+)
   - Checks if app has visible tasks
   - Uses `ActivityManager.appTasks`

## Error Handling Patterns

### Pattern 1: Log and Continue (Boot)
```kotlin
when (result) {
    is StartResult.Success -> Log.d(TAG, "Success")
    is StartResult.Failure -> Log.w(TAG, "Failed: ${result.reason}")
}
```

### Pattern 2: Fallback to UI (Tile)
```kotlin
when (result) {
    is StartResult.Success -> delay(1.seconds)
    is StartResult.Failure -> {
        if (result.reason == FailureReason.SYSTEM_RESTRICTION) {
            openMainActivity()
        }
    }
}
```

### Pattern 3: Show Error (Foreground)
```kotlin
when (result) {
    is StartResult.Success -> showPlayingUI()
    is StartResult.Failure -> showErrorUI(result.reason)
}
```

## Best Practices

1. **Always handle both Success and Failure**
   ```kotlin
   when (result) {
       is StartResult.Success -> { /* ... */ }
       is StartResult.Failure -> { /* ... */ }
   }
   ```

2. **Log failure reasons for diagnostics**
   ```kotlin
   is StartResult.Failure -> {
       Log.w(TAG, "Failed: ${result.reason}")
       analytics.logFailure(result.reason)
   }
   ```

3. **Use appropriate source for your entry point**
   ```kotlin
   // From boot receiver
   source = PlaybackCoordinator.StartSource.BOOT
   
   // From quick settings
   source = PlaybackCoordinator.StartSource.QS_TILE
   
   // From UI
   source = PlaybackCoordinator.StartSource.FOREGROUND
   ```

4. **Consider using callback for UI updates**
   ```kotlin
   callback = object : ResultCallback {
       override fun onResult(result: StartResult) {
           runOnUiThread { updateUI(result) }
       }
   }
   ```

## Adding New Entry Points

### Step 1: Add to StartSource enum
```kotlin
enum class StartSource {
    BOOT,
    QS_TILE,
    FOREGROUND,
    WIDGET  // Add new source
}
```

### Step 2: Update restriction logic (if needed)
```kotlin
val canStart = when (source) {
    StartSource.BOOT, StartSource.QS_TILE -> context.canStartForegroundService()
    StartSource.FOREGROUND, StartSource.WIDGET -> true  // Widget is user interaction
}
```

### Step 3: Use the coordinator
```kotlin
val result = PlaybackCoordinator.startPlayback(
    context = widgetContext,
    source = PlaybackCoordinator.StartSource.WIDGET,
    callback = widgetCallback
)
```

## Testing

### Unit Test Example
```kotlin
@Test
fun `should handle system restriction`() {
    val result = PlaybackCoordinator.StartResult.Failure(
        source = PlaybackCoordinator.StartSource.BOOT,
        reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
    )
    
    assertEquals(PlaybackCoordinator.StartSource.BOOT, result.source)
    assertEquals(PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION, result.reason)
}
```

### Integration Test Example
```kotlin
@Test
fun `foreground startup should succeed`() = runBlocking {
    val result = PlaybackCoordinator.startPlayback(
        context = appContext,
        source = PlaybackCoordinator.StartSource.FOREGROUND,
        callback = null
    )
    
    assertTrue(result is PlaybackCoordinator.StartResult.Success)
}
```

## Troubleshooting

### Playback won't start from boot
1. Check if auto-start is enabled in settings
2. Check if app is on battery optimization whitelist
3. Look for `SYSTEM_RESTRICTION` in logs
4. Guide user to disable battery optimization

### Tile click has no effect
1. Check logs for failure reason
2. If `SYSTEM_RESTRICTION`: App needs foreground interaction
3. If `CONTROLLER_ERROR`: PlaybackService may not be running
4. Verify tile is listening for state changes

### Tile shows wrong state
1. Verify `TileService.requestListeningState()` is called
2. Check `PlaybackService` player listener
3. Ensure tile updates on `playWhenReady` changes

## API Reference

### startPlayback()
```kotlin
suspend fun startPlayback(
    context: Context,
    source: StartSource,
    callback: ResultCallback? = null
): StartResult
```
Attempts to start playback with system restriction awareness.

**Parameters:**
- `context`: The context used to start the service
- `source`: The source of the start request
- `callback`: Optional callback for receiving the result

**Returns:** `StartResult` - Success or Failure with reason

### canStartPlayback()
```kotlin
fun canStartPlayback(
    context: Context,
    source: StartSource
): Boolean
```
Checks if playback can be started without actually starting it.

**Parameters:**
- `context`: The context to check restrictions
- `source`: The source to check for

**Returns:** `Boolean` - true if playback can be started

## Related Classes

- `PlaybackService`: The MediaSessionService that plays audio
- `BootService`: Handles boot-time startup
- `QsTileService`: Handles quick settings tile
- `Context.canStartForegroundService()`: System restriction check

## Documentation

- Full architecture: `docs/UNIFIED_STARTUP_CHAIN.md`
- Change log: `docs/CHANGELOG_STARTUP_CHAIN.md`
- Android docs: [Foreground Services](https://developer.android.com/guide/components/foreground-services)
