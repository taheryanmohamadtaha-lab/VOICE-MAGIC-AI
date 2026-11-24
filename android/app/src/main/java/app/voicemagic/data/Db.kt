package app.voicemagic.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val durationMs: Long,
    val sampleRate: Int,
    val effectId: String?,
    val createdAt: Long,
    /** true only when produced by a cloud AI model; used to label AI-generated/converted audio. */
    val aiGenerated: Boolean = false,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Track names/files: lines of "name|relativeFile|sampleRate". */
    val manifest: String,
    val updatedAt: Long,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val params: String,
)

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Epoch millis when the user gave explicit consent (0 = never; such a profile cannot be used). */
    val consentAt: Long,
    /** Local file with the recorded consent statement. */
    val consentSamplePath: String?,
    /** Newline separated local sample file paths. */
    val samplePaths: String,
    /** Voice characteristics for the local demonstration mode (EffectParams.serialize()). */
    val params: String,
    /** Id of the profile at the cloud provider, if the user uploaded samples. */
    val cloudProfileId: String? = null,
)

@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,
    val credits: Int,
    val note: String,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC") fun all(): Flow<List<RecordingEntity>>
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT :n") fun recent(n: Int): Flow<List<RecordingEntity>>
    @Query("SELECT * FROM recordings") suspend fun snapshot(): List<RecordingEntity>
    @Insert suspend fun insert(r: RecordingEntity): Long
    @Delete suspend fun delete(r: RecordingEntity)
    @Query("DELETE FROM recordings") suspend fun deleteAll()
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun all(): Flow<List<ProjectEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProjectEntity): Long
    @Delete suspend fun delete(p: ProjectEntity)
    @Query("SELECT * FROM projects") suspend fun snapshot(): List<ProjectEntity>
    @Query("DELETE FROM projects") suspend fun deleteAll()
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name") fun all(): Flow<List<PresetEntity>>
    @Insert suspend fun insert(p: PresetEntity): Long
    @Delete suspend fun delete(p: PresetEntity)
    @Query("DELETE FROM presets") suspend fun deleteAll()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC") fun all(): Flow<List<VoiceProfileEntity>>
    @Query("SELECT * FROM voice_profiles") suspend fun snapshot(): List<VoiceProfileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: VoiceProfileEntity): Long
    @Delete suspend fun delete(p: VoiceProfileEntity)
    @Query("DELETE FROM voice_profiles") suspend fun deleteAll()
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage ORDER BY ts DESC LIMIT 100") fun recent(): Flow<List<UsageEntity>>
    @Insert suspend fun insert(u: UsageEntity): Long
    @Query("DELETE FROM usage") suspend fun deleteAll()
}

@Database(
    entities = [RecordingEntity::class, ProjectEntity::class, PresetEntity::class, VoiceProfileEntity::class, UsageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun projects(): ProjectDao
    abstract fun presets(): PresetDao
    abstract fun profiles(): ProfileDao
    abstract fun usage(): UsageDao
}
