package com.darshan.payflow.di

import com.darshan.payflow.BuildConfig
import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.HttpPspClient
import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.qr.AndroidCameraPermission
import com.darshan.payflow.data.qr.BarcodeScanner
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.qr.CameraXController
import com.darshan.payflow.data.qr.MlKitBarcodeScanner
import com.darshan.payflow.data.upi.AndroidUpiAppLauncher
import com.darshan.payflow.data.upi.UpiAppLauncher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object PspModule {
    /**
     * Sandbox by default: `FakePsp` with realistic latency and one pending poll. When `PSP_BASE_URL` is set in
     * local.properties (and is https), the OkHttp client talks to that server instead.
     */
    @Provides @Singleton
    fun pspClient(clock: Clock): PspClient {
        val url = BuildConfig.PSP_BASE_URL.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        return if (url != null && url.isHttps) {
            val ok = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            HttpPspClient(url, ok, Json { ignoreUnknownKeys = true })
        } else {
            FakePsp(clock, FakePspConfig(latencyMillis = 400, pendingPolls = 1))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpiModule {
    @Binds @Singleton
    abstract fun upiAppLauncher(impl: AndroidUpiAppLauncher): UpiAppLauncher
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {
    @Binds @Singleton
    abstract fun barcodeScanner(impl: MlKitBarcodeScanner): BarcodeScanner

    @Binds
    abstract fun cameraController(impl: CameraXController): CameraController

    @Binds
    abstract fun cameraPermission(impl: AndroidCameraPermission): CameraPermission
}
