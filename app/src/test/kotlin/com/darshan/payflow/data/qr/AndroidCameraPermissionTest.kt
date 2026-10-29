package com.darshan.payflow.data.qr

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidCameraPermissionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `reflects the runtime permission state`() {
        val permission = AndroidCameraPermission(app)
        assertThat(permission.isGranted()).isFalse()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertThat(permission.isGranted()).isTrue()
    }
}
