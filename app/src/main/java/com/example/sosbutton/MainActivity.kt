package com.example.sosbutton

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var contactsInput: EditText
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            statusText.text = "Status: " + SosService.status
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun missingPermissions(): List<String> =
        neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun normalise(raw: String): String {
        val d = raw.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d
            d.length == 10 -> "+91$d"
            d.startsWith("91") && d.length == 12 -> "+$d"
            d.startsWith("0") && d.length == 11 -> "+91" + d.substring(1)
            else -> d
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "SOS Button"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)

        root.addView(TextView(this).apply {
            text = "Pair the ESP32 (named SOS_Button) in your phone's Bluetooth settings first."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        contactsInput = EditText(this).apply {
            hint = "Family numbers, separated by commas (e.g. 9876543210, 9123456780)"
            setText(getSharedPreferences("sos", Context.MODE_PRIVATE).getString("contacts", ""))
        }
        root.addView(contactsInput)

        fun addButton(label: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        addButton("1. Allow permissions") { askPermissions() }
        addButton("2. Save numbers and START protection") { startProtection(false) }
        addButton("Send TEST SOS now") { startProtection(true) }
        addButton("Stop protection") {
            startService(Intent(this, SosService::class.java).setAction(SosService.ACTION_STOP))
        }
        addButton("Battery settings (set this app to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(statusText)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun askPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions already allowed", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun startProtection(sendTest: Boolean) {
        val numbers = contactsInput.text.toString()
            .split(",", ";", "\n")
            .map { normalise(it.trim()) }
            .filter { it.length >= 8 }

        if (numbers.isEmpty()) {
            Toast.makeText(this, "Enter at least one phone number", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("sos", Context.MODE_PRIVATE)
            .edit().putString("contacts", numbers.joinToString(",")).apply()
        contactsInput.setText(numbers.joinToString(", "))

        if (missingPermissions().isNotEmpty()) {
            Toast.makeText(this, "Tap '1. Allow permissions' first", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, SosService::class.java)
        if (sendTest) intent.action = SosService.ACTION_TEST
        startForegroundService(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }
}
