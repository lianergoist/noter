package dk.rpix.noter.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dk.rpix.noter.R
import kotlinx.coroutines.flow.Flow

/** Rolig besked, der bliver stående, til man lukker den. */
suspend fun SnackbarHostState.showMessage(text: String, close: String? = null) {
    showSnackbar(text, actionLabel = close, withDismissAction = close == null, duration = SnackbarDuration.Long)
}

/** Vis konfliktbeskeden, når en sync har lavet en konfliktkopi. */
@Composable
fun ConflictMessages(conflicts: Flow<Int>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(conflicts, resources) {
        conflicts.collect { snackbar.showMessage(resources.getString(R.string.conflict)) }
    }
}

/**
 * Én ViewModelStore pr. skærm, så en skærms ViewModel overlever rotation, men ryddes
 * (og gemmer det sidste), når man forlader skærmen.
 */
class ScreenStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun owner(route: String): ViewModelStoreOwner {
        val store = stores.getOrPut(route) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    fun leave(route: String) {
        stores.remove(route)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}
