package com.fde.audiosmbsync

import android.app.Application
import androidx.work.WorkManager
import com.fde.audiosmbsync.data.AppDatabase
import com.fde.audiosmbsync.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioSyncApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            database.configDao().get()?.let { SyncScheduler.schedule(WorkManager.getInstance(this@AudioSyncApplication), it) }
        }
    }
}
