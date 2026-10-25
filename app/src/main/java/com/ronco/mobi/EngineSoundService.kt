package com.ronco.mobi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class EngineSoundService : Service() {

    companion object {
        const val ACTION_STOP = "com.ronco.mobi.STOP"
        private const val CHANNEL_ID = "ronco_mobi"
        private const val NOTIF_ID = 1

        /** Liga o motor (usado pelo celular e pelo Android Auto). Exige a permissao de localizacao ja concedida. */
        fun start(ctx: Context) {
            ctx.applicationContext.startForegroundService(Intent(ctx, EngineSoundService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.applicationContext.stopService(Intent(ctx, EngineSoundService::class.java))
        }
    }

    private val S = SoundState
    private val handler = Handler(Looper.getMainLooper())
    private val model = EngineModel()
    private var synth: EngineSynth? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastGpsAt = 0L
    private var tickCount = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            var ms = if (loc.hasSpeed()) loc.speed else 0f
            if (ms < 0.8f) ms = 0f            // filtra o tremor do GPS parado (< ~3 km/h)
            S.gpsFix = true
            if (loc.hasAltitude() && S.testKmh < 0f) model.onAltitude(loc.altitude, if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else 15f)
            lastGpsAt = SystemClock.elapsedRealtime()
            if (S.testKmh < 0f) model.onSpeed(ms)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { S.gpsFix = false }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            tickCount++
            val test = S.testKmh
            if (test >= 0f) {
                if (tickCount % 5 == 0) model.onSpeed(test / 3.6f)
            } else if (lastGpsAt > 0 && now - lastGpsAt > 5000) {
                if (tickCount % 5 == 0) model.onSpeed(0f)
                S.gpsFix = false
            }
            model.tick(now)
            if (tickCount % 1200 == 0) Profiles.saveDriver()
            handler.postDelayed(this, 50)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (S.running) return START_STICKY

        Profiles.ensureLoaded(this)
        startAsForeground()
        S.running = true

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ronco:engine").apply { acquire() }

        synth = EngineSynth().also { it.start() }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper())
            } catch (_: SecurityException) { }
        }
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        locationManager?.removeUpdates(locationListener)
        synth?.stop()
        Profiles.saveDriver()
        wakeLock?.let { if (it.isHeld) it.release() }
        S.running = false
        S.gpsFix = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ronco Mobi", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineSoundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Parar", stop
        ).build()
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ronco Mobi ativo")
            .setContentText("Simulando o som do motor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).addAction(stopAction).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (e: SecurityException) {
                // iniciado pelo carro sem o app em primeiro plano: sobe so como midia (o som toca; o GPS pode demorar)
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else startForeground(NOTIF_ID, notif)
    }
}
