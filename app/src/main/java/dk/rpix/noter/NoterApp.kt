package dk.rpix.noter

import android.app.Application
import dk.rpix.noter.data.NoterDatabase
import dk.rpix.noter.data.RoomNoteStore
import dk.rpix.noter.data.Settings
import dk.rpix.noter.sync.SyncManager
import dk.rpix.noter.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manuel afhængighedsstyring: alt deles herfra. */
class NoterApp : Application() {
    /** Til arbejde, der skal gøres færdigt, selvom skærmen lukkes (fx sidste gem i editoren). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { NoterDatabase.open(this) }
    val store by lazy { RoomNoteStore(database.notes()) }
    val settings by lazy { Settings(this) }
    val syncManager by lazy { SyncManager(this, store, settings) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            if (settings.account() != null && !settings.authFailed.first()) SyncScheduler.schedulePeriodic(this@NoterApp)
        }
    }

    /** Efter hver lokal ændring. */
    fun requestSync() = SyncScheduler.requestSync(this)
}
