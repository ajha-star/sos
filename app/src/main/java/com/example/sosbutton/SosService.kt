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
        const val ACTION_SAFE = "com.example.sosbutton.SAFE"
        const val CHANNEL_ID = "sos_channel"
        const val NOTIF_ID = 1
        const val DEVICE_NAME = "SOS_Button"

        // How often a new location is sent while the alert is active.
        // 15 seconds for the demo. Change to 120000L (2 minutes) for the final version.
        const val UPDATE_INTERVAL_MS = 15000L

        // The alert stops by itself after this long, so a forgotten alert does not text forever.
        const val MAX_ALERT_MS = 60L * 60L * 1000L

        const val LOCATION_WAIT_MS = 15000L
        const val RECONNECT_DELAY_MS = 3000L
        const val SAFE_ATTEMPTS = 3
        const val SAFE_RETRY_MS = 5000L

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile
        var status: String = "Stopped"
    }

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var anySent = false
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private val alertActive = AtomicBoolean(false)

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

        val action = intent?.action

        if (action == ACTION_STOP) {
            status = "Stopped"
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            worker = Thread { connectLoop() }.also { it.start() }
        }

        if (action == ACTION_TEST) {
            startAlert()
        } else if (action == ACTION_SAFE) {
            requestSafe()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        closeSocket()
        status = "Stopped"
        super.onDestroy()
    }

    // ---------- Bluetooth (reconnects by itself whenever the link drops) ----------

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
                setStatus(idleText("Connecting to $DEVICE_NAME..."))
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                setStatus(idleText("Connected. Waiting for SOS"))
                readLoop(s)
                setStatus(idleText("Disconnected, reconnecting..."))
            } catch (e: SecurityException) {
                setStatus("Bluetooth permission missing")
                pause(5000)
            } catch (e: IOException) {
                setStatus(idleText("Cannot reach $DEVICE_NAME, retrying..."))
            } catch (e: Exception) {
                setStatus(idleText("Bluetooth problem, retrying..."))
            } finally {
                closeSocket()
            }
            pause(RECONNECT_DELAY_MS)
        }
    }

    // While an alert is running the alert keeps going even if Bluetooth drops,
    // so the status line keeps showing the alert and only mentions the link.
    private fun idleText(text: String): String =
        if (alertActive.get()) "Alert active (Bluetooth: $text)" else text

    private fun readLoop(s: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(s.inputStream))
        while (running) {
            val line = reader.readLine() ?: break
            when (line.trim()) {
                "SOS" -> startAlert()
                "SAFE", "CANCEL" -> requestSafe()
            }
        }
    }

    @Synchronized
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

    // ---------- Alert: send now, then a new location every UPDATE_INTERVAL_MS until SAFE ----------

    private fun startAlert() {
        if (!alertActive.compareAndSet(false, true)) {
            // An alert is already running (for example SOS tapped again after a reconnect).
            // Ignore it, but answer so the red LED on the ESP32 does not stay stuck on.
            reply("SENT")
            return
        }
        stopRequested = false
        anySent = false
        Thread { runAlert() }.start()
    }

    private fun requestSafe() {
        if (!alertActive.get()) return  // nothing is running, so nothing happens
        stopRequested = true
        setStatus("SAFE pressed. Stopping the alert...")
    }

    private fun runAlert() {
        val startTime = System.currentTimeMillis()
        var round = 0
        try {
            while (running && !stopRequested &&
                System.currentTimeMillis() - startTime < MAX_ALERT_MS
            ) {
                round++
                reply("SENDING")
                setStatus(if (round == 1) "SOS: getting location..." else "Update ${round - 1}: getting location...")
                val loc = getLocation()
                if (stopRequested || !running) break

                val ok = sendToContacts(buildMessage(loc, round))
                if (ok) {
                    anySent = true
                    reply("SENT")
                    setStatus(
                        "Alert active. Last SMS sent at " + clock() +
                            (if (loc == null) " (no location)" else "") +
                            ". Press SAFE to stop"
                    )
                } else {
                    reply("FAIL")
                    setStatus("SMS failed (check numbers, SMS balance, permission). Trying again next round")
                }
                waitOrStop(UPDATE_INTERVAL_MS)
            }

            if (!running) return

            if (stopRequested) {
                if (anySent) {
                    sendSafe()
                } else {
                    reply("ENDED")
                    setStatus("Alert cancelled before anything was sent. Waiting for SOS")
                }
            } else {
                reply("ENDED")
                setStatus("Alert ended automatically after ${MAX_ALERT_MS / 60000} minutes")
            }
        } finally {
            alertActive.set(false)
        }
    }

    private fun sendSafe() {
        reply("SAFE_SENDING")
        setStatus("Sending the 'I am safe' message...")
        var ok = false
        var attempt = 0
        while (attempt < SAFE_ATTEMPTS && !ok && running) {
            attempt++
            ok = sendToContacts("I am safe now. The alert has been stopped.")
            if (!ok && attempt < SAFE_ATTEMPTS) pause(SAFE_RETRY_MS)
        }
        if (ok) {
            reply("SAFE_SENT")
            setStatus("'I am safe' sent at " + clock() + ". Alert stopped. Waiting for SOS")
        } else {
            reply("FAIL")
            setStatus("'I am safe' message FAILED to send. Check numbers, SMS balance, permission")
            pause(3000)
            reply("ENDED")  // lets the ESP32 accept a new SOS
        }
    }

    private fun waitOrStop(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (running && !stopRequested && System.currentTimeMillis() < end) {
            pause(200)
        }
    }

    private fun clock(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun buildMessage(loc: Location?, round: Int): String {
        val header = if (round == 1) "HELP! I am in danger." else "UPDATE ${round - 1}: still in danger."
        return if (loc != null) {
            val lat = String.format(Locale.US, "%.6f", loc.latitude)
            val lon = String.format(Locale.US, "%.6f", loc.longitude)
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            // The link is on its own line and the comma is written as %2C,
            // so it does not break on the receiver's phone.
            "$header Location:\n" +
                "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon\n" +
                "($lat, $lon, ${loc.accuracy.toInt()} m, $time)"
        } else {
            "$header Location not found. Please call me now."
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

        var anyOk = false
        for (n in numbers) {
            try {
                sms.sendMultipartTextMessage(n, null, sms.divideMessage(message), null, null)
                anyOk = true
            } catch (e: Exception) {
                // try the next number
            }
        }
        return anyOk
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
            val end = System.currentTimeMillis() + LOCATION_WAIT_MS
            while (System.currentTimeMillis() < end && running && !stopRequested) {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            }
        } catch (e: SecurityException) {
            return null
        } catch (e: InterruptedException) {
            // fall through and use what we have
        } finally {
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
            }
        }

        if (best == null) {
            // fall back to the most recent known location
            try {
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    val last = lm.getLastKnownLocation(p)
                    val b = best
                    if (last != null && (b == null || last.time > b.time)) best = last
                }
            } catch (e: SecurityException) {
                return null
            }
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
