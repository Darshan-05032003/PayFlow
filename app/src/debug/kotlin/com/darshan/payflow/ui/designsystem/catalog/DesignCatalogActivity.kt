package com.darshan.payflow.ui.designsystem.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.darshan.payflow.ui.theme.PayFlowTheme

/**
 * Debug-only entry to the design catalog. It lives in `src/debug`, so no class or manifest entry reaches a release build.
 * Open with `adb -s emulator-5554 shell am start -n com.darshan.payflow/.ui.designsystem.catalog.DesignCatalogActivity`.
 */
class DesignCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by rememberSaveable { mutableStateOf(systemDark) }
            var reduceMotion by rememberSaveable { mutableStateOf(false) }
            PayFlowTheme(darkTheme = dark, reduceMotion = reduceMotion) {
                DesignCatalog(
                    dark = dark,
                    onDarkChange = { dark = it },
                    reduceMotion = reduceMotion,
                    onReduceMotionChange = { reduceMotion = it },
                )
            }
        }
    }
}
