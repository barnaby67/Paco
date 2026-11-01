package com.paco.kindleremote

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggle: Button
    private lateinit var darkOverlay: View
    private val handler = Handler(Looper.getMainLooper())
    private val goDark = Runnable { setDark(true) }
    private var dark = false

    // Pressing the power button while dark turns the screen off; switch it straight back on, undimmed.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!dark) return
            setDark(false)
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "KindleRemote:wake"
            ).acquire(1000)
        }
    }

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        darkOverlay = findViewById(R.id.dark)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        statusText = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        findViewById<Button>(R.id.prev).setOnClickListener { RemoteService.previousPage() }
        findViewById<Button>(R.id.next).setOnClickListener { RemoteService.nextPage() }
        toggle.setOnClickListener {
            if (RemoteService.instance != null) {
                stopService(Intent(this, RemoteService::class.java))
            } else if (hasPermissions()) {
                startForegroundService(Intent(this, RemoteService::class.java))
            } else {
                requestPermissions(permissions, 1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteService.statusListener = { s -> runOnUiThread { show(s) } }
        show(RemoteService.status)
        restartDarkTimer()
    }

    override fun onPause() {
        RemoteService.statusListener = null
        handler.removeCallbacks(goDark)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    // Any touch wakes the screen; the touch that wakes it doesn't press a button.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dark) {
            if (ev.action == MotionEvent.ACTION_DOWN) setDark(false)
            return true
        }
        restartDarkTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun restartDarkTimer() {
        handler.removeCallbacks(goDark)
        handler.postDelayed(goDark, 3000)
    }

    // Black overlay at minimum backlight. The app stays in the foreground, so the volume keys keep working.
    private fun setDark(on: Boolean) {
        dark = on
        darkOverlay.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else {
            window.insetsController?.show(WindowInsets.Type.systemBars())
            restartDarkTimer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        if (hasPermissions()) startForegroundService(Intent(this, RemoteService::class.java))
        else show("Bluetooth permission is required")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (RemoteService.instance == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (event.repeatCount == 0) RemoteService.nextPage()
            KeyEvent.KEYCODE_VOLUME_UP -> if (event.repeatCount == 0) RemoteService.previousPage()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (RemoteService.instance != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun show(s: String) {
        statusText.text = s
        toggle.text = if (RemoteService.instance != null) "Stop" else "Start"
    }

    private fun hasPermissions() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
