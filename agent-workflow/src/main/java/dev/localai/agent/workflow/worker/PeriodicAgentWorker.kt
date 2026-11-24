package dev.localai.agent.workflow.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class PeriodicAgentWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PeriodicAgentWorker"
        const val KEY_OBJECTIVE = "agent_objective"
        const val KEY_SESSION_ID = "agent_session_id"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext

        if (PowerConstraintGuard.isLowBatteryOrSaver(context)) {
            Log.w(TAG, "Postponing background agent work: Low battery (<15%) or battery saver is active.")
            return@withContext Result.retry()
        }

        try {
            val objective = inputData.getString(KEY_OBJECTIVE) ?: "Perform periodic maintenance and summary"
            val sessionId = inputData.getString(KEY_SESSION_ID)

            Log.i(TAG, "Executing periodic agent work for objective: $objective (session: $sessionId)")
            val success = executeAgentTask(context, objective, sessionId)

            if (success) {
                Result.success()
            } else {
                Log.w(TAG, "Periodic agent task returned failure. Requesting retry.")
                Result.retry()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to execute PeriodicAgentWorker: ${t.message}", t)
            Result.retry()
        }
    }

    open suspend fun executeAgentTask(context: Context, objective: String, sessionId: String?): Boolean {
        return true
    }
}
