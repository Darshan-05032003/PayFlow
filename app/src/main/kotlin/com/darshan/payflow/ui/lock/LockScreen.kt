package com.darshan.payflow.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.StateIcon
import kotlinx.coroutines.delay

data class LockCallbacks(
    val onDigit: (String) -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onBiometric: () -> Unit = {},
    val onTick: () -> Unit = {},
    val onSetupPin: (String) -> Unit = {},
    val onCancelSetup: () -> Unit = {},
)

@Composable
fun PinRule.message(): String = stringResource(
    when (this) {
        PinRule.Ok -> R.string.lock_rule_ok
        PinRule.WrongLength -> R.string.lock_rule_length
        PinRule.NotDigits -> R.string.lock_rule_digits
        PinRule.TooSimple -> R.string.lock_rule_simple
        PinRule.Mismatch -> R.string.lock_rule_mismatch
    },
)

/** Full-screen lock / PIN setup. Always a secure window. */
@Composable
fun LockScreen(state: LockUiState, callbacks: LockCallbacks) {
    SecureWindow()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (state) {
            LockUiState.Checking -> LoadingView()
            is LockUiState.Unlocked -> Unit
            is LockUiState.Setup -> SetupContent(state, callbacks)
            is LockUiState.Locked -> LockedContent(state, callbacks)
        }
    }
}

@Composable
private fun LockedContent(state: LockUiState.Locked, callbacks: LockCallbacks) {
    val lockedOut = state.lockoutRemainingMillis > 0
    if (lockedOut) LaunchedEffect(state.lockoutRemainingMillis) { delay(1_000); callbacks.onTick() }
    val message = when {
        lockedOut -> stringResource(R.string.lock_locked_out, (state.lockoutRemainingMillis + 999) / 1_000)
        state.error == LockError.WRONG_PIN -> stringResource(R.string.lock_wrong_pin, state.remainingAttempts)
        state.biometricMessage != null -> state.biometricMessage
        else -> ""
    }
    PinFrame(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.lock_title),
        subtitle = stringResource(R.string.lock_enter_pin),
        dots = state.enteredDigits,
        message = message,
        keypadEnabled = !lockedOut,
        onDigit = callbacks.onDigit,
        onBackspace = callbacks.onBackspace,
    ) {
        if (state.biometricAvailable) {
            OutlinedButton(onClick = callbacks.onBiometric, modifier = Modifier.padding(top = 8.dp).testTag(PayFlowTestTags.LOCK_BIOMETRIC)) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Text(stringResource(R.string.lock_use_biometric), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SetupContent(state: LockUiState.Setup, callbacks: LockCallbacks) {
    var entered by rememberSaveable(state.step) { mutableStateOf("") }
    PinFrame(
        icon = Icons.Outlined.LockOpen,
        title = stringResource(if (state.step == SetupStep.ENTER) R.string.lock_setup_enter else R.string.lock_setup_confirm),
        subtitle = stringResource(if (state.step == SetupStep.ENTER) R.string.lock_setup_hint else R.string.lock_setup_confirm_hint),
        dots = entered.length,
        message = state.error?.message() ?: "",
        keypadEnabled = true,
        onDigit = { d ->
            if (entered.length < PinManager.PIN_LENGTH) {
                entered += d
                if (entered.length == PinManager.PIN_LENGTH) { callbacks.onSetupPin(entered); entered = "" }
            }
        },
        onBackspace = { entered = entered.dropLast(1) },
    ) {
        TextButton(onClick = callbacks.onCancelSetup, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.lock_setup_later)) }
    }
}

/** Shared layout: brand icon, title, dots, message, keypad and an extra action (biometrics / skip). */
@Composable
private fun PinFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    dots: Int,
    message: String,
    keypadEnabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    extra: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIcon(icon, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        PinDots(dots)
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp).height(40.dp),
        )
        Keypad(enabled = keypadEnabled, onDigit = onDigit, onBackspace = onBackspace)
        Spacer(Modifier.height(8.dp))
        extra()
    }
}

@Composable
private fun PinDots(count: Int) {
    val description = stringResource(R.string.lock_digits_entered, count, PinManager.PIN_LENGTH)
    Row(Modifier.padding(vertical = 20.dp).semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(PinManager.PIN_LENGTH) { i ->
            Box(
                Modifier.size(16.dp).background(
                    if (i < count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Box(Modifier.size(72.dp))
                        "⌫" -> IconButton(onClick = onBackspace, enabled = enabled, modifier = Modifier.size(72.dp).testTag(PayFlowTestTags.LOCK_BACKSPACE)) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.lock_backspace))
                        }
                        else -> FilledTonalButton(
                            onClick = { onDigit(key) },
                            enabled = enabled,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp).testTag(PayFlowTestTags.lockKey(key)),
                        ) {
                            Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}
