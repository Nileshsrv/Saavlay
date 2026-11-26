package com.saavn.lyricsoverlay

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var statusNotif: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var switchSaavnOnly: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusNotif = findViewById(R.id.statusNotif)
        statusOverlay = findViewById(R.id.statusOverlay)
        switchSaavnOnly = findViewById(R.id.switchSaavnOnly)

        findViewById<Button>(R.id.btnNotifPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (!hasNotificationAccess()) {
                Toast.makeText(this, "Grant Notification Access first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant Display Over Other Apps first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_JIOSAAVN_ONLY, switchSaavnOnly.isChecked)
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
    }

    override fun onResume() {
        super.onResume()
        val notifOk = hasNotificationAccess()
        val overlayOk = Settings.canDrawOverlays(this)
        statusNotif.text = if (notifOk) "Notification Access: Granted" else "Notification Access: Required"
        statusNotif.setTextColor(if (notifOk) 0xFF6CE5A8.toInt() else 0xFFFF8A80.toInt())
        statusOverlay.text = if (overlayOk) "Overlay Permission: Granted" else "Overlay Permission: Required"
        statusOverlay.setTextColor(if (overlayOk) 0xFF6CE5A8.toInt() else 0xFFFF8A80.toInt())
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, MediaListenerService::class.java).flattenToString()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}
