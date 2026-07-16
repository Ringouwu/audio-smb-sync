package com.fde.audiosmbsync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class UploadStatus { QUEUED, UPLOADING, UPLOADED, FAILED }

@Entity(tableName = "app_config")
data class AppConfig(
    @PrimaryKey val id: Int = 1,
    val deviceCode: String = "",
    val recordingTreeUri: String = "",
    val smbHost: String = "",
    val shareName: String = "",
    val username: String = "",
    val passwordCiphertext: String = "",
    val passwordIv: String = "",
    val lastSyncAt: Long? = null
)

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val sourceName: String,
    val fingerprint: String,
    val sizeBytes: Long,
    val recordedAt: Long,
    val targetName: String,
    val status: UploadStatus = UploadStatus.QUEUED,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val uploadedAt: Long? = null
)

class Converters {
    @TypeConverter fun toStatus(value: String) = UploadStatus.valueOf(value)
    @TypeConverter fun fromStatus(value: UploadStatus) = value.name
}

@Dao
interface AppConfigDao {
    @Query("SELECT * FROM app_config WHERE id = 1") fun observe(): Flow<AppConfig?>
    @Query("SELECT * FROM app_config WHERE id = 1") suspend fun get(): AppConfig?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: AppConfig)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY recordedAt DESC") fun observeAll(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE fingerprint = :fingerprint LIMIT 1") suspend fun byFingerprint(fingerprint: String): Recording?
    @Query("SELECT * FROM recordings WHERE status = 'QUEUED' OR (status = 'FAILED' AND (lastError IS NULL OR lastError NOT LIKE '命名冲突%')) ORDER BY recordedAt") suspend fun pending(): List<Recording>
    @Query("SELECT * FROM recordings WHERE targetName = :targetName LIMIT 1") suspend fun byTargetName(targetName: String): Recording?
    @Insert suspend fun insert(recording: Recording)
    @Query("UPDATE recordings SET status = :status, retryCount = :retries, lastError = :error, uploadedAt = :uploadedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus, retries: Int, error: String?, uploadedAt: Long?)
}

@Database(entities = [AppConfig::class, Recording::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun configDao(): AppConfigDao
    abstract fun recordingDao(): RecordingDao
    companion object { fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "audio-sync.db").build() }
}
