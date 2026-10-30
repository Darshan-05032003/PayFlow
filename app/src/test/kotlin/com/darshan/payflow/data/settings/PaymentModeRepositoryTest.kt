package com.darshan.payflow.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.testutil.FakeUpiAppLauncher
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaymentModeRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(launcher: FakeUpiAppLauncher, scope: kotlinx.coroutines.CoroutineScope, file: File = File(tmp.root, "settings.preferences_pb")) =
        DataStorePaymentModeRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, launcher)

    @Test
    fun `defaults to upi apps when one is installed and to sandbox otherwise`() = runTest {
        assertThat(repo(FakeUpiAppLauncher(installed = true), backgroundScope, File(tmp.root, "a.preferences_pb")).current()).isEqualTo(PaymentMode.UPI_APPS)
        assertThat(repo(FakeUpiAppLauncher(installed = false), backgroundScope, File(tmp.root, "b.preferences_pb")).current()).isEqualTo(PaymentMode.SANDBOX)
    }

    @Test
    fun `an explicit choice is persisted and wins over the device default`() = runTest {
        val file = File(tmp.root, "c.preferences_pb")
        val firstScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val first = repo(FakeUpiAppLauncher(installed = true), firstScope, file)
        first.set(PaymentMode.SANDBOX)
        assertThat(first.mode.first()).isEqualTo(PaymentMode.SANDBOX)
        firstScope.cancel() // "process death": the first DataStore is gone
        val second = repo(FakeUpiAppLauncher(installed = true), backgroundScope, file)
        assertThat(second.current()).isEqualTo(PaymentMode.SANDBOX)
        second.set(PaymentMode.UPI_APPS)
        assertThat(second.current()).isEqualTo(PaymentMode.UPI_APPS)
    }

    @Test
    fun `garbage in the preference falls back to the device default`() = runTest {
        val file = File(tmp.root, "d.preferences_pb")
        val prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        prefs.updateData { it.toMutablePreferences().apply { this[DataStorePaymentModeRepository.KEY] = "DROP TABLE" }.toPreferences() }
        assertThat(DataStorePaymentModeRepository(prefs, FakeUpiAppLauncher(installed = false)).current()).isEqualTo(PaymentMode.SANDBOX)
    }
}
