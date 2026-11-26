package com.saavn.lyricsoverlay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

data class TrackSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artwork: Bitmap?,
    val isPlaying: Boolean
)

/**
 * Replaces bridge.js + content.js track detection on Android.
 * Hooks into JioSaavn's system MediaSession for metadata, live timestamps, and transport controls.
 */
class MediaSessionTracker(
    private val context: Context,
    private val onTrackChanged: (TrackSnapshot?) -> Unit
) {
    companion object {
        val JIOSAAVN_PACKAGES = setOf(
            "com.jio.media.jiobeats",
            "com.saavn.android"
        )
    }

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    var jioSaavnOnly: Boolean = true
        set(value) {
            field = value
            refreshActiveController()
        }

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emitSnapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emitSnapshot()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickController(controllers.orEmpty())
    }

    fun start() {
        manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        refreshActiveController()
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    fun refreshActiveController() {
        val controllers = runCatching { manager.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())
        pickController(controllers)
    }

    private fun pickController(controllers: List<MediaController>) {
        val chosen = if (jioSaavnOnly) {
            controllers.firstOrNull { it.packageName in JIOSAAVN_PACKAGES }
        } else {
            controllers.firstOrNull { it.packageName in JIOSAAVN_PACKAGES } ?: controllers.firstOrNull()
        }

        if (chosen?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = chosen
            activeController?.registerCallback(controllerCallback)
        }
        emitSnapshot()
    }

    private fun emitSnapshot() {
        val ctrl = activeController
        val meta = ctrl?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (ctrl == null || title.isBlank()) {
            onTrackChanged(null)
            return
        }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING

        onTrackChanged(
            TrackSnapshot(
                packageName = ctrl.packageName,
                title = title,
                artist = artist,
                durationMs = durationMs,
                artwork = art,
                isPlaying = playing
            )
        )
    }

    /** Computes current playback position in seconds, interpolating while playing. */
    fun currentPositionSec(): Double {
        val state = activeController?.playbackState ?: return 0.0
        var posMs = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            posMs += (delta * state.playbackSpeed).toLong()
        }
        return (posMs.coerceAtLeast(0L)) / 1000.0
    }

    fun togglePlayPause() {
        val ctrl = activeController ?: return
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
    }

    fun next() {
        activeController?.transportControls?.skipToNext()
    }

    fun previous() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun seekToSec(sec: Double) {
        activeController?.transportControls?.seekTo((sec.coerceAtLeast(0.0) * 1000.0).toLong())
    }
}
