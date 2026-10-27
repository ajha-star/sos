package com.example.sosbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SosService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.sosbutton.STOP"
        const val ACTION_TEST = "com.example.sosbutton.TEST"
        const val CHANNEL_ID = "sos_channel"
        const val NOTIF_ID = 1
        const val DEVICE_NAME = "SOS_Button"
        const val CANCEL_WINDOW_MS = 3000L
        const val LOCATION_WAIT_SECONDS = 15L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile
        var status: String = "Stopped"
    }

    @Volatile private var running = false
    @Volatile private var cancelRequested = false
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            val n = buildNotification("Starting...")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            status = "Could not start: " + e.message
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                status = "Stopped"
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                Thread { handleSos(false) }.start()
            }
        }

        if (!running) {
            running = true
            worker = Thread { connectLoop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        closeSocket()
        status = "Stopped"
        super.onDestroy()
    }

    // ---------- Bluetooth ----------

    private fun findDevice(): BluetoothDevice? {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
    }

    private fun connectLoop() {
        while (running) {
            try {
                val device = findDevice()
                if (device == null) {
                    setStatus("Turn on Bluetooth and pair '$DEVICE_NAME'")
                    pause(5000)
                    continue
                }
                setStatus("Connecting to $DEVICE_NAME...")
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                setStatus("Connected. Waiting for SOS")
                readLoop(s)
                setStatus("Disconnected, reconnecting...")
            } catch (e: SecurityException) {
                setStatus("Bluetooth permission missing")
                pause(5000)
            } catch (e: IOException) {
                setStatus("Cannot reach $DEVICE_NAME, retrying...")
            } finally {
                closeSocket()
            }
            pause(3000)
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(s.inputStream))
        while (running) {
            val line = reader.readLine() ?: break
            when (line.trim()) {
                "SOS" -> Thread { handleSos(true) }.start()
                "CANCEL" -> cancelRequested = true
            }
        }
    }

    private fun reply(text: String) {
        try {
            val out = socket?.outputStream ?: return
            out.write((text + "\n").toByteArray())
            out.flush()
        } catch (e: IOException) {
            // ignore: the loop will reconnect
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: IOException) {
        }
        socket = null
    }

    // ---------- SOS handling ----------

    private fun handleSos(withCancelWindow: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            cancelRequested = false
            if (withCancelWindow) {
                setStatus("SOS received. Sending in 3 seconds (press cancel to stop)")
                val end = System.currentTimeMillis() + CANCEL_WINDOW_MS
                while (System.currentTimeMillis() < end) {
                    if (cancelRequested) {
                        setStatus("SOS cancelled")
                        return
                    }
                    pause(100)
                }
            }
            setStatus("Getting location...")
            val loc = getLocation()
            val message = buildMessage(loc)
            val ok = sendToContacts(message)
            if (ok) {
                reply("SENT")
                setStatus("SOS sent at " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) +
                    if (loc == null) " (no location)" else "")
            } else {
                reply("FAIL")
                setStatus("SMS failed. Check numbers, SMS balance, permission")
            }
        } finally {
            busy.set(false)
        }
    }

    private fun buildMessage(loc: Location?): String {
        return if (loc != null) {
            val lat = String.format(Locale.US, "%.6f", loc.latitude)
            val lon = String.format(Locale.US, "%.6f", loc.longitude)
            // Link on its own line, comma encoded as %2C, so SMS apps do not cut it at the comma.
            "HELP! I am in danger. My location:\n" +
                "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon\n" +
                "($lat, $lon, accuracy about ${loc.accuracy.toInt()} m)"
        } else {
            "HELP! I am in danger. My location could not be found. Please call me now."
        }
    }

    private fun sendToContacts(message: String): Boolean {
        val numbers = getSharedPreferences("sos", Context.MODE_PRIVATE)
            .getString("contacts", "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (numbers.isEmpty()) return false

        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        var anySent = false
        for (n in numbers) {
            try {
                sms.sendMultipartTextMessage(n, null, sms.divideMessage(message), null, null)
                anySent = true
            } catch (e: Exception) {
                // try the next number
            }
        }
        return anySent
    }

    // ---------- Location ----------

    private fun getLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        val latch = CountDownLatch(1)

        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val b = best
                if (b == null || l.accuracy < b.accuracy) best = l
                if (l.accuracy <= 50f) latch.countDown()
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }
        }

        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
            latch.await(LOCATION_WAIT_SECONDS, TimeUnit.SECONDS)
            lm.removeUpdates(listener)

            if (best == null) {
                // fall back to the most recent known location
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    val last = lm.getLastKnownLocation(p)
                    val b = best
                    if (last != null && (b == null || last.time > b.time)) best = last
                }
            }
        } catch (e: SecurityException) {
            return null
        } catch (e: InterruptedException) {
            return best
        }
        return best
    }

    // ---------- Helpers ----------

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    private fun setStatus(text: String) {
        status = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SOS protection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS Button is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
}
