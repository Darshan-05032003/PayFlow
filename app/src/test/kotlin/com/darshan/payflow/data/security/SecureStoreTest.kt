package com.darshan.payflow.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SecureStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var prefs: DataStore<Preferences>
    private val box = InMemoryCryptoBox()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("secure", ".preferences_pb")
        prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() { scope.cancel(); file.delete() }

    @Test
    fun `values are encrypted at rest and readable back`() = runTest {
        val store = EncryptedSecureStore(prefs, box)
        store.put("pin", "super-secret-record")
        assertThat(store.get("pin")).isEqualTo("super-secret-record")
        val raw = prefs.data.first()[stringPreferencesKey("pin")]
        assertThat(raw).isNotNull()
        assertThat(raw).doesNotContain("super-secret")
        assertThat(store.get("missing")).isNull()
        store.remove("pin")
        assertThat(store.get("pin")).isNull()
    }

    @Test
    fun `a tampered or foreign ciphertext reads as missing, never as a different value`() = runTest {
        val store = EncryptedSecureStore(prefs, box)
        store.put("binding", "fp:abc")
        prefs.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey("binding"), "AAAA") }.toPreferences() }
        assertThat(store.get("binding")).isNull()
        val otherKey = EncryptedSecureStore(prefs, InMemoryCryptoBox())
        store.put("binding", "fp:abc")
        assertThat(otherKey.get("binding")).isNull()
    }

    @Test
    fun `keys are namespaced with aad so a value cannot be moved to another key`() = runTest {
        val store = EncryptedSecureStore(prefs, box)
        store.put("a", "value-for-a")
        val rawA = prefs.data.first()[stringPreferencesKey("a")]!!
        prefs.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey("b"), rawA) }.toPreferences() }
        assertThat(store.get("b")).isNull()
    }
}
