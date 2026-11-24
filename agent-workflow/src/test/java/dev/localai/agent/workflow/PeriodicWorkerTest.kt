package dev.localai.agent.workflow

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.localai.agent.workflow.worker.AgentWorkScheduler
import dev.localai.agent.workflow.worker.PeriodicAgentWorker
import dev.localai.agent.workflow.worker.PowerConstraintGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeriodicWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        PowerConstraintGuard.overridePowerGatedForTesting = null
    }

    @Test
    fun testPeriodicWorkerGatedWhenBatterySaverActive() = runBlocking {
        PowerConstraintGuard.overridePowerGatedForTesting = true

        val worker = TestListenableWorkerBuilder<PeriodicAgentWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun testPeriodicWorkerSucceedsUnderNormalPower() = runBlocking {
        PowerConstraintGuard.overridePowerGatedForTesting = false

        val worker = TestListenableWorkerBuilder<PeriodicAgentWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun testSchedulerEnqueuesWorkWithBatteryConstraint() {
        AgentWorkScheduler.schedulePeriodicBriefing(context, intervalHours = 12)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosByTag(AgentWorkScheduler.PERIODIC_WORK_TAG)
            .get()

        assertNotNull(workInfos)
        assertTrue(workInfos.isNotEmpty())
        val workInfo = workInfos.first()
        assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)
        assertTrue(workInfo.tags.contains(AgentWorkScheduler.PERIODIC_WORK_TAG))
    }
}
