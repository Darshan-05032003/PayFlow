package com.darshan.payflow.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow

data class SecurityCallbacks(
    val onBack: () -> Unit = {},
    val onPhoneChange: (String) -> Unit = {},
    val onRequestOtp: () -> Unit = {},
    val onOtpChange: (String) -> Unit = {},
    val onVerifyOtp: () -> Unit = {},
    val onUnbind: () -> Unit = {},
    val onCheckIntegrity: () -> Unit = {},
    val onSetupPin: () -> Unit = {},
    val onRemovePin: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

@Composable
fun SecurityRoute(onBack: () -> Unit, onSetupPin: () -> Unit, viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SecurityScreen(
        state,
        SecurityCallbacks(
            onBack = onBack, onPhoneChange = viewModel::onPhoneChange, onRequestOtp = viewModel::requestOtp, onOtpChange = viewModel::onOtpChange,
            onVerifyOtp = viewModel::verifyOtp, onUnbind = viewModel::unbind, onCheckIntegrity = viewModel::checkIntegrity,
            onSetupPin = onSetupPin, onRemovePin = viewModel::removePin, onRefresh = viewModel::refresh,
        ),
    )
}

@Composable
fun TamperFinding.label(): String = stringResource(
    when (this) {
        TamperFinding.SU_BINARY -> R.string.tamper_su
        TamperFinding.MAGISK -> R.string.tamper_magisk
        TamperFinding.TEST_KEYS -> R.string.tamper_test_keys
        TamperFinding.FRIDA_PORT, TamperFinding.FRIDA_LIBRARY -> R.string.tamper_frida
        TamperFinding.DEBUGGER -> R.string.tamper_debugger
        TamperFinding.DEBUGGABLE -> R.string.tamper_debuggable
        TamperFinding.SIGNATURE_MISMATCH -> R.string.tamper_signature
        TamperFinding.SIGNATURE_UNREADABLE -> R.string.tamper_signature_unreadable
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(state: SecurityUiState, callbacks: SecurityCallbacks) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingView(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section(stringResource(R.string.security_pin_title)) {
                if (state.pinConfigured) {
                    Text(stringResource(R.string.security_pin_on))
                    OutlinedButton(onClick = callbacks.onRemovePin, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_REMOVE_PIN)) { Text(stringResource(R.string.security_pin_remove)) }
                } else {
                    Text(stringResource(R.string.security_pin_off), color = MaterialTheme.colorScheme.error)
                    Button(onClick = callbacks.onSetupPin, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SETUP_PIN)) { Text(stringResource(R.string.security_pin_setup)) }
                }
            }
            Section(stringResource(R.string.security_binding_title)) { BindingSection(state, callbacks) }
            Section(stringResource(R.string.security_tamper_title)) {
                val report = state.tamper
                when {
                    report == null -> Text(stringResource(R.string.loading))
                    report.findings.isEmpty() -> Text(stringResource(R.string.security_tamper_clean), color = MaterialTheme.colorScheme.primary)
                    else -> report.findings.forEach { Text("• " + it.label(), color = MaterialTheme.colorScheme.error) }
                }
                if (report?.shouldBlockPayments == true) Text(stringResource(R.string.security_tamper_blocked), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = callbacks.onRefresh) { Text(stringResource(R.string.security_rescan)) }
            }
            Section(stringResource(R.string.security_integrity_title)) {
                Text(
                    when (val s = state.integrity) {
                        IntegrityStatus.NotChecked -> stringResource(R.string.security_integrity_not_checked)
                        IntegrityStatus.Checking -> stringResource(R.string.loading)
                        IntegrityStatus.NotConfigured -> stringResource(R.string.security_integrity_not_configured)
                        is IntegrityStatus.Error -> stringResource(R.string.security_integrity_error, s.code)
                        is IntegrityStatus.Result -> when (s.verdict) {
                            IntegrityVerdict.MEETS_DEVICE_INTEGRITY -> stringResource(R.string.security_integrity_ok)
                            IntegrityVerdict.FAILED -> stringResource(R.string.security_integrity_failed)
                            IntegrityVerdict.UNEVALUATED -> stringResource(R.string.security_integrity_unevaluated)
                        } + if (s.sandboxVerifier) " " + stringResource(R.string.security_integrity_sandbox) else ""
                    },
                )
                Button(onClick = callbacks.onCheckIntegrity, enabled = state.integrity != IntegrityStatus.Checking, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_INTEGRITY)) {
                    Text(stringResource(R.string.security_integrity_check))
                }
            }
        }
    }
}

@Composable
private fun BindingSection(state: SecurityUiState, callbacks: SecurityCallbacks) {
    when (val b = state.binding) {
        is BindingStatus.Bound -> {
            Text(stringResource(R.string.security_bound, b.maskedPhone))
            OutlinedButton(onClick = callbacks.onUnbind, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_UNBIND)) { Text(stringResource(R.string.security_unbind)) }
        }
        is BindingStatus.Mismatch -> {
            Text(stringResource(R.string.security_binding_mismatch, b.maskedPhone), color = MaterialTheme.colorScheme.error)
            Button(onClick = callbacks.onUnbind) { Text(stringResource(R.string.security_rebind)) }
        }
        is BindingStatus.OtpPending -> {
            Text(stringResource(R.string.security_otp_sent, b.maskedPhone))
            state.sandboxOtp?.let { Text(stringResource(R.string.security_sandbox_otp, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SANDBOX_OTP)) }
            OutlinedTextField(
                value = state.otpInput, onValueChange = callbacks.onOtpChange, singleLine = true, enabled = !state.busy,
                label = { Text(stringResource(R.string.security_otp)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = state.bindingError != null,
                supportingText = state.bindingError?.let { { Text(stringResource(if (it == BindingError.WRONG_OTP) R.string.security_otp_wrong else R.string.security_otp_expired)) } },
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_OTP),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = callbacks.onVerifyOtp, enabled = !state.busy && state.otpInput.length == 6, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_VERIFY_OTP)) { Text(stringResource(R.string.security_verify)) }
                OutlinedButton(onClick = callbacks.onRequestOtp, enabled = !state.busy) { Text(stringResource(R.string.security_resend)) }
            }
        }
        BindingStatus.Unbound -> {
            Text(stringResource(R.string.security_unbound))
            OutlinedTextField(
                value = state.phoneInput, onValueChange = callbacks.onPhoneChange, singleLine = true, enabled = !state.busy,
                label = { Text(stringResource(R.string.security_phone)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = state.bindingError == BindingError.INVALID_PHONE,
                supportingText = if (state.bindingError == BindingError.INVALID_PHONE) { { Text(stringResource(R.string.security_phone_invalid)) } } else null,
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_PHONE),
            )
            Button(onClick = callbacks.onRequestOtp, enabled = !state.busy, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SEND_OTP)) { Text(stringResource(R.string.security_send_otp)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
