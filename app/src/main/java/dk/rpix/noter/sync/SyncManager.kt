package dk.rpix.noter.sync

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dk.rpix.noter.BuildConfig
import dk.rpix.noter.NoterApp
import dk.rpix.noter.R
import dk.rpix.noter.core.AuthError
import dk.rpix.noter.core.DavClient
import dk.rpix.noter.core.DavError
import dk.rpix.noter.core.NetworkError
import dk.rpix.noter.core.SyncStats
import dk.rpix.noter.core.Syncer
import dk.rpix.noter.data.Account
import dk.rpix.noter.data.RoomNoteStore
import dk.rpix.noter.data.Settings
import dk.rpix.noter.data.SyncError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncOutcome {
    data class Ok(val stats: SyncStats) : SyncOutcome
    data class Failed(val error: SyncError) : SyncOutcome
}

/** Debug-bygget må bruge http til en lokal testserver (se network_security_config). */
fun allowInsecure(url: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    val host = runCatching { Uri.parse(url.trim()).host }.getOrNull()
    return host in setOf("10.0.2.2", "localhost", "127.0.0.1")
}

fun davClient(account: Account) =
    DavClient(account.url, account.user, account.password, allowInsecure = allowInsecure(account.url))

/** Kører [Syncer] med højst én sync ad gangen og husker resultatet i [Settings]. */
class SyncManager(
    private val context: Context,
    private val store: RoomNoteStore,
    private val settings: Settings,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Antal konflikter i en sync, så brugerfladen kan vise en besked. */
    private val _conflicts = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val conflicts: SharedFlow<Int> = _conflicts

    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        _running.value = true
        try {
            val outcome = withContext(Dispatchers.IO) { runSync() }
            when (outcome) {
                is SyncOutcome.Ok -> {
                    settings.recordSuccess(System.currentTimeMillis())
                    if (outcome.stats.conflicts > 0) _conflicts.tryEmit(outcome.stats.conflicts)
                }
                is SyncOutcome.Failed -> {
                    settings.recordError(outcome.error)
                    // Stop de periodiske forsøg, til loginoplysningerne er rettet
                    if (outcome.error == SyncError.Auth) SyncScheduler.cancelPeriodic(context)
                }
            }
            outcome
        } finally {
            _running.value = false
        }
    }

    private suspend fun runSync(): SyncOutcome {
        val account = settings.account() ?: return SyncOutcome.Failed(SyncError.NotConfigured)
        return try {
            SyncOutcome.Ok(Syncer(store, davClient(account), context.getString(R.string.conflict_label)).sync())
        } catch (_: AuthError) {
            SyncOutcome.Failed(SyncError.Auth)
        } catch (_: NetworkError) {
            SyncOutcome.Failed(SyncError.Network)
        } catch (e: DavError) {
            SyncOutcome.Failed(if (e.status != 0) SyncError.Server(e.status) else SyncError.Other(e.message ?: ""))
        } catch (e: IllegalArgumentException) {
            SyncOutcome.Failed(SyncError.NotConfigured)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NoterApp
        return when (val outcome = app.syncManager.syncNow()) {
            is SyncOutcome.Ok -> Result.success()
            is SyncOutcome.Failed -> when (outcome.error) {
                SyncError.Network -> Result.retry()
                is SyncError.Server -> if (outcome.error.status >= 500) Result.retry() else Result.failure()
                else -> Result.failure()
            }
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "periodic-sync"
    private const val AFTER_CHANGE = "sync-after-change"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** Engangs-sync efter en lokal ændring; venter på net og prøver igen med backoff. */
    fun requestSync(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(AFTER_CHANGE, ExistingWorkPolicy.REPLACE, req)
    }
}
