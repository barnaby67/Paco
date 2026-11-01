package com.paco.kindleremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggle: Button

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
    }

    override fun onPause() {
        RemoteService.statusListener = null
        super.onPause()
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
