package com.saavn.lyricsoverlay

import android.service.notification.NotificationListenerService

/**
 * Required by Android's MediaSessionManager so our app can read active media sessions
 * (song metadata, album art, and exact playback timestamps) from JioSaavn.
 */
class MediaListenerService : NotificationListenerService()
