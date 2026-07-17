package com.fde.audiosmbsync.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class UploadStatus { QUEUED, UPLOADING, UPLOADED, FAILED }
enum class SmbAuthMode { GUEST, PASSWORD }
enum class SyncScheduleMode { DAILY, INTERVAL }
enum class VerificationMode { SIZE_ONLY, SHA256 }
enum class RecordedAtSource { FILENAME, MODIFIED_TIME, NOT_REQUIRED }
enum class SyncRange { ALL, RECENT_DAYS, CUSTOM_START }
enum class SyncRunStatus { RUNNING, SUCCESS, PARTIAL_FAILURE, FAILED }
enum class SyncItemResult { UPLOADED, SKIPPED, FAILED }

@Entity(tableName = "app_config")
data class AppConfig(
    @PrimaryKey val id: Int = 1,
    val deviceCode: String = "", val salesPhoneNumber: String = "", // v1/v2 legacy only
    val deviceName: String = "", val recordingTreeUri: String = "", val smbHost: String = "",
    val shareName: String = "", val smbSubPath: String = "", val authMode: SmbAuthMode = SmbAuthMode.PASSWORD,
    val username: String = "", val passwordCiphertext: String = "", val passwordIv: String = "",
    val createDeviceSubfolder: Boolean = false, val renameOnUpload: Boolean = true,
    val customFilenameRegex: String = "", val allowModifiedTimeFallback: Boolean = false,
    val verificationMode: VerificationMode = VerificationMode.SIZE_ONLY,
    val autoSyncEnabled: Boolean = true, val syncScheduleMode: SyncScheduleMode = SyncScheduleMode.DAILY,
    val dailySyncHour: Int = 2, val dailySyncMinute: Int = 0, val intervalMinutes: Long = 1440,
    val syncRange: SyncRange = SyncRange.ALL, val recentDays: Int = 7, val customStartAt: Long? = null,
    val lastSyncAt: Long? = null
)

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val sourceUri: String, val sourceName: String,
    val fingerprint: String, val sizeBytes: Long, val recordedAt: Long,
    val recordedAtSource: RecordedAtSource = RecordedAtSource.FILENAME, val targetName: String,
    val targetDirectory: String = "", val status: UploadStatus = UploadStatus.QUEUED,
    val retryCount: Int = 0, val lastError: String? = null, val uploadedAt: Long? = null
)

@Entity(tableName = "sync_runs")
data class SyncRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val startedAt: Long, val finishedAt: Long? = null,
    val range: SyncRange, val scannedCount: Int = 0, val skippedCount: Int = 0,
    val uploadedCount: Int = 0, val failedCount: Int = 0, val status: SyncRunStatus = SyncRunStatus.RUNNING,
    val error: String? = null
)

@Entity(tableName = "sync_run_items")
data class SyncRunItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val recordingId: Long? = null,
    val sourceName: String, val targetName: String, val result: SyncItemResult,
    val reason: String? = null, val processedAt: Long
)

class Converters {
    @TypeConverter fun status(v: String) = UploadStatus.valueOf(v); @TypeConverter fun status(v: UploadStatus) = v.name
    @TypeConverter fun auth(v: String) = SmbAuthMode.valueOf(v); @TypeConverter fun auth(v: SmbAuthMode) = v.name
    @TypeConverter fun schedule(v: String) = SyncScheduleMode.valueOf(v); @TypeConverter fun schedule(v: SyncScheduleMode) = v.name
    @TypeConverter fun verify(v: String) = VerificationMode.valueOf(v); @TypeConverter fun verify(v: VerificationMode) = v.name
    @TypeConverter fun source(v: String) = RecordedAtSource.valueOf(v); @TypeConverter fun source(v: RecordedAtSource) = v.name
    @TypeConverter fun range(v: String) = SyncRange.valueOf(v); @TypeConverter fun range(v: SyncRange) = v.name
    @TypeConverter fun runStatus(v: String) = SyncRunStatus.valueOf(v); @TypeConverter fun runStatus(v: SyncRunStatus) = v.name
    @TypeConverter fun itemResult(v: String) = SyncItemResult.valueOf(v); @TypeConverter fun itemResult(v: SyncItemResult) = v.name
}

@Dao interface AppConfigDao { @Query("SELECT * FROM app_config WHERE id=1") fun observe(): Flow<AppConfig?>; @Query("SELECT * FROM app_config WHERE id=1") suspend fun get(): AppConfig?; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(c: AppConfig) }
@Dao interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY recordedAt DESC") fun observeAll(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE fingerprint=:fingerprint LIMIT 1") suspend fun byFingerprint(fingerprint: String): Recording?
    @Query("SELECT * FROM recordings WHERE (status='QUEUED' OR (status='FAILED' AND (lastError IS NULL OR lastError NOT LIKE '命名%'))) ORDER BY recordedAt") suspend fun pending(): List<Recording>
    @Query("SELECT * FROM recordings WHERE targetName=:name AND targetDirectory=:dir LIMIT 1") suspend fun byTargetName(name: String, dir: String): Recording?
    @Insert suspend fun insert(r: Recording): Long
    @Query("UPDATE recordings SET status=:status,retryCount=:retries,lastError=:error,uploadedAt=:uploadedAt WHERE id=:id") suspend fun updateStatus(id:Long,status:UploadStatus,retries:Int,error:String?,uploadedAt:Long?)
}
@Dao interface SyncLogDao {
    @Insert suspend fun start(run: SyncRun): Long
    @Insert suspend fun addItem(item: SyncRunItem)
    @Query("UPDATE sync_runs SET finishedAt=:finishedAt,scannedCount=:scanned,skippedCount=:skipped,uploadedCount=:uploaded,failedCount=:failed,status=:status,error=:error WHERE id=:id") suspend fun finish(id:Long,finishedAt:Long,scanned:Int,skipped:Int,uploaded:Int,failed:Int,status:SyncRunStatus,error:String?)
    @Query("SELECT * FROM sync_runs ORDER BY startedAt DESC LIMIT 1000") fun observeRuns(): Flow<List<SyncRun>>
    @Query("SELECT * FROM sync_run_items WHERE runId=:runId ORDER BY processedAt DESC") fun observeItems(runId:Long): Flow<List<SyncRunItem>>
    @Query("DELETE FROM sync_runs WHERE startedAt < :cutoff") suspend fun pruneRuns(cutoff:Long)
    @Query("DELETE FROM sync_runs WHERE id NOT IN (SELECT id FROM sync_runs ORDER BY startedAt DESC LIMIT 1000)") suspend fun pruneCount()
}

@Database(entities=[AppConfig::class,Recording::class,SyncRun::class,SyncRunItem::class],version=3,exportSchema=true)
@TypeConverters(Converters::class)
abstract class AppDatabase: RoomDatabase() {
    abstract fun configDao():AppConfigDao; abstract fun recordingDao():RecordingDao; abstract fun syncLogDao():SyncLogDao
    companion object {
        private val M1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){ val cols=listOf("salesPhoneNumber TEXT NOT NULL DEFAULT ''","deviceName TEXT NOT NULL DEFAULT ''","authMode TEXT NOT NULL DEFAULT 'PASSWORD'","createDeviceSubfolder INTEGER NOT NULL DEFAULT 0","renameOnUpload INTEGER NOT NULL DEFAULT 1","customFilenameRegex TEXT NOT NULL DEFAULT ''","allowModifiedTimeFallback INTEGER NOT NULL DEFAULT 0","verificationMode TEXT NOT NULL DEFAULT 'SIZE_ONLY'","autoSyncEnabled INTEGER NOT NULL DEFAULT 1","syncScheduleMode TEXT NOT NULL DEFAULT 'DAILY'","dailySyncHour INTEGER NOT NULL DEFAULT 2","dailySyncMinute INTEGER NOT NULL DEFAULT 0","intervalMinutes INTEGER NOT NULL DEFAULT 1440"); cols.forEach{db.execSQL("ALTER TABLE app_config ADD COLUMN $it")}; db.execSQL("ALTER TABLE recordings ADD COLUMN recordedAtSource TEXT NOT NULL DEFAULT 'FILENAME'");db.execSQL("ALTER TABLE recordings ADD COLUMN targetDirectory TEXT NOT NULL DEFAULT ''")}}
        private val M2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){ db.execSQL("ALTER TABLE app_config ADD COLUMN smbSubPath TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE app_config ADD COLUMN syncRange TEXT NOT NULL DEFAULT 'ALL'");db.execSQL("ALTER TABLE app_config ADD COLUMN recentDays INTEGER NOT NULL DEFAULT 7");db.execSQL("ALTER TABLE app_config ADD COLUMN customStartAt INTEGER");db.execSQL("CREATE TABLE IF NOT EXISTS sync_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,startedAt INTEGER NOT NULL,finishedAt INTEGER,range TEXT NOT NULL,scannedCount INTEGER NOT NULL,skippedCount INTEGER NOT NULL,uploadedCount INTEGER NOT NULL,failedCount INTEGER NOT NULL,status TEXT NOT NULL,error TEXT)");db.execSQL("CREATE TABLE IF NOT EXISTS sync_run_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,runId INTEGER NOT NULL,recordingId INTEGER,sourceName TEXT NOT NULL,targetName TEXT NOT NULL,result TEXT NOT NULL,reason TEXT,processedAt INTEGER NOT NULL)")}}
        fun create(context:Context)=Room.databaseBuilder(context,AppDatabase::class.java,"audio-sync.db").addMigrations(M1_2,M2_3).build()
    }
}
