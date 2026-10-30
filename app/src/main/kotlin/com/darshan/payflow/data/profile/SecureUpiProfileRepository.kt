package com.darshan.payflow.data.profile

import com.darshan.payflow.data.security.SecureStore
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.model.UpiProfileRepository
import com.darshan.payflow.domain.model.UpiProfileValidation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ProfileRecord(val name: String, val vpa: String)

/**
 * The profile is encrypted at rest through [SecureStore] (AES-GCM, Keystore key, AAD = key name) because the user's own VPA and
 * name are personal data. Loaded once into memory; a record that fails to decrypt or validate is treated as absent.
 */
@Singleton
class SecureUpiProfileRepository @Inject constructor(private val store: SecureStore) : UpiProfileRepository {
    private val cache = MutableStateFlow<Loaded?>(null)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private class Loaded(val profile: UpiProfile?)

    override val profile: Flow<UpiProfile?> = cache.onStart { load() }.filterNotNull().map { it.profile }

    override suspend fun get(): UpiProfile? = load().profile

    override suspend fun save(profile: UpiProfile) {
        val valid = UpiProfile.validate(profile.name, profile.vpa) as? UpiProfileValidation.Valid ?: throw IllegalArgumentException("invalid profile")
        mutex.withLock {
            store.put(KEY, json.encodeToString(ProfileRecord(valid.profile.name, valid.profile.vpa)))
            cache.value = Loaded(valid.profile)
        }
    }

    override suspend fun clear() = mutex.withLock {
        store.remove(KEY)
        cache.value = Loaded(null)
    }

    private suspend fun load(): Loaded = cache.value ?: mutex.withLock {
        cache.value ?: Loaded(readStored()).also { cache.value = it }
    }

    private suspend fun readStored(): UpiProfile? {
        val raw = store.get(KEY) ?: return null
        val record = runCatching { json.decodeFromString<ProfileRecord>(raw) }.getOrNull() ?: return null
        return (UpiProfile.validate(record.name, record.vpa) as? UpiProfileValidation.Valid)?.profile
    }

    companion object {
        const val KEY = "upi_profile"
    }
}
