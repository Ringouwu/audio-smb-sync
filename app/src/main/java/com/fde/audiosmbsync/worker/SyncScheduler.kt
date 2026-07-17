package com.fde.audiosmbsync.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.SyncScheduleMode
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_NAME = "audio_sync_periodic"
    private const val DELAYED_NAME = "audio_sync_delayed"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(workManager: WorkManager, config: AppConfig) {
        workManager.cancelUniqueWork(PERIODIC_NAME)
        if (!config.autoSyncEnabled) return
        val (intervalMinutes, initialDelayMinutes) = when (config.syncScheduleMode) {
            SyncScheduleMode.DAILY -> {
                val now = ZonedDateTime.now()
                var next = now.with(LocalTime.of(config.dailySyncHour.coerceIn(0, 23), config.dailySyncMinute.coerceIn(0, 59)))
                if (!next.isAfter(now)) next = next.plusDays(1)
                24L * 60 to Duration.between(now, next).toMinutes().coerceAtLeast(0)
            }
            SyncScheduleMode.INTERVAL -> config.intervalMinutes.coerceAtLeast(15) to config.intervalMinutes.coerceAtLeast(15)
        }
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun scheduleOnce(workManager: WorkManager, delayMinutes: Long = 0) {
        val request = OneTimeWorkRequestBuilder<DailySyncWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(DELAYED_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
