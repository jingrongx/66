package com.lvyoubao.app.sensing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lvyoubao.app.MainActivity
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.observation.FatigueBand
import com.lvyoubao.app.data.observation.FatigueEngine
import com.lvyoubao.app.data.observation.FatigueSample
import com.lvyoubao.app.data.observation.ObservationEvent
import com.lvyoubao.app.data.observation.ObservationStore
import com.lvyoubao.app.data.observation.SensingBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 被动感知前台服务（观测版，只记录不干预）：
 * TYPE_STEP_COUNTER 硬件计步 + LocationManager 定位（国产无 GMS 设备也可用），
 * 每分钟算一次疲劳积分，每 5 分钟/状态变化时落样本，越过黄/橙/红线记事件。
 */
class FatigueSensingService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FatigueEngine
    private lateinit var store: ObservationStore

    private var tickJob: Job? = null
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var lastBand = FatigueBand.GREEN
    private var lastScore = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            engine.onLocation(
                ts = location.time,
                lat = location.latitude,
                lon = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                accuracyM = location.accuracy,
            )
            SensingBus.lastLatitude.value = location.latitude
            SensingBus.lastLongitude.value = location.longitude
            SensingBus.lastLocationAt.value = System.currentTimeMillis()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = FatigueEngine()
        store = (application as TravelApp).observationStore
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        engine.start(System.currentTimeMillis())
        lastBand = FatigueBand.GREEN
        lastScore = 0

        registerSensors()
        registerLocations()
        startTicking()

        SensingBus.running.value = true
        SensingBus.startedAt.value = System.currentTimeMillis()
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        sensorManager?.unregisterListener(this)
        locationManager?.let { lm ->
            lm.removeUpdates(locationListener)
        }
        SensingBus.running.value = false
        SensingBus.startedAt.value = null
        scope.launch {
            store.appendEvent(
                ObservationEvent(
                    ts = System.currentTimeMillis(),
                    type = ObservationEvent.SYSTEM,
                    detail = "记录停止",
                    score = lastScore,
                )
            )
        }
        super.onDestroy()
    }

    private fun registerSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun registerLocations() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            scope.launch {
                store.appendEvent(
                    ObservationEvent(
                        ts = System.currentTimeMillis(),
                        type = ObservationEvent.SYSTEM,
                        detail = "缺少定位权限：距离/爬升维度不记录，仅计步",
                    )
                )
            }
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 20_000L, 8f, locationListener)
        } catch (_: IllegalArgumentException) {
            // 设备无 GPS provider（模拟器等）
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 15f, locationListener)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick++
                val now = System.currentTimeMillis()
                val snapshot = engine.onTick(now)
                lastScore = snapshot.score
                SensingBus.band.value = snapshot.band

                if (snapshot.band != lastBand) {
                    if (snapshot.band.ordinal > lastBand.ordinal) {
                        store.appendEvent(
                            ObservationEvent(
                                ts = now,
                                type = ObservationEvent.THRESHOLD,
                                detail = "疲劳积分越过${lastBand.label}→${snapshot.band.label}（${lastScore}分）",
                                score = snapshot.score,
                            )
                        )
                    }
                    lastBand = snapshot.band
                }

                // 每 5 分钟，或积分发生明显变化时落样本
                if (tick % 5 == 1 || snapshot.band != previousSampleBand) {
                    previousSampleBand = snapshot.band
                    store.appendSample(
                        FatigueSample(
                            ts = now,
                            steps = snapshot.steps,
                            walkingMinutes = snapshot.continuousWalkingMinutes,
                            distanceMeters = snapshot.distanceMeters,
                            climbMeters = snapshot.climbMeters,
                            score = snapshot.score,
                            band = snapshot.band.name,
                        )
                    )
                }
            }
        }
    }

    private var previousSampleBand = FatigueBand.GREEN

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
            engine.onSteps(event.values[0].toInt())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "行程感知",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "旅游宝在后台安静记录行程状态（观测版）" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.lvyoubao.app.R.drawable.ic_launcher_foreground)
            .setContentTitle("旅游宝记录中")
            .setContentText("观测版：只记录不干预，状态点变橙/红说明该考虑休息了")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sensing"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FatigueSensingService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FatigueSensingService::class.java))
        }
    }
}
