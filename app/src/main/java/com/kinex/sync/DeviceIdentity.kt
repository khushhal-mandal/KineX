package com.kinex.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * This device's anonymous account: the recovery-phrase entropy, and the cached token derived
 * from it.
 *
 * **Everything here is a suspend function on the IO dispatcher, including the trivial reads.**
 * Not politeness: `EncryptedSharedPreferences.create` does Android Keystore work on first use,
 * which is tens of milliseconds and occasionally more, and the entropy write below deliberately
 * blocks. A caller that has to be in a coroutine cannot accidentally do either on the main
 * thread.
 *
 * **Why an encrypted prefs file rather than the plain one next to the camera default.** The
 * stored entropy *is* the account — the phrase, the private key, the device id and every
 * session ever synced under it all derive from these sixteen bytes, and there is no server-side
 * password to fall back on. On a non-rooted device app-private storage is already unreadable by
 * other apps; encryption is what covers the rest: a rooted phone, an `adb backup`, or anything
 * that gets at the filesystem offline. The master key lives in the Keystore and never leaves
 * the device, so the file is useless without the phone even if it is copied.
 *
 * That property is also why `kinex.identity.xml` is excluded from cloud backup and device
 * transfer in `backup_rules.xml` and `data_extraction_rules.xml`. Two reasons, and the second
 * is the one that would have bitten: the file must not leave the device, *and* a restored copy
 * would arrive without the Keystore key that decrypts it, so a device transfer would install an
 * identity file the app cannot read.
 *
 * The recovery phrase is the intended way to move an account between phones. Nothing else is.
 */
internal class DeviceIdentity private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * The twelve words, creating the account on first call.
     *
     * Deriving the phrase from stored entropy rather than storing the phrase means there is one
     * representation of the secret on disk and the words are always consistent with the key —
     * they cannot drift, because they are recomputed from the same bytes the key is.
     */
    suspend fun recoveryPhrase(): String = withContext(Dispatchers.IO) {
        Bip39.encode(entropy()).joinToString(" ")
    }

    /** The keypair, via the phrase — the same path a restore on another device would take. */
    suspend fun keys(): DeviceKeys = withContext(Dispatchers.IO) {
        DeviceKeys.fromPhrase(Bip39.encode(entropy()).joinToString(" "))
    }

    suspend fun isPhraseAcknowledged(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_ACKNOWLEDGED, false)
    }

    suspend fun acknowledgePhrase() = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_ACKNOWLEDGED, true).apply()
    }

    /**
     * A cached token that will still be valid for long enough to use, or null.
     *
     * The margin exists because a token that passes an expiry check and then expires during the
     * request it was fetched for produces a 401 that looks like a signing failure. The worker
     * recovers from that anyway; not provoking it is cheaper than diagnosing it.
     */
    suspend fun cachedToken(): String? = withContext(Dispatchers.IO) {
        val token = prefs.getString(KEY_TOKEN, null) ?: return@withContext null
        val expiresAt = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        token.takeIf { System.currentTimeMillis() + TOKEN_MARGIN_MS < expiresAt }
    }

    suspend fun storeToken(token: String, expiresInSeconds: Long, deviceId: String) =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000)
                .putString(KEY_DEVICE_ID, deviceId)
                .apply()
        }

    /** Drops the cached token. Called when the server rejects one that had not expired yet. */
    suspend fun clearToken() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES_AT).apply()
    }

    /** The server's device id, once a token has been issued. Null before the first handshake. */
    suspend fun deviceId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_DEVICE_ID, null)
    }

    /**
     * The account's sixteen bytes, generated on first call and never again.
     *
     * `@Synchronized` because two coroutines arriving together on a fresh install would
     * otherwise each generate entropy and the second would overwrite the first — orphaning any
     * account the first had already registered.
     *
     * **`commit()`, not `apply()`.** Everywhere else in this app the argument runs the other
     * way: `AppSettings` uses `apply` because losing a toggle to a process kill costs a toggle.
     * Losing *this* write costs the account — the next launch would generate different entropy,
     * derive a different device id, and every session synced under the old one would be
     * stranded on the server under an identity nothing can reach again. Blocking a background
     * thread for a few milliseconds once in the life of an install is the correct trade.
     */
    @Synchronized
    private fun entropy(): ByteArray {
        prefs.getString(KEY_ENTROPY, null)?.let {
            return Base64Url.decode(it, Bip39.ENTROPY_BYTES)
        }
        val fresh = ByteArray(Bip39.ENTROPY_BYTES)
        SecureRandom().nextBytes(fresh)
        prefs.edit().putString(KEY_ENTROPY, Base64Url.encode(fresh)).commit()
        return fresh
    }

    companion object {
        /** Becomes `shared_prefs/kinex.identity.xml`, which is the path the backup rules name. */
        private const val FILE_NAME = "kinex.identity"

        private const val KEY_ENTROPY = "recovery_entropy"
        private const val KEY_ACKNOWLEDGED = "phrase_acknowledged"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_DEVICE_ID = "device_id"

        /** Tokens last 24 hours; treating the last minute as expired costs nothing. */
        private const val TOKEN_MARGIN_MS = 60_000L

        @Volatile
        private var instance: DeviceIdentity? = null

        /** One per process, because the encrypted prefs file and its Keystore key are one. */
        fun get(context: Context): DeviceIdentity =
            instance ?: synchronized(this) {
                instance ?: DeviceIdentity(context.applicationContext).also { instance = it }
            }
    }
}
