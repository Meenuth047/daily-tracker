package com.mypcosjourney.app

import android.content.Intent
import android.os.Build
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@CapacitorPlugin(name = "HealthConnect")
class HealthConnectPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.Main)

    private val stepsReadPermission = HealthPermission.getReadPermission(StepsRecord::class)

    @PluginMethod
    fun checkAvailability(call: PluginCall) {
        val ret = JSObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ret.put("available", false)
            ret.put("status", "UNAVAILABLE")
            ret.put("message", "Health Connect requires Android 8.0 or higher")
            call.resolve(ret)
            return
        }

        try {
            val status = HealthConnectClient.getSdkStatus(context)
            when (status) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    ret.put("available", true)
                    ret.put("status", "AVAILABLE")
                    ret.put("message", "Health Connect is supported and available on this device.")
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    ret.put("available", false)
                    ret.put("status", "PROVIDER_UPDATE_REQUIRED")
                    ret.put("message", "Health Connect requires update or installation from Google Play.")
                }
                else -> {
                    ret.put("available", false)
                    ret.put("status", "UNAVAILABLE")
                    ret.put("message", "Health Connect is not supported on this device.")
                }
            }
        } catch (e: Exception) {
            ret.put("available", false)
            ret.put("status", "ERROR")
            ret.put("message", e.message ?: "Failed to check Health Connect status")
        }

        call.resolve(ret)
    }

    @PluginMethod
    fun checkHealthPermissions(call: PluginCall) {
        val ret = JSObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            ret.put("hasPermission", false)
            ret.put("status", "UNAVAILABLE")
            ret.put("message", "Health Connect unavailable")
            call.resolve(ret)
            return
        }

        val client = HealthConnectClient.getOrCreate(context)
        scope.launch(Dispatchers.IO) {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                val hasPerm = granted.contains(stepsReadPermission)
                ret.put("hasPermission", hasPerm)
                ret.put("status", if (hasPerm) "GRANTED" else "NOT_GRANTED")
                ret.put("message", if (hasPerm) "Steps permission granted" else "Steps permission not granted")
                call.resolve(ret)
            } catch (e: Exception) {
                ret.put("hasPermission", false)
                ret.put("error", e.message)
                call.resolve(ret)
            }
        }
    }

    @PluginMethod
    fun requestHealthPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            val ret = JSObject()
            ret.put("granted", false)
            ret.put("message", "Health Connect not available on this device")
            call.resolve(ret)
            return
        }

        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(activity, setOf(stepsReadPermission))
            startActivityForResult(call, intent, "healthConnectPermissionCallback")
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("granted", false)
            ret.put("error", e.message)
            call.resolve(ret)
        }
    }

    @ActivityCallback
    fun healthConnectPermissionCallback(call: PluginCall, result: ActivityResult) {
        checkHealthPermissions(call)
    }

    @PluginMethod
    fun openHealthConnectSettings(call: PluginCall) {
        val ret = JSObject()
        try {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            ret.put("success", true)
        } catch (e: Exception) {
            ret.put("success", false)
            ret.put("error", e.message)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getDailySteps(call: PluginCall) {
        val ret = JSObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            ret.put("steps", null)
            ret.put("source", "health_connect")
            ret.put("isAvailable", false)
            ret.put("message", "Health Connect not available")
            call.resolve(ret)
            return
        }

        val dateStr = call.getString("date")
        val targetDate = if (!dateStr.isNullOrEmpty()) {
            try { LocalDate.parse(dateStr) } catch (e: Exception) { LocalDate.now() }
        } else {
            LocalDate.now()
        }

        val startOfDay = ZonedDateTime.of(targetDate, LocalTime.MIN, ZoneId.systemDefault()).toInstant()
        val endOfDay = ZonedDateTime.of(targetDate, LocalTime.MAX, ZoneId.systemDefault()).toInstant()

        val client = HealthConnectClient.getOrCreate(context)
        scope.launch(Dispatchers.IO) {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.contains(stepsReadPermission)) {
                    ret.put("steps", null)
                    ret.put("source", "health_connect")
                    ret.put("isAvailable", true)
                    ret.put("hasPermission", false)
                    ret.put("message", "Health Connect read permission for steps not granted")
                    call.resolve(ret)
                    return@launch
                }

                val response = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
                    )
                )

                val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
                ret.put("steps", steps)
                ret.put("source", "health_connect")
                ret.put("isAvailable", true)
                ret.put("hasPermission", true)
                ret.put("hasData", steps > 0)
                ret.put("date", targetDate.toString())
                ret.put("timestamp", System.currentTimeMillis())
                call.resolve(ret)
            } catch (e: Exception) {
                ret.put("steps", null)
                ret.put("source", "health_connect")
                ret.put("isAvailable", true)
                ret.put("error", e.message ?: "Failed to aggregate steps")
                call.resolve(ret)
            }
        }
    }
}
