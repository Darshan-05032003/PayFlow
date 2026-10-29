package com.darshan.payflow.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/** Small encrypted key-value store for secrets (PIN record, device binding). Values are never stored in clear. */
interface SecureStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

/** DataStore-backed; each value is AES-GCM encrypted with the key name as AAD, so a ciphertext cannot be moved between keys. */
class EncryptedSecureStore(private val prefs: DataStore<Preferences>, private val box: CryptoBox) : SecureStore {
    override suspend fun get(key: String): String? {
        val raw = prefs.data.first()[stringPreferencesKey(key)] ?: return null
        return runCatching { box.decryptString(raw, aad = key.toByteArray()) }.getOrNull() // tampered/foreign → treated as missing
    }

    override suspend fun put(key: String, value: String) {
        val cipher = box.encryptString(value, aad = key.toByteArray())
        prefs.edit { it[stringPreferencesKey(key)] = cipher }
    }

    override suspend fun remove(key: String) {
        prefs.edit { it.remove(stringPreferencesKey(key)) }
    }
}

/** Test/JVM store: same encryption, in-memory map. [getAll] exposes the raw ciphertext for "nothing in clear" assertions. */
class InMemorySecureStore(private val box: CryptoBox = InMemoryCryptoBox()) : SecureStore {
    private val map = ConcurrentHashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]?.let { runCatching { box.decryptString(it, key.toByteArray()) }.getOrNull() }
    override suspend fun put(key: String, value: String) { map[key] = box.encryptString(value, key.toByteArray()) }
    override suspend fun remove(key: String) { map.remove(key) }
    fun getAll(): Map<String, String> = map.toMap()
}
