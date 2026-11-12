package com.darshan.payflow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class PayFlowApp : Application() {
    @Inject lateinit var startup: StartupPrefetch

    /** Lives as long as the process. Only the startup reads run in it. */
    private val processScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startup.start(processScope)
    }
}
