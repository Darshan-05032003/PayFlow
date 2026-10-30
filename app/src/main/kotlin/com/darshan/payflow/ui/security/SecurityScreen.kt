package com.darshan.payflow.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.SectionCard
import com.darshan.payflow.ui.components.StatusChip
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingView(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecurityOverview(state)
            SectionCard(stringResource(R.string.security_pin_title), icon = Icons.Outlined.Fingerprint, trailing = { OnOffChip(state.pinConfigured) }) {
                if (state.pinConfigured) {
                    StatusLine(stringResource(R.string.security_pin_on), ok = true)
                    OutlinedButton(onClick = callbacks.onRemovePin, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_REMOVE_PIN)) { Text(stringResource(R.string.security_pin_remove)) }
                } else {
                    StatusLine(stringResource(R.string.security_pin_off), ok = false)
                    Button(onClick = callbacks.onSetupPin, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SETUP_PIN)) { Text(stringResource(R.string.security_pin_setup)) }
                }
            }
            SectionCard(stringResource(R.string.security_binding_title), icon = Icons.Outlined.PhonelinkLock, trailing = { OnOffChip(state.binding is BindingStatus.Bound) }) {
                BindingSection(state, callbacks)
            }
            SectionCard(stringResource(R.string.security_tamper_title), icon = Icons.Outlined.Shield, trailing = { state.tamper?.let { OnOffChip(it.findings.isEmpty()) } }) {
                val report = state.tamper
                when {
                    report == null -> Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    report.findings.isEmpty() -> StatusLine(stringResource(R.string.security_tamper_clean), ok = true)
                    else -> report.findings.forEach { StatusLine(it.label(), ok = false) }
                }
                if (report?.shouldBlockPayments == true) {
                    InfoBanner(stringResource(R.string.security_tamper_blocked), icon = Icons.Outlined.Block, tone = BannerTone.Error)
                } else if (report != null && report.findings.isNotEmpty()) {
                    Text(stringResource(R.string.security_tamper_debug_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = callbacks.onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.security_rescan), modifier = Modifier.padding(start = 6.dp))
                }
            }
            SectionCard(stringResource(R.string.security_integrity_title), icon = Icons.Outlined.VerifiedUser) {
                val s = state.integrity
                val ok: Boolean? = when (s) {
                    is IntegrityStatus.Result -> s.verdict == IntegrityVerdict.MEETS_DEVICE_INTEGRITY
                    is IntegrityStatus.Error -> false
                    else -> null
                }
                val text = when (s) {
                    IntegrityStatus.NotChecked -> stringResource(R.string.security_integrity_not_checked)
                    IntegrityStatus.Checking -> stringResource(R.string.loading)
                    IntegrityStatus.NotConfigured -> stringResource(R.string.security_integrity_not_configured)
                    is IntegrityStatus.Error -> stringResource(R.string.security_integrity_error, s.code)
                    is IntegrityStatus.Result -> when (s.verdict) {
                        IntegrityVerdict.MEETS_DEVICE_INTEGRITY -> stringResource(R.string.security_integrity_ok)
                        IntegrityVerdict.FAILED -> stringResource(R.string.security_integrity_failed)
                        IntegrityVerdict.UNEVALUATED -> stringResource(R.string.security_integrity_unevaluated)
                    } + if (s.sandboxVerifier) " " + stringResource(R.string.security_integrity_sandbox) else ""
                }
                if (ok == null) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) else StatusLine(text, ok = ok)
                if (s == IntegrityStatus.Checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = callbacks.onCheckIntegrity, enabled = s != IntegrityStatus.Checking, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_INTEGRITY)) {
                    Text(stringResource(R.string.security_integrity_check))
                }
            }
            InfoBanner(stringResource(R.string.security_sandbox_note), icon = Icons.Outlined.Science, tone = BannerTone.Info)
        }
    }
}

/** Headline card: how many of the four protections are active. */
@Composable
private fun SecurityOverview(state: SecurityUiState) {
    val checks = listOf(
        state.pinConfigured,
        state.binding is BindingStatus.Bound,
        state.tamper?.findings?.isEmpty() == true,
        (state.integrity as? IntegrityStatus.Result)?.verdict == IntegrityVerdict.MEETS_DEVICE_INTEGRITY,
    )
    val active = checks.count { it }
    val colors = when {
        active == checks.size -> PayFlowTheme.status.success
        active >= 2 -> PayFlowTheme.status.pending
        else -> PayFlowTheme.status.failed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(40.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(R.string.security_overview_title, active, checks.size), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.security_overview_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun OnOffChip(on: Boolean) {
    val colors = if (on) PayFlowTheme.status.success else PayFlowTheme.status.pending
    StatusChip(stringResource(if (on) R.string.security_state_on else R.string.security_state_off), colors)
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    val colors = if (ok) PayFlowTheme.status.success else PayFlowTheme.status.failed
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(20.dp).padding(top = 1.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun BindingSection(state: SecurityUiState, callbacks: SecurityCallbacks) {
    when (val b = state.binding) {
        is BindingStatus.Bound -> {
            StatusLine(stringResource(R.string.security_bound, b.maskedPhone), ok = true)
            OutlinedButton(onClick = callbacks.onUnbind, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_UNBIND)) { Text(stringResource(R.string.security_unbind)) }
        }
        is BindingStatus.Mismatch -> {
            StatusLine(stringResource(R.string.security_binding_mismatch, b.maskedPhone), ok = false)
            Button(onClick = callbacks.onUnbind) { Text(stringResource(R.string.security_rebind)) }
        }
        is BindingStatus.OtpPending -> {
            Text(stringResource(R.string.security_otp_sent, b.maskedPhone), style = MaterialTheme.typography.bodyMedium)
            state.sandboxOtp?.let {
                InfoBanner(
                    stringResource(R.string.security_sandbox_otp, it),
                    icon = Icons.Outlined.Sms,
                    tone = BannerTone.Warning,
                    modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SANDBOX_OTP),
                )
            }
            OutlinedTextField(
                value = state.otpInput, onValueChange = callbacks.onOtpChange, singleLine = true, enabled = !state.busy,
                label = { Text(stringResource(R.string.security_otp)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                leadingIcon = { Icon(Icons.Outlined.Password, contentDescription = null) },
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
            Text(stringResource(R.string.security_unbound), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.phoneInput, onValueChange = callbacks.onPhoneChange, singleLine = true, enabled = !state.busy,
                label = { Text(stringResource(R.string.security_phone)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.security_phone_placeholder)) },
                isError = state.bindingError == BindingError.INVALID_PHONE,
                supportingText = if (state.bindingError == BindingError.INVALID_PHONE) { { Text(stringResource(R.string.security_phone_invalid)) } } else null,
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_PHONE),
            )
            Button(onClick = callbacks.onRequestOtp, enabled = !state.busy, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_SEND_OTP)) { Text(stringResource(R.string.security_send_otp)) }
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}
