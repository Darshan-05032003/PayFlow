package com.darshan.payflow.ui.pay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LoadingView

data class PayCallbacks(
    val onBack: () -> Unit = {},
    val onVpaChange: (String) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onCheckStatus: () -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
    val onReset: () -> Unit = {},
)

@Composable
fun PayRoute(
    upiAppLauncher: UpiAppLauncher,
    onBack: () -> Unit,
    onOpenPayment: (String) -> Unit,
    viewModel: PayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val noUpiApp = stringResource(R.string.pay_no_upi_app)
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onUpiAppResult(upiAppLauncher.parseActivityResult(result.resultCode, result.data))
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayEvent.LaunchUpiApp -> activityLauncher.launch(upiAppLauncher.chooserIntent(event.upiUri))
                PayEvent.NoUpiApp -> snackbar.showSnackbar(noUpiApp)
            }
        }
    }
    PayScreen(
        state = state,
        callbacks = PayCallbacks(
            onBack = onBack,
            onVpaChange = viewModel::onVpaChange,
            onNameChange = viewModel::onNameChange,
            onAmountChange = viewModel::onAmountChange,
            onNoteChange = viewModel::onNoteChange,
            onSubmit = viewModel::submit,
            onCheckStatus = viewModel::checkStatus,
            onOpenPayment = onOpenPayment,
            onReset = viewModel::reset,
        ),
        snackbarHostState = snackbar,
    )
}

@Composable
fun PayFieldError.message(): String = when (this) {
    PayFieldError.REQUIRED -> stringResource(R.string.error_required)
    PayFieldError.INVALID_VPA -> stringResource(R.string.error_invalid_vpa)
    PayFieldError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_amount)
    PayFieldError.AMOUNT_TOO_LARGE -> stringResource(R.string.error_amount_too_large, Money.formatInr(Payment.MAX_AMOUNT_PAISE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(state: PayUiState, callbacks: PayCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pay_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val phase = state.phase) {
                PayPhase.Idle, PayPhase.Submitting -> PayForm(state, callbacks)
                is PayPhase.AwaitingApp -> StatusBlock(stringResource(R.string.pay_awaiting_app), stringResource(R.string.pay_awaiting_app_hint)) {
                    OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                        Text(stringResource(R.string.pay_check_status))
                    }
                }
                is PayPhase.Confirming -> {
                    Text(stringResource(R.string.pay_confirming), style = MaterialTheme.typography.titleMedium)
                    LoadingView(Modifier.padding(top = 32.dp))
                }
                is PayPhase.Done -> DoneBlock(phase, callbacks)
                is PayPhase.Unresolved -> StatusBlock(stringResource(R.string.pay_unresolved), stringResource(R.string.pay_unresolved_hint)) {
                    Button(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                        Text(stringResource(R.string.pay_check_status))
                    }
                    TextButton(onClick = { callbacks.onOpenPayment(phase.paymentId) }) { Text(stringResource(R.string.pay_view_payment)) }
                }
            }
        }
    }
}

@Composable
private fun PayForm(state: PayUiState, callbacks: PayCallbacks) {
    val busy = state.isBusy
    OutlinedTextField(
        value = state.vpa, onValueChange = callbacks.onVpaChange, enabled = !busy, singleLine = true,
        label = { Text(stringResource(R.string.pay_vpa)) },
        isError = state.errors.vpa != null,
        supportingText = state.errors.vpa?.let { { Text(it.message()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_VPA),
    )
    OutlinedTextField(
        value = state.name, onValueChange = callbacks.onNameChange, enabled = !busy, singleLine = true,
        label = { Text(stringResource(R.string.pay_payee_name)) },
        isError = state.errors.name != null,
        supportingText = state.errors.name?.let { { Text(it.message()) } },
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_NAME),
    )
    OutlinedTextField(
        value = state.amountText, onValueChange = callbacks.onAmountChange, enabled = !busy, singleLine = true,
        label = { Text(stringResource(R.string.pay_amount)) },
        prefix = { Text("₹") },
        isError = state.errors.amount != null,
        supportingText = state.errors.amount?.let { { Text(it.message()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_AMOUNT),
    )
    OutlinedTextField(
        value = state.note, onValueChange = callbacks.onNoteChange, enabled = !busy, singleLine = true,
        label = { Text(stringResource(R.string.pay_note)) },
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_NOTE),
    )
    Button(onClick = callbacks.onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_SUBMIT)) {
        Text(stringResource(R.string.pay_submit))
    }
    if (busy) LoadingView(Modifier.padding(top = 16.dp))
    Text(stringResource(R.string.pay_sandbox_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusBlock(title: String, hint: String, actions: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
}

@Composable
private fun DoneBlock(phase: PayPhase.Done, callbacks: PayCallbacks) {
    val title = when (phase.status) {
        PaymentStatus.SUCCESS -> stringResource(R.string.pay_done_success)
        PaymentStatus.PENDING, PaymentStatus.CREATED -> stringResource(R.string.pay_done_pending)
        else -> stringResource(R.string.pay_done_failed)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    phase.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (phase.status == PaymentStatus.PENDING) {
        Text(stringResource(R.string.pay_done_pending_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
            Text(stringResource(R.string.pay_check_status))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase.paymentId.isNotEmpty()) {
            TextButton(onClick = { callbacks.onOpenPayment(phase.paymentId) }) { Text(stringResource(R.string.pay_view_payment)) }
        }
        Button(onClick = callbacks.onReset, modifier = Modifier.testTag(PayFlowTestTags.PAY_RESET)) { Text(stringResource(R.string.pay_new_payment)) }
    }
}
