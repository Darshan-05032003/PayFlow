package com.darshan.payflow.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
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
    Surface(modifier = Modifier.fillMaxSize()) {
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
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.lock_enter_pin), color = MaterialTheme.colorScheme.onSurfaceVariant)
        PinDots(state.enteredDigits)
        val message = when {
            lockedOut -> stringResource(R.string.lock_locked_out, (state.lockoutRemainingMillis + 999) / 1_000)
            state.error == LockError.WRONG_PIN -> stringResource(R.string.lock_wrong_pin, state.remainingAttempts)
            state.biometricMessage != null -> state.biometricMessage
            else -> ""
        }
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        Keypad(enabled = !lockedOut, onDigit = callbacks.onDigit, onBackspace = callbacks.onBackspace)
        if (state.biometricAvailable) {
            OutlinedButton(onClick = callbacks.onBiometric, modifier = Modifier.padding(top = 16.dp).testTag(PayFlowTestTags.LOCK_BIOMETRIC)) {
                Icon(Icons.Default.Face, contentDescription = null)
                Text(stringResource(R.string.lock_use_biometric), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SetupContent(state: LockUiState.Setup, callbacks: LockCallbacks) {
    var entered by rememberSaveable(state.step) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(if (state.step == SetupStep.ENTER) R.string.lock_setup_enter else R.string.lock_setup_confirm),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.lock_setup_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        PinDots(entered.length)
        Text(state.error?.message() ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        Keypad(
            enabled = true,
            onDigit = { d ->
                if (entered.length < PinManager.PIN_LENGTH) {
                    entered += d
                    if (entered.length == PinManager.PIN_LENGTH) { callbacks.onSetupPin(entered); entered = "" }
                }
            },
            onBackspace = { entered = entered.dropLast(1) },
        )
        TextButton(onClick = callbacks.onCancelSetup, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.lock_setup_later)) }
    }
}

@Composable
private fun PinDots(count: Int) {
    val description = stringResource(R.string.lock_digits_entered, count, PinManager.PIN_LENGTH)
    Row(Modifier.padding(vertical = 16.dp).semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(PinManager.PIN_LENGTH) { i ->
            Box(
                Modifier.size(14.dp).background(
                    if (i < count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Box(Modifier.size(72.dp))
                        "⌫" -> IconButton(onClick = onBackspace, enabled = enabled, modifier = Modifier.size(72.dp).testTag(PayFlowTestTags.LOCK_BACKSPACE)) {
                            Text("⌫", style = MaterialTheme.typography.titleLarge)
                        }
                        else -> OutlinedButton(onClick = { onDigit(key) }, enabled = enabled, modifier = Modifier.size(72.dp).testTag(PayFlowTestTags.lockKey(key))) {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
