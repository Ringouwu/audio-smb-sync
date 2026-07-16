package com.fde.audiosmbsync.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun schedule(workManager: WorkManager) {
        val now = ZonedDateTime.now()
        var nextRun = now.with(LocalTime.of(2, 0, 0, 0))
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusDays(1)
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, nextRun).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork("daily_audio_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
