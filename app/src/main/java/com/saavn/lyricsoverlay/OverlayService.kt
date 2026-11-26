package com.saavn.lyricsoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Draws the floating synced-lyrics & mini-player overlay over JioSaavn (SYSTEM_ALERT_WINDOW).
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.saavn.lyricsoverlay.START"
        const val ACTION_STOP = "com.saavn.lyricsoverlay.STOP"
        const val EXTRA_JIOSAAVN_ONLY = "jiosaavn_only"
        private const val CHANNEL_ID = "saavn_overlay_channel"
        private const val NOTIF_ID = 101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var tracker: MediaSessionTracker

    // Views
    private lateinit var pillContainer: View
    private lateinit var pillArt: ImageView
    private lateinit var pillText: TextView
    private lateinit var pillPlayPause: ImageButton

    private lateinit var cardContainer: View
    private lateinit var cardArt: ImageView
    private lateinit var cardTitle: TextView
    private lateinit var cardArtist: TextView
    private lateinit var offsetLabel: TextView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: LinearLayout
    private lateinit var btnPlayPause: ImageButton

    // State
    private var isExpanded = true
    private var currentTrackKey = ""
    private var lines: List<LyricLine> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var activeIndex = -1
    private var offsetSec = 0.0
    private var theme = LyricsLib.extractCoverTheme(null)
    private var lyricsJob: Job? = null
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlayView()

        tracker = MediaSessionTracker(this) { snapshot ->
            handleTrackSnapshot(snapshot)
        }
        tracker.start()
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_JIOSAAVN_ONLY) == true) {
            tracker.jioSaavnOnly = intent.getBooleanExtra(EXTRA_JIOSAAVN_ONLY, true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        lyricsJob?.cancel()
        tracker.stop()
        if (::rootView.isInitialized) {
            runCatching { windowManager.removeView(rootView) }
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupOverlayView() {
        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 180
        }

        pillContainer = rootView.findViewById(R.id.pillContainer)
        pillArt = rootView.findViewById(R.id.pillArt)
        pillText = rootView.findViewById(R.id.pillText)
        pillPlayPause = rootView.findViewById(R.id.pillPlayPause)

        cardContainer = rootView.findViewById(R.id.cardContainer)
        cardArt = rootView.findViewById(R.id.cardArt)
        cardTitle = rootView.findViewById(R.id.cardTitle)
        cardArtist = rootView.findViewById(R.id.cardArtist)
        offsetLabel = rootView.findViewById(R.id.offsetLabel)
        lyricsScroll = rootView.findViewById(R.id.lyricsScroll)
        lyricsList = rootView.findViewById(R.id.lyricsList)
        btnPlayPause = rootView.findViewById(R.id.btnPlayPause)

        rootView.findViewById<View>(R.id.btnCollapse).setOnClickListener { setExpanded(false) }
        rootView.findViewById<View>(R.id.btnClose).setOnClickListener { stopSelf() }
        pillContainer.setOnClickListener { setExpanded(true) }

        pillPlayPause.setOnClickListener { tracker.togglePlayPause() }
        btnPlayPause.setOnClickListener { tracker.togglePlayPause() }
        rootView.findViewById<View>(R.id.btnPrev).setOnClickListener { tracker.previous() }
        rootView.findViewById<View>(R.id.btnNext).setOnClickListener { tracker.next() }

        rootView.findViewById<View>(R.id.btnOffsetMinus).setOnClickListener {
            offsetSec -= 0.5
            updateOffsetLabel()
        }
        rootView.findViewById<View>(R.id.btnOffsetPlus).setOnClickListener {
            offsetSec += 0.5
            updateOffsetLabel()
        }

        attachDragListener(pillContainer)
        attachDragListener(rootView.findViewById(R.id.cardHeader))

        setExpanded(true)
        windowManager.addView(rootView, layoutParams)
    }

    private fun attachDragListener(handle: View) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x
                    initY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    layoutParams.x = initX + dx
                    layoutParams.y = initY + dy
                    windowManager.updateViewLayout(rootView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        pillContainer.visibility = if (expanded) View.GONE else View.VISIBLE
        cardContainer.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun updateOffsetLabel() {
        val sign = if (offsetSec >= 0) "+" else ""
        offsetLabel.text = String.format("%s%.1fs", sign, offsetSec)
    }

    private fun handleTrackSnapshot(snapshot: TrackSnapshot?) {
        if (snapshot == null) {
            currentTrackKey = ""
            cardTitle.text = getString(R.string.waiting_for_saavn)
            cardArtist.text = getString(R.string.play_a_song_hint)
            pillText.text = getString(R.string.waiting_for_saavn)
            showStatusMessage(getString(R.string.play_a_song_hint))
            return
        }

        val iconRes = if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        btnPlayPause.setImageResource(iconRes)
        pillPlayPause.setImageResource(iconRes)

        val key = "${snapshot.title}|${snapshot.artist}"
        if (key == currentTrackKey) return
        currentTrackKey = key

        cardTitle.text = snapshot.title
        cardArtist.text = snapshot.artist.ifBlank { snapshot.packageName }
        pillText.text = snapshot.title

        if (snapshot.artwork != null) {
            cardArt.setImageBitmap(snapshot.artwork)
            pillArt.setImageBitmap(snapshot.artwork)
        } else {
            cardArt.setImageResource(R.drawable.ic_music)
            pillArt.setImageResource(R.drawable.ic_music)
        }

        theme = LyricsLib.extractCoverTheme(snapshot.artwork)
        applyThemeToCard()

        offsetSec = 0.0
        updateOffsetLabel()
        loadLyricsForTrack(snapshot)
    }

    private fun applyThemeToCard() {
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.glowColor, theme.baseColor)
        ).apply {
            cornerRadius = dp(20f)
            setStroke(dp(1f).toInt(), 0x33FFFFFF)
        }
        cardContainer.background = bg
    }

    private fun loadLyricsForTrack(snapshot: TrackSnapshot) {
        lyricsJob?.cancel()
        showStatusMessage(getString(R.string.loading_lyrics))
        lyricsJob = scope.launch {
            val res = LrcLibClient.fetchLyrics(
                title = snapshot.title,
                artist = snapshot.artist,
                durationSec = snapshot.durationMs / 1000.0
            )
            when (res) {
                is LyricsLookup.NotFound -> showStatusMessage(getString(R.string.no_lyrics_found))
                is LyricsLookup.Found -> {
                    if (res.instrumental) {
                        showStatusMessage(getString(R.string.instrumental_track))
                    } else if (res.lines.isNotEmpty()) {
                        renderSyncedLines(res.lines)
                    } else if (!res.plain.isNullOrBlank()) {
                        showStatusMessage(res.plain)
                    } else {
                        showStatusMessage(getString(R.string.no_lyrics_found))
                    }
                }
            }
        }
    }

    private fun showStatusMessage(msg: String) {
        lines = emptyList()
        lineViews = emptyList()
        activeIndex = -1
        lyricsList.removeAllViews()
        val tv = TextView(this).apply {
            text = msg
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt())
        }
        lyricsList.addView(tv)
    }

    private fun renderSyncedLines(parsed: List<LyricLine>) {
        lines = parsed
        activeIndex = -1
        lyricsList.removeAllViews()
        lineViews = parsed.map { line ->
            TextView(this).apply {
                text = if (line.text.isBlank()) "♪" else line.text
                setTextColor(0x88FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(6f).toInt(), dp(7f).toInt(), dp(6f).toInt(), dp(7f).toInt())
                setOnClickListener {
                    tracker.seekToSec(line.timeSec - offsetSec)
                }
            }.also { lyricsList.addView(it) }
        }
    }

    private fun startTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                if (lines.isNotEmpty()) {
                    val t = tracker.currentPositionSec() + offsetSec
                    val idx = LyricsLib.findLineIndex(lines, t)
                    if (idx != activeIndex) {
                        highlightLine(idx)
                    }
                }
                delay(120L)
            }
        }
    }

    private fun highlightLine(newIdx: Int) {
        if (activeIndex in lineViews.indices) {
            lineViews[activeIndex].apply {
                setTextColor(0x88FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.NORMAL)
            }
        }
        activeIndex = newIdx
        if (newIdx in lineViews.indices) {
            val tv = lineViews[newIdx]
            tv.apply {
                setTextColor(theme.accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            if (!isExpanded) {
                pillText.text = tv.text
            } else {
                lyricsScroll.post {
                    val targetY = (tv.top - lyricsScroll.height / 3).coerceAtLeast(0)
                    lyricsScroll.smoothScrollTo(0, targetY)
                }
            }
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Saavn Lyrics Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Floating synced lyrics active")
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
}
