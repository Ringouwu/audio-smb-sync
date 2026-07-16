package com.fde.audiosmbsync

import android.app.Application
import androidx.work.WorkManager
import com.fde.audiosmbsync.data.AppDatabase
import com.fde.audiosmbsync.worker.SyncScheduler

class AudioSyncApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(WorkManager.getInstance(this))
    }
}
