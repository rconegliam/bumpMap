package com.rconegliam.bumpmap.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rconegliam.bumpmap.BuildConfig
import com.rconegliam.bumpmap.MainActivity
import com.rconegliam.bumpmap.R
import java.io.BufferedWriter
import java.io.File

/**
 * Foreground service that writes accelerometer, gyroscope, gravity and GPS readings to a CSV file
 * while the user drives. All sensor/location callbacks and file writes run on one worker thread.
 */
class RecorderService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val ACTION_START = "com.rconegliam.bumpmap.recorder.START"
        private const val ACTION_STOP = "com.rconegliam.bumpmap.recorder.STOP"
        private const val ACTION_MARK = "com.rconegliam.bumpmap.recorder.MARK"
        private const val EXTRA_MARK = "mark"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val SENSOR_PERIOD_US = 10_000 // 100 Hz
        private const val GPS_INTERVAL_MS = 1_000L
        private const val PUBLISH_INTERVAL_MS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val GRAVITY_LOW_PASS = 0.9f

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(intent(context, ACTION_STOP))
        }

        fun mark(context: Context, type: MarkType) {
            context.startService(intent(context, ACTION_MARK).putExtra(EXTRA_MARK, type.code))
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        private fun intent(context: Context, action: String) =
            Intent(context, RecorderService::class.java).setAction(action)
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var recording = false

    // Accessed only on the worker thread.
    private var writer: BufferedWriter? = null
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var hasGravitySensor = false
    private val shake = RollingRms(windowNs = 1_000_000_000L)
    private var sensorSamples = 0L
    private var locationFixes = 0L
    private var speedKmh: Float? = null
    private var accuracyM: Float? = null
    private var marks = 0

    private val publisher = object : Runnable {
        override fun run() {
            publishStatus()
            worker.postDelayed(this, PUBLISH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        workerThread = HandlerThread("bumpmap-recorder").apply { start() }
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_MARK -> {
                val type = MarkType.entries.firstOrNull { it.code == intent.getStringExtra(EXTRA_MARK) }
                if (!recording) {
                    stopSelf()
                } else if (type != null) {
                    val tNs = SystemClock.elapsedRealtimeNanos()
                    worker.post {
                        writer?.appendLine(CsvRows.mark(tNs, type))
                        marks++
                        publishStatus()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (recording) return
        if (!hasLocationPermission(this)) {
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        recording = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BumpMap:recording")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val file = RecordingStore.newFile(this)
        val startedAtMs = System.currentTimeMillis()
        val startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()

        worker.post {
            resetCounters(gravitySensor != null)
            writer = file.bufferedWriter().apply {
                writeHeader(this, startedAtMs, startedAtElapsedNs, accelerometer, gyroscope, gravitySensor)
            }
        }
        listOfNotNull(accelerometer, gyroscope, gravitySensor).forEach { sensor ->
            sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US, worker)
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GPS_INTERVAL_MS,
            0f,
            this,
            workerThread.looper,
        )
        RecorderState.update {
            RecorderStatus(
                isRecording = true,
                fileName = file.name,
                startedAtMs = startedAtMs,
                finishedCount = it.finishedCount,
            )
        }
        worker.postDelayed(publisher, PUBLISH_INTERVAL_MS)
    }

    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        stopListening()
        worker.post {
            closeWriter()
            RecorderState.update { RecorderStatus(finishedCount = it.finishedCount + 1) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopListening() {
        recording = false
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        worker.removeCallbacks(publisher)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        if (recording) {
            stopListening()
            worker.post {
                closeWriter()
                RecorderState.update { RecorderStatus(finishedCount = it.finishedCount + 1) }
            }
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val out = writer ?: return
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                out.appendLine(CsvRows.vector('a', event.timestamp, v[0], v[1], v[2]))
                sensorSamples++
                if (!hasGravitySensor) {
                    // Phones without a gravity sensor: estimate gravity with a low-pass filter.
                    for (i in 0..2) {
                        gravity[i] = if (hasGravity) GRAVITY_LOW_PASS * gravity[i] + (1 - GRAVITY_LOW_PASS) * v[i] else v[i]
                    }
                    hasGravity = true
                }
                if (hasGravity) {
                    shake.add(
                        event.timestamp,
                        SignalMath.verticalLinear(v[0], v[1], v[2], gravity[0], gravity[1], gravity[2]),
                    )
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                out.appendLine(CsvRows.vector('g', event.timestamp, v[0], v[1], v[2]))
                sensorSamples++
            }
            Sensor.TYPE_GRAVITY -> {
                out.appendLine(CsvRows.vector('v', event.timestamp, v[0], v[1], v[2]))
                v.copyInto(gravity, endIndex = 3)
                hasGravity = true
                sensorSamples++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val out = writer ?: return
        out.appendLine(
            CsvRows.location(
                tNs = location.elapsedRealtimeNanos,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else Double.NaN,
                speedMps = if (location.hasSpeed()) location.speed else Float.NaN,
                bearing = if (location.hasBearing()) location.bearing else Float.NaN,
                accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN,
            ),
        )
        locationFixes++
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
        accuracyM = if (location.hasAccuracy()) location.accuracy else null
    }

    // Overridden explicitly: these have no default implementation before Android 11.
    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun resetCounters(gravitySensorPresent: Boolean) {
        hasGravitySensor = gravitySensorPresent
        hasGravity = false
        sensorSamples = 0
        locationFixes = 0
        speedKmh = null
        accuracyM = null
        marks = 0
    }

    private fun publishStatus() {
        if (writer == null) return
        RecorderState.update {
            it.copy(
                sensorSamples = sensorSamples,
                locationFixes = locationFixes,
                speedKmh = speedKmh,
                accuracyM = accuracyM,
                shakeRms = shake.value,
                marks = marks,
            )
        }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun writeHeader(
        out: BufferedWriter,
        startedAtMs: Long,
        startedAtElapsedNs: Long,
        accelerometer: Sensor?,
        gyroscope: Sensor?,
        gravitySensor: Sensor?,
    ) {
        out.appendLine(CsvRows.meta("format", 1))
        out.appendLine(CsvRows.meta("app_version", BuildConfig.VERSION_NAME))
        out.appendLine(CsvRows.meta("device", "${Build.MANUFACTURER} ${Build.MODEL}"))
        out.appendLine(CsvRows.meta("android_sdk", Build.VERSION.SDK_INT))
        out.appendLine(CsvRows.meta("started_at_epoch_ms", startedAtMs))
        out.appendLine(CsvRows.meta("started_at_elapsed_ns", startedAtElapsedNs))
        out.appendLine(CsvRows.meta("accelerometer", accelerometer?.name))
        out.appendLine(CsvRows.meta("gyroscope", gyroscope?.name))
        out.appendLine(CsvRows.meta("gravity", gravitySensor?.name))
        out.appendLine(CsvRows.HEADER)
    }

    private fun buildNotification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_name))
                .build(),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            intent(this, ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

private fun File.bufferedWriter(): BufferedWriter = outputStream().bufferedWriter(Charsets.UTF_8)
