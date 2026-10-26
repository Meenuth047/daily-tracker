package com.mypcosjourney.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CapacitorPlugin(
    name = "StepCounter",
    permissions = [
        Permission(
            alias = "activityRecognition",
            strings = [Manifest.permission.ACTIVITY_RECOGNITION]
        )
    ]
)
class StepCounterPlugin : Plugin(), SensorEventListener {

    companion object {
        private const val PREFS_NAME = "pcos_step_counter_prefs"
        private const val PREF_LAST_SEEN_RAW = "last_seen_raw"
        private const val PREF_TODAY_DATE = "today_date"
        private const val PREF_BASELINE_RAW = "baseline_raw"
        private const val PREF_REBOOT_OFFSET = "reboot_offset"

        // Active Session
        private const val PREF_SESSION_ACTIVE = "session_active"
        private const val PREF_SESSION_TYPE = "session_type" // "lunch" or "evening"
        private const val PREF_SESSION_START_RAW = "session_start_raw"
        private const val PREF_SESSION_PAUSED = "session_paused"
        private const val PREF_SESSION_PAUSE_START_RAW = "session_pause_start_raw"
        private const val PREF_SESSION_PAUSED_DELTA = "session_paused_delta"
        private const val PREF_SESSION_START_TIME = "session_start_time"
    }

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var isListenerRegistered = false

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun load() {
        super.load()
        initSensor()
    }

    private fun initSensor() {
        if (sensorManager == null) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        }

        if (stepSensor != null && !isListenerRegistered) {
            val registered = sensorManager?.registerListener(
                this,
                stepSensor,
                SensorManager.SENSOR_DELAY_UI
            ) ?: false
            isListenerRegistered = registered
        }
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    // --- Capacitor Methods ---

    @PluginMethod
    fun checkAvailability(call: PluginCall) {
        val ret = JSObject()
        initSensor()

        val hasSensor = stepSensor != null
        ret.put("available", hasSensor)

        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getPermissionState("activityRecognition") == com.getcapacitor.PermissionState.GRANTED
        } else {
            true
        }
        ret.put("hasPermission", hasPerm)

        val message = when {
            !hasSensor -> "Step counter hardware sensor (Sensor.TYPE_STEP_COUNTER) not present on this device."
            !hasPerm -> "Physical Activity permission is required to track walking steps."
            else -> "Step counter hardware sensor is active and ready."
        }
        ret.put("message", message)
        ret.put("sensorType", if (hasSensor) "TYPE_STEP_COUNTER" else null)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestStepPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (getPermissionState("activityRecognition") == com.getcapacitor.PermissionState.GRANTED) {
                val ret = JSObject()
                ret.put("granted", true)
                ret.put("message", "Permission already granted")
                call.resolve(ret)
            } else {
                requestPermissionForAlias("activityRecognition", call, "stepPermissionCallback")
            }
        } else {
            val ret = JSObject()
            ret.put("granted", true)
            ret.put("message", "Permission granted automatically (API < 29)")
            call.resolve(ret)
        }
    }

    @PermissionCallback
    fun stepPermissionCallback(call: PluginCall) {
        val granted = getPermissionState("activityRecognition") == com.getcapacitor.PermissionState.GRANTED
        val ret = JSObject()
        ret.put("granted", granted)
        ret.put("message", if (granted) "Physical Activity permission granted" else "Physical Activity permission denied")
        if (granted) {
            initSensor()
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getDailySteps(call: PluginCall) {
        val ret = JSObject()
        initSensor()

        if (stepSensor == null) {
            ret.put("steps", null)
            ret.put("available", false)
            ret.put("source", "sensor_step_counter")
            ret.put("message", "Step counter sensor not present on this device")
            call.resolve(ret)
            return
        }

        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getPermissionState("activityRecognition") == com.getcapacitor.PermissionState.GRANTED
        } else {
            true
        }

        if (!hasPerm) {
            ret.put("steps", null)
            ret.put("available", true)
            ret.put("hasPermission", false)
            ret.put("source", "sensor_step_counter")
            ret.put("message", "Physical Activity permission not granted")
            call.resolve(ret)
            return
        }

        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, -1f)
        val today = getTodayDateString()
        val savedDate = prefs.getString(PREF_TODAY_DATE, null)

        val steps = if (lastSeenRaw < 0) {
            // First time reading, no events arrived yet
            0
        } else {
            computeTodaySteps(lastSeenRaw)
        }

        ret.put("steps", steps)
        ret.put("available", true)
        ret.put("hasPermission", true)
        ret.put("source", "sensor_step_counter")
        ret.put("date", today)
        ret.put("timestamp", System.currentTimeMillis())
        call.resolve(ret)
    }

    @PluginMethod
    fun startSession(call: PluginCall) {
        val ret = JSObject()
        initSensor()

        val sessionType = call.getString("sessionType") ?: "walk"
        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, 0f)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putBoolean(PREF_SESSION_ACTIVE, true)
            .putString(PREF_SESSION_TYPE, sessionType)
            .putFloat(PREF_SESSION_START_RAW, lastSeenRaw)
            .putBoolean(PREF_SESSION_PAUSED, false)
            .putFloat(PREF_SESSION_PAUSE_START_RAW, 0f)
            .putFloat(PREF_SESSION_PAUSED_DELTA, 0f)
            .putLong(PREF_SESSION_START_TIME, now)
            .apply()

        ret.put("status", "started")
        ret.put("sessionType", sessionType)
        ret.put("startStepsRaw", lastSeenRaw)
        ret.put("startTime", now)
        call.resolve(ret)
    }

    @PluginMethod
    fun pauseSession(call: PluginCall) {
        val ret = JSObject()
        val isActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false)
        if (!isActive) {
            ret.put("status", "no_active_session")
            call.resolve(ret)
            return
        }

        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, 0f)
        prefs.edit()
            .putBoolean(PREF_SESSION_PAUSED, true)
            .putFloat(PREF_SESSION_PAUSE_START_RAW, lastSeenRaw)
            .apply()

        ret.put("status", "paused")
        call.resolve(ret)
    }

    @PluginMethod
    fun resumeSession(call: PluginCall) {
        val ret = JSObject()
        val isActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false)
        val isPaused = prefs.getBoolean(PREF_SESSION_PAUSED, false)

        if (!isActive || !isPaused) {
            ret.put("status", "not_paused")
            call.resolve(ret)
            return
        }

        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, 0f)
        val pauseStartRaw = prefs.getFloat(PREF_SESSION_PAUSE_START_RAW, lastSeenRaw)
        val deltaPaused = if (lastSeenRaw >= pauseStartRaw) lastSeenRaw - pauseStartRaw else 0f
        val currentAccumulatedPaused = prefs.getFloat(PREF_SESSION_PAUSED_DELTA, 0f)

        prefs.edit()
            .putBoolean(PREF_SESSION_PAUSED, false)
            .putFloat(PREF_SESSION_PAUSED_DELTA, currentAccumulatedPaused + deltaPaused)
            .apply()

        ret.put("status", "resumed")
        call.resolve(ret)
    }

    @PluginMethod
    fun stopSession(call: PluginCall) {
        val ret = JSObject()
        val isActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false)
        val sessionType = prefs.getString(PREF_SESSION_TYPE, "walk") ?: "walk"
        val startRaw = prefs.getFloat(PREF_SESSION_START_RAW, 0f)
        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, startRaw)
        val startTime = prefs.getLong(PREF_SESSION_START_TIME, System.currentTimeMillis())
        val endTime = System.currentTimeMillis()
        val durationSeconds = ((endTime - startTime) / 1000).toInt()

        var pausedDelta = prefs.getFloat(PREF_SESSION_PAUSED_DELTA, 0f)
        if (prefs.getBoolean(PREF_SESSION_PAUSED, false)) {
            val pauseStartRaw = prefs.getFloat(PREF_SESSION_PAUSE_START_RAW, lastSeenRaw)
            if (lastSeenRaw >= pauseStartRaw) {
                pausedDelta += (lastSeenRaw - pauseStartRaw)
            }
        }

        val rawDelta = if (lastSeenRaw >= startRaw) lastSeenRaw - startRaw else lastSeenRaw
        val sessionSteps = maxOf(0, (rawDelta - pausedDelta).toInt())

        // Save session history separated by type and date
        val today = getTodayDateString()
        prefs.edit()
            .putBoolean(PREF_SESSION_ACTIVE, false)
            .putBoolean(PREF_SESSION_PAUSED, false)
            .putInt("session_${sessionType}_${today}_steps", sessionSteps)
            .putInt("session_${sessionType}_${today}_duration", durationSeconds)
            .apply()

        ret.put("status", "stopped")
        ret.put("sessionType", sessionType)
        ret.put("sessionSteps", if (stepSensor != null) sessionSteps else null)
        ret.put("startTime", startTime)
        ret.put("endTime", endTime)
        ret.put("durationSeconds", durationSeconds)
        ret.put("source", if (stepSensor != null) "hardware_step_counter" else "not_available")
        ret.put("timestamp", endTime)
        call.resolve(ret)
    }

    @PluginMethod
    fun getSessionStatus(call: PluginCall) {
        val ret = JSObject()
        val isActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false)
        val sessionType = prefs.getString(PREF_SESSION_TYPE, "walk") ?: "walk"
        val isPaused = prefs.getBoolean(PREF_SESSION_PAUSED, false)
        val startTime = prefs.getLong(PREF_SESSION_START_TIME, 0L)
        val startRaw = prefs.getFloat(PREF_SESSION_START_RAW, 0f)
        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, startRaw)

        val today = getTodayDateString()
        val lunchSteps = prefs.getInt("session_lunch_${today}_steps", -1)
        val eveningSteps = prefs.getInt("session_evening_${today}_steps", -1)

        ret.put("isActive", isActive)
        ret.put("isPaused", isPaused)
        ret.put("sessionType", if (isActive) sessionType else null)
        ret.put("startTime", if (isActive) startTime else null)

        if (isActive && stepSensor != null) {
            var pausedDelta = prefs.getFloat(PREF_SESSION_PAUSED_DELTA, 0f)
            if (isPaused) {
                val pauseStartRaw = prefs.getFloat(PREF_SESSION_PAUSE_START_RAW, lastSeenRaw)
                if (lastSeenRaw >= pauseStartRaw) {
                    pausedDelta += (lastSeenRaw - pauseStartRaw)
                }
            }
            val rawDelta = if (lastSeenRaw >= startRaw) lastSeenRaw - startRaw else lastSeenRaw
            val liveSteps = maxOf(0, (rawDelta - pausedDelta).toInt())
            ret.put("liveSessionSteps", liveSteps)
        } else {
            ret.put("liveSessionSteps", null)
        }

        ret.put("todayLunchSteps", if (lunchSteps >= 0) lunchSteps else null)
        ret.put("todayEveningSteps", if (eveningSteps >= 0) eveningSteps else null)
        ret.put("source", if (stepSensor != null) "hardware_step_counter" else "not_available")
        call.resolve(ret)
    }

    // --- SensorEventListener Implementation ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return

        val rawSteps = event.values[0]
        val today = getTodayDateString()
        val savedDate = prefs.getString(PREF_TODAY_DATE, null)
        val lastSeenRaw = prefs.getFloat(PREF_LAST_SEEN_RAW, -1f)

        val editor = prefs.edit()

        // 1. Check for Reboot: counter reset
        if (lastSeenRaw >= 0 && rawSteps < lastSeenRaw) {
            val baseline = prefs.getFloat(PREF_BASELINE_RAW, 0f)
            val stepsBeforeReboot = maxOf(0f, lastSeenRaw - baseline)
            val currentRebootOffset = prefs.getFloat(PREF_REBOOT_OFFSET, 0f)
            editor.putFloat(PREF_REBOOT_OFFSET, currentRebootOffset + stepsBeforeReboot)
            editor.putFloat(PREF_BASELINE_RAW, 0f)
        }

        // 2. Check for Midnight Rollover
        if (savedDate == null) {
            editor.putString(PREF_TODAY_DATE, today)
            editor.putFloat(PREF_BASELINE_RAW, rawSteps)
            editor.putFloat(PREF_REBOOT_OFFSET, 0f)
        } else if (savedDate != today) {
            // Archived previous day's steps
            val prevSteps = computeTodaySteps(if (lastSeenRaw >= 0) lastSeenRaw else rawSteps)
            editor.putInt("history_${savedDate}_daily_steps", prevSteps)

            // Reset for today
            editor.putString(PREF_TODAY_DATE, today)
            editor.putFloat(PREF_BASELINE_RAW, rawSteps)
            editor.putFloat(PREF_REBOOT_OFFSET, 0f)
        }

        editor.putFloat(PREF_LAST_SEEN_RAW, rawSteps)
        editor.apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun computeTodaySteps(currentRaw: Float): Int {
        val baseline = prefs.getFloat(PREF_BASELINE_RAW, currentRaw)
        val rebootOffset = prefs.getFloat(PREF_REBOOT_OFFSET, 0f)
        val rawDelta = if (currentRaw >= baseline) currentRaw - baseline else currentRaw
        return maxOf(0, (rawDelta + rebootOffset).toInt())
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        if (isListenerRegistered) {
            sensorManager?.unregisterListener(this)
            isListenerRegistered = false
        }
    }
}
