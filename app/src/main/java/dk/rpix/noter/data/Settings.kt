package dk.rpix.noter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Krypterer adgangskoden med en AES-nøgle, der aldrig forlader Android Keystore. */
object PasswordCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "noter_password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Returnerer base64 af IV + chiffertekst. */
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** null, hvis teksten ikke kan dekrypteres (fx hvis nøglen er væk efter en gendannelse). */
    fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

data class Account(val url: String, val user: String, val password: String)

/** Seneste fejl fra en sync, vist roligt på listen. */
sealed interface SyncError {
    data object Network : SyncError
    data object Auth : SyncError
    data object NotConfigured : SyncError
    data class Server(val status: Int) : SyncError
    data class Other(val message: String) : SyncError

    fun encode(): String = when (this) {
        Network -> "network"
        Auth -> "auth"
        NotConfigured -> "config"
        is Server -> "server:$status"
        is Other -> "other:$message"
    }

    companion object {
        fun decode(s: String?): SyncError? = when {
            s == null -> null
            s == "network" -> Network
            s == "auth" -> Auth
            s == "config" -> NotConfigured
            s.startsWith("server:") -> Server(s.removePrefix("server:").toIntOrNull() ?: 0)
            else -> Other(s.removePrefix("other:"))
        }
    }
}

data class SyncState(val lastSuccess: Long?, val error: SyncError?)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** URL, bruger, krypteret adgangskode og sync-status. */
class Settings(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val URL = stringPreferencesKey("url")
        val USER = stringPreferencesKey("user")
        val PASSWORD = stringPreferencesKey("password_enc")
        val LAST_SYNC = longPreferencesKey("last_sync")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val AUTH_FAILED = booleanPreferencesKey("auth_failed")
    }

    val url: Flow<String> = store.data.map { it[Keys.URL] ?: "" }
    val user: Flow<String> = store.data.map { it[Keys.USER] ?: "" }
    val hasPassword: Flow<Boolean> = store.data.map { it[Keys.PASSWORD] != null }
    val authFailed: Flow<Boolean> = store.data.map { it[Keys.AUTH_FAILED] ?: false }
    val syncState: Flow<SyncState> = store.data.map {
        SyncState(it[Keys.LAST_SYNC], SyncError.decode(it[Keys.LAST_ERROR]))
    }

    /** null, hvis appen ikke er sat op, eller adgangskoden ikke kan læses. */
    suspend fun account(): Account? {
        val p = store.data.first()
        val url = p[Keys.URL]?.takeIf { it.isNotBlank() } ?: return null
        val user = p[Keys.USER]?.takeIf { it.isNotBlank() } ?: return null
        val password = p[Keys.PASSWORD]?.let(PasswordCrypto::decrypt) ?: return null
        return Account(url, user, password)
    }

    /** [password] = null beholder den gemte adgangskode. */
    suspend fun saveAccount(url: String, user: String, password: String?) {
        val enc = password?.let(PasswordCrypto::encrypt)
        store.edit {
            it[Keys.URL] = url
            it[Keys.USER] = user
            if (enc != null) it[Keys.PASSWORD] = enc
            it[Keys.AUTH_FAILED] = false
            it.remove(Keys.LAST_ERROR)
        }
    }

    suspend fun storedPassword(): String? = store.data.first()[Keys.PASSWORD]?.let(PasswordCrypto::decrypt)

    suspend fun recordSuccess(time: Long) = store.edit {
        it[Keys.LAST_SYNC] = time
        it.remove(Keys.LAST_ERROR)
        it[Keys.AUTH_FAILED] = false
    }

    suspend fun recordError(error: SyncError) = store.edit {
        it[Keys.LAST_ERROR] = error.encode()
        if (error == SyncError.Auth) it[Keys.AUTH_FAILED] = true
    }

    suspend fun clearSyncHistory() = store.edit {
        it.remove(Keys.LAST_SYNC)
        it.remove(Keys.LAST_ERROR)
    }
}
