package dk.rpix.noter.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dk.rpix.noter.core.Note
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "notes", indices = [Index(value = ["href"], unique = true)])
data class NoteEntity(
    @PrimaryKey val uid: String,
    val href: String?,
    val etag: String?,
    val raw: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val modified: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val ignored: Boolean,
) {
    fun toNote() = Note(uid, href, etag, raw, title, body, tags, modified, dirty, deleted, ignored)
}

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val k: String, val v: String)

/** Tags kan indeholde komma, så de gemmes som en JSON-liste. */
class Converters {
    @TypeConverter
    fun tagsToJson(tags: List<String>): String = JSONArray(tags).toString()

    @TypeConverter
    fun jsonToTags(json: String): List<String> {
        val a = JSONArray(json)
        return List(a.length()) { a.getString(it) }
    }
}

@Dao
abstract class NoteDao {
    @Query("SELECT * FROM notes WHERE href = :href")
    abstract fun byHref(href: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE uid = :uid")
    abstract fun byUid(uid: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE uid = :uid")
    abstract fun observe(uid: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE dirty = 1 AND ignored = 0")
    abstract fun pending(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE href IS NOT NULL")
    abstract fun withHref(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND ignored = 0 ORDER BY modified DESC, title")
    abstract fun visible(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND ignored = 0 ORDER BY modified DESC, title")
    abstract fun observeVisible(): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE dirty = 1 AND ignored = 0")
    abstract fun observePendingCount(): Flow<Int>

    /** INSERT OR REPLACE, som i referencen: fjerner også en anden række med samme href. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE uid = :uid")
    abstract fun remove(uid: String)

    @Transaction
    open fun putClean(oldUid: String?, note: NoteEntity) {
        if (oldUid != null) remove(oldUid)
        insert(note)
    }

    @Query("UPDATE notes SET href = :href, etag = :etag, dirty = 0 WHERE uid = :uid")
    abstract fun markPushed(uid: String, href: String, etag: String)

    @Query("UPDATE notes SET href = NULL, etag = NULL, dirty = 1, deleted = 0 WHERE uid = :uid")
    abstract fun detach(uid: String)

    @Query(
        "UPDATE notes SET raw = :raw, title = :title, body = :body, tags = :tags, modified = :modified, " +
            "dirty = 1 WHERE uid = :uid",
    )
    abstract fun update(uid: String, raw: String, title: String, body: String, tags: List<String>, modified: String)

    @Query("UPDATE notes SET deleted = 1, dirty = 1 WHERE uid = :uid")
    abstract fun markDeleted(uid: String)

    @Query("SELECT v FROM meta WHERE k = :k")
    abstract fun getMeta(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun setMeta(meta: MetaEntity)

    @Query("DELETE FROM meta")
    abstract fun clearMeta()

    @Query("DELETE FROM notes WHERE dirty = 0 OR ignored = 1")
    abstract fun removeClean()

    @Query("DELETE FROM notes WHERE deleted = 1")
    abstract fun removeDeleted()

    @Query("UPDATE notes SET href = NULL, etag = NULL")
    abstract fun detachAll()

    /**
     * Ny samling: glem alt, der kom fra den gamle, men behold lokale ændringer,
     * så de oprettes i den nye samling ved næste sync.
     */
    @Transaction
    open fun resetForNewCollection() {
        clearMeta()
        removeClean()
        removeDeleted()
        detachAll()
    }
}

@Database(entities = [NoteEntity::class, MetaEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NoterDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun open(context: Context): NoterDatabase =
            Room.databaseBuilder(context, NoterDatabase::class.java, "noter.db").build()
    }
}
