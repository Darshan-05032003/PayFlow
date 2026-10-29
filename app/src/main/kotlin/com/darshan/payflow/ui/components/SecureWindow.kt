package com.darshan.payflow.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Marks the hosting window as secure while this composable is in the tree: `FLAG_SECURE` blocks screenshots, screen recording
 * and non-secure displays, and `filterTouchesWhenObscured` on the decor view rejects taps delivered through an overlay
 * (tapjacking). Reference-counted so nested payment screens never clear the flag early.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.let(SecureWindowRegistry::acquire)
        onDispose { window?.let(SecureWindowRegistry::release) }
    }
}

internal object SecureWindowRegistry {
    private val counts = WeakHashMap<Window, Int>()

    @Synchronized
    fun acquire(window: Window) {
        counts[window] = (counts[window] ?: 0) + 1
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
    }

    @Synchronized
    fun release(window: Window) {
        val next = (counts[window] ?: 1) - 1
        if (next <= 0) {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[window] = next
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
