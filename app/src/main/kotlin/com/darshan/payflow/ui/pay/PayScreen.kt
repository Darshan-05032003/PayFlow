package com.darshan.payflow.ui.pay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.SectionCard
import com.darshan.payflow.ui.components.StateIcon
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding

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
    val blocked = stringResource(R.string.pay_blocked)
    val warning = stringResource(R.string.pay_guard_warning)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayEvent.LaunchUpiApp -> activityLauncher.launch(upiAppLauncher.chooserIntent(event.upiUri))
                PayEvent.NoUpiApp -> snackbar.showSnackbar(noUpiApp)
                is PayEvent.Blocked -> snackbar.showSnackbar(blocked)
                is PayEvent.Warning -> snackbar.showSnackbar(warning)
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
    PayFieldError.VPA_NOT_FOUND -> stringResource(R.string.error_vpa_not_found)
    PayFieldError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_amount)
    PayFieldError.AMOUNT_TOO_LARGE -> stringResource(R.string.error_amount_too_large, Money.formatInr(Payment.MAX_AMOUNT_PAISE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(state: PayUiState, callbacks: PayCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pay_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val phase = state.phase) {
                PayPhase.Idle, PayPhase.Submitting -> PayForm(state, callbacks)
                is PayPhase.AwaitingApp -> StatusBlock(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    colors = PayFlowTheme.status.pending,
                    title = stringResource(R.string.pay_awaiting_app),
                    hint = stringResource(R.string.pay_awaiting_app_hint),
                ) {
                    OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                        Text(stringResource(R.string.pay_check_status))
                    }
                }
                is PayPhase.Confirming -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                    Text(stringResource(R.string.pay_confirming), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.pay_confirming_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is PayPhase.Done -> DoneBlock(phase, callbacks)
                is PayPhase.Unresolved -> StatusBlock(
                    icon = Icons.Outlined.CloudOff,
                    colors = PayFlowTheme.status.failed,
                    title = stringResource(R.string.pay_unresolved),
                    hint = stringResource(R.string.pay_unresolved_hint),
                ) {
                    Button(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                        Text(stringResource(R.string.pay_check_status))
                    }
                    OutlinedButton(onClick = { callbacks.onOpenPayment(phase.paymentId) }) { Text(stringResource(R.string.pay_view_payment)) }
                }
            }
        }
    }
}

@Composable
private fun PayForm(state: PayUiState, callbacks: PayCallbacks) {
    val busy = state.isBusy
    SectionCard(stringResource(R.string.pay_section_payee), icon = Icons.Outlined.Person) {
        OutlinedTextField(
            value = state.vpa, onValueChange = callbacks.onVpaChange, enabled = !busy && !state.payeeLocked, singleLine = true,
            label = { Text(stringResource(R.string.pay_vpa)) },
            placeholder = { Text(stringResource(R.string.pay_vpa_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
            trailingIcon = when {
                state.payeeLocked -> ({ Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_field_locked)) })
                state.verification is VpaVerificationState.Verified -> ({ Icon(Icons.Outlined.Verified, contentDescription = null, tint = PayFlowTheme.status.success.content) })
                else -> null
            },
            isError = state.errors.vpa != null,
            supportingText = {
                val error = state.errors.vpa
                when {
                    error != null -> Text(error.message())
                    state.verification is VpaVerificationState.Verifying -> Text(stringResource(R.string.pay_verifying))
                    state.verification is VpaVerificationState.Verified -> Text(
                        stringResource(R.string.pay_verified, state.verification.payeeName),
                        color = PayFlowTheme.status.success.content,
                        modifier = Modifier.testTag(PayFlowTestTags.PAY_VERIFIED),
                    )
                    state.verification is VpaVerificationState.NotFound -> Text(stringResource(R.string.error_vpa_not_found), color = MaterialTheme.colorScheme.error)
                    state.verification is VpaVerificationState.Unavailable -> Text(stringResource(R.string.pay_verify_unavailable), color = MaterialTheme.colorScheme.tertiary)
                    else -> Text(stringResource(R.string.pay_vpa_hint))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_VPA),
        )
        OutlinedTextField(
            value = state.name, onValueChange = callbacks.onNameChange, enabled = !busy && !state.nameLocked, singleLine = true,
            label = { Text(stringResource(R.string.pay_payee_name)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            trailingIcon = if (state.nameLocked) ({ Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_field_locked)) }) else null,
            isError = state.errors.name != null,
            supportingText = state.errors.name?.let { { Text(it.message()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_NAME),
        )
    }
    SectionCard(stringResource(R.string.pay_section_amount), icon = Icons.Outlined.Notes) {
        OutlinedTextField(
            value = state.amountText, onValueChange = callbacks.onAmountChange, enabled = !busy && !state.amountLocked, singleLine = true,
            label = { Text(stringResource(R.string.pay_amount)) },
            prefix = { Text("₹", style = MaterialTheme.typography.headlineSmall) },
            textStyle = MaterialTheme.typography.headlineSmall,
            trailingIcon = if (state.amountLocked) ({ Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_field_locked)) }) else null,
            isError = state.errors.amount != null,
            supportingText = { Text(state.errors.amount?.message() ?: stringResource(R.string.pay_amount_hint, Money.formatInr(Payment.MAX_AMOUNT_PAISE))) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_AMOUNT),
        )
        OutlinedTextField(
            value = state.note, onValueChange = callbacks.onNoteChange, enabled = !busy, singleLine = true,
            label = { Text(stringResource(R.string.pay_note)) },
            leadingIcon = { Icon(Icons.Outlined.Notes, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_NOTE),
        )
    }
    Button(
        onClick = callbacks.onSubmit,
        enabled = !busy,
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PayFlowTestTags.PAY_SUBMIT),
    ) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.pay_submit), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
    if (busy) LoadingView(Modifier.height(72.dp))
    InfoBanner(stringResource(R.string.pay_sandbox_note), icon = Icons.Outlined.Science, tone = BannerTone.Info)
}

/** Centred status block: a big tinted icon, a title, a hint and the actions for this phase. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBlock(
    icon: ImageVector,
    colors: com.darshan.payflow.ui.theme.StatusColor,
    title: String,
    hint: String?,
    detail: @Composable (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateIcon(icon, container = colors.container, tint = colors.content, modifier = Modifier.size(96.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        detail?.invoke()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) { actions() }
    }
}

@Composable
private fun DoneBlock(phase: PayPhase.Done, callbacks: PayCallbacks) {
    val pending = phase.status == PaymentStatus.PENDING || phase.status == PaymentStatus.CREATED
    val success = phase.status == PaymentStatus.SUCCESS
    val (icon, colors) = when {
        success -> Icons.Outlined.CheckCircle to PayFlowTheme.status.success
        pending -> Icons.Outlined.Schedule to PayFlowTheme.status.pending
        else -> Icons.Outlined.ErrorOutline to PayFlowTheme.status.failed
    }
    val title = when {
        success -> stringResource(R.string.pay_done_success)
        pending -> stringResource(R.string.pay_done_pending)
        else -> stringResource(R.string.pay_done_failed)
    }
    val hint = when {
        success -> stringResource(R.string.pay_done_success_hint)
        pending -> stringResource(R.string.pay_done_pending_hint)
        else -> null
    }
    StatusBlock(
        icon = icon,
        colors = colors,
        title = title,
        hint = hint,
        detail = phase.reason?.let { reason -> { Text(reason, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) } },
    ) {
        if (pending) {
            OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                Text(stringResource(R.string.pay_check_status))
            }
        }
        if (phase.paymentId.isNotEmpty()) {
            OutlinedButton(onClick = { callbacks.onOpenPayment(phase.paymentId) }) { Text(stringResource(R.string.pay_view_payment)) }
        }
        Button(onClick = callbacks.onReset, modifier = Modifier.testTag(PayFlowTestTags.PAY_RESET)) { Text(stringResource(R.string.pay_new_payment)) }
    }
}
