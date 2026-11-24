package dev.localai.agent.workflow.worker

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

object PowerConstraintGuard {

    private const val TAG = "PowerConstraintGuard"
    const val DEFAULT_LOW_BATTERY_THRESHOLD_PCT = 15

    @Volatile
    var overridePowerGatedForTesting: Boolean? = null

    fun isLowBatteryOrSaver(
        context: Context,
        batteryThresholdPct: Int = DEFAULT_LOW_BATTERY_THRESHOLD_PCT
    ): Boolean {
        overridePowerGatedForTesting?.let { return it }

        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            val isSaver = pm?.isPowerSaveMode == true
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val isGated = isSaver || (batteryPct < batteryThresholdPct && !charging)
            if (isGated) {
                Log.w(TAG, "Device power gated: isSaver=$isSaver, battery=$batteryPct%, charging=$charging")
            }
            isGated
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read device power state: ${t.message}")
            false
        }
    }
}
