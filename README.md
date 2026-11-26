# Saavn Lyrics Overlay (Android)

A Kotlin companion app that brings your **Saavn Modern & Lyrics Sidekick** extension features to the installed **JioSaavn Android app** as a floating overlay.

## How it works
- `MediaListenerService` + `MediaSessionTracker` hook into Android's `MediaSessionManager` to read JioSaavn's currently playing song, album art, live playback timestamp, and transport controls (`play`, `pause`, `next`, `previous`, `seekTo`).
- `LyricsLib.kt` & `LrcLibClient.kt` port your exact `lib.js` & `background.js` logic: title/artist normalization, 3-step LRCLIB lookup, LRC timestamp parsing, and cover-art color extraction.
- `OverlayService.kt` draws a draggable, cover-art-tinted floating synced-lyrics card (and compact pill) on top of JioSaavn using `SYSTEM_ALERT_WINDOW`.

## Build & Run
1. Unzip `SaavnLyricsOverlay-android.zip` and open the `SaavnLyricsOverlay` folder in **Android Studio** (Jellyfish or newer).
2. Let Gradle sync (uses Android SDK 34, Kotlin 1.9.24, Material 3, and Coroutines).
3. Connect your Android phone via USB (with **USB Debugging** enabled) and click **Run**.
4. In the app:
   - Tap **1. Grant Notification Access** and enable **Saavn Media Session Listener**.
   - Tap **2. Allow Display Over Other Apps** and enable it for **Saavn Lyrics Overlay**.
   - Tap **Start Floating Lyrics Overlay**, open **JioSaavn**, and play a song.
