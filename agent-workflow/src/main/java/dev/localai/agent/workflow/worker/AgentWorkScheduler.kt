package dev.localai.agent.workflow.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AgentWorkScheduler {

    private const val TAG = "AgentWorkScheduler"
    const val DEFAULT_WORK_NAME = "PeriodicAgentWorkflow"
    const val PERIODIC_WORK_TAG = "AgentWorkflowTag"
    const val DEFAULT_TAG = "AgentWorkflowTag"
    private const val PREFS_NAME = "localai_agent_scheduler_prefs"
    private const val KEY_PREFIX_SCHEDULED = "key_scheduled_"

    fun schedulePeriodicBriefing(context: Context, intervalHours: Long = 24) {
        schedulePeriodicWork(
            context = context,
            workName = DEFAULT_WORK_NAME,
            intervalHours = intervalHours,
            objective = "Run daily proactive agent briefing and telemetry audit"
        )
    }

    fun schedulePeriodicWork(
        context: Context,
        workName: String = DEFAULT_WORK_NAME,
        intervalHours: Long = 24,
        objective: String? = null,
        workerClass: Class<out PeriodicAgentWorker> = PeriodicAgentWorker::class.java
    ) {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = Data.Builder().apply {
                if (!objective.isNullOrBlank()) {
                    putString(PeriodicAgentWorker.KEY_OBJECTIVE, objective)
                }
            }.build()

            val workRequest = PeriodicWorkRequest.Builder(workerClass, intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag(PERIODIC_WORK_TAG)
                .addTag(workName)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            setScheduled(context, workName, true)
            Log.i(TAG, "Scheduled periodic work '$workName' every $intervalHours hours with battery-not-low constraints.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to schedule periodic work '$workName': ${t.message}", t)
        }
    }

    fun cancelWork(context: Context, workName: String = DEFAULT_WORK_NAME) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName)
            setScheduled(context, workName, false)
            Log.i(TAG, "Cancelled periodic work '$workName'.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel periodic work '$workName': ${t.message}", t)
        }
    }

    fun isScheduled(context: Context, workName: String = DEFAULT_WORK_NAME): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_PREFIX_SCHEDULED + workName, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun setScheduled(context: Context, workName: String, enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_PREFIX_SCHEDULED + workName, enabled).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist scheduler preference: ${t.message}")
        }
    }
}
