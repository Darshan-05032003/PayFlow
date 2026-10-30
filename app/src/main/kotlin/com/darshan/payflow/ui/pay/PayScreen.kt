package com.darshan.payflow.ui.pay

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.domain.model.StatusSource
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.DetailRow
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SandboxBadge
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.SectionCard
import com.darshan.payflow.ui.components.StateIcon
import com.darshan.payflow.ui.components.formattedDate
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
    val onAppChosen: (String) -> Unit = {},
    val onDismissChooser: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onPayAgain: () -> Unit = {},
    /** "Done" on the result screen: leave the Pay screen. */
    val onDone: () -> Unit = {},
    val onMarkPaid: () -> Unit = {},
    val onMarkNotPaid: () -> Unit = {},
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
    val context = LocalContext.current
    val noUpiApp = stringResource(R.string.pay_no_upi_app)
    val shareTitle = stringResource(R.string.pay_share_receipt)
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onUpiAppResult(upiAppLauncher.parseActivityResult(result.resultCode, result.data))
    }
    val blocked = stringResource(R.string.pay_blocked)
    val warning = stringResource(R.string.pay_guard_warning)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayEvent.LaunchUpiApp -> {
                    val intent = event.packageName?.let { upiAppLauncher.targetedIntent(event.upiUri, it) } ?: upiAppLauncher.chooserIntent(event.upiUri)
                    runCatching { activityLauncher.launch(intent) }.onFailure { snackbar.showSnackbar(noUpiApp) }
                }
                PayEvent.NoUpiApp -> snackbar.showSnackbar(noUpiApp)
                is PayEvent.Blocked -> snackbar.showSnackbar(blocked)
                is PayEvent.Warning -> snackbar.showSnackbar(warning)
                is PayEvent.ShareReceipt -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, event.text)
                    runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                }
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
            onAppChosen = viewModel::onAppChosen,
            onDismissChooser = viewModel::dismissChooser,
            onShare = viewModel::shareReceipt,
            onPayAgain = viewModel::payAgain,
            onDone = { viewModel.reset(); onBack() },
            onMarkPaid = { viewModel.markManually(paid = true) },
            onMarkNotPaid = { viewModel.markManually(paid = false) },
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
                actions = { if (state.paymentMode == PaymentMode.SANDBOX) SandboxBadge(Modifier.padding(end = 12.dp)) },
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
                PayPhase.Idle, PayPhase.Submitting, is PayPhase.ChoosingApp -> PayForm(state, callbacks)
                is PayPhase.AwaitingApp -> StatusBlock(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    colors = PayFlowTheme.status.pending,
                    title = stringResource(R.string.pay_awaiting_app),
                    hint = stringResource(if (state.paymentMode == PaymentMode.UPI_APPS) R.string.pay_awaiting_app_hint_real else R.string.pay_awaiting_app_hint),
                ) {
                    if (state.paymentMode != PaymentMode.UPI_APPS) {
                        OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                            Text(stringResource(R.string.pay_check_status))
                        }
                    }
                }
                is PayPhase.Confirming -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                    Text(
                        stringResource(if (state.paymentMode == PaymentMode.UPI_APPS) R.string.pay_recording else R.string.pay_confirming),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (state.paymentMode != PaymentMode.UPI_APPS) {
                        Text(
                            stringResource(R.string.pay_confirming_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                is PayPhase.Done -> ResultBlock(phase, state.paymentMode, callbacks)
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
    (state.phase as? PayPhase.ChoosingApp)?.let { UpiAppChooserSheet(it.apps, state, callbacks) }
}

/** PayFlow's own "Pay with" sheet: icon + name per installed UPI app, built from the package manager query. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpiAppChooserSheet(apps: List<UpiApp>, state: PayUiState, callbacks: PayCallbacks) {
    ModalBottomSheet(onDismissRequest = callbacks.onDismissChooser, modifier = Modifier.testTag(PayFlowTestTags.PAY_APP_SHEET)) {
        Column(Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.pay_chooser_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.pay_chooser_subtitle, state.amountPaise?.let(Money::formatInr) ?: "", state.vpa),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            apps.forEachIndexed { index, app ->
                val description = stringResource(R.string.pay_chooser_app, app.label)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { callbacks.onAppChosen(app.packageName) }
                        .padding(vertical = 12.dp)
                        .testTag(PayFlowTestTags.payApp(app.packageName))
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bitmap = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
                    } else {
                        StateIcon(Icons.Outlined.AccountBalanceWallet, modifier = Modifier.size(44.dp))
                    }
                    Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp).weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                if (index < apps.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                stringResource(R.string.pay_chooser_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PayForm(state: PayUiState, callbacks: PayCallbacks) {
    val busy = state.isBusy || state.phase is PayPhase.ChoosingApp
    val realMode = state.paymentMode == PaymentMode.UPI_APPS
    AmountDisplay(state.amountPaise)
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
                    state.verification is VpaVerificationState.Skipped -> Text(stringResource(R.string.pay_vpa_hint_real))
                    else -> Text(stringResource(R.string.pay_vpa_hint))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_VPA),
        )
        OutlinedTextField(
            value = state.name, onValueChange = callbacks.onNameChange, enabled = !busy && !state.nameLocked, singleLine = true,
            label = { Text(stringResource(if (realMode) R.string.pay_payee_name_optional else R.string.pay_payee_name)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            trailingIcon = when {
                state.payeeLocked -> ({ Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_field_locked)) })
                state.nameLocked -> ({ Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_name_registered)) })
                else -> null
            },
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
    val label = state.amountPaise?.takeIf { it > 0 }?.let { stringResource(R.string.pay_submit_amount, Money.formatInr(it)) } ?: stringResource(R.string.pay_submit)
    Button(
        onClick = callbacks.onSubmit,
        enabled = !busy,
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PayFlowTestTags.PAY_SUBMIT),
    ) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
    if (state.isBusy) LoadingView(Modifier.height(72.dp))
    when (state.paymentMode) {
        PaymentMode.SANDBOX -> InfoBanner(stringResource(R.string.pay_sandbox_note), icon = Icons.Outlined.Science, tone = BannerTone.Info)
        PaymentMode.UPI_APPS -> InfoBanner(stringResource(R.string.pay_real_note), icon = Icons.Outlined.Info, tone = BannerTone.Info)
        null -> Unit
    }
}

/** The big rupee read-out above the form, with Indian digit grouping (₹1,23,456.78). */
@Composable
private fun AmountDisplay(amountPaise: Long?) {
    val text = amountPaise?.takeIf { it > 0 }?.let(Money::formatInr) ?: stringResource(R.string.pay_amount_placeholder)
    val description = stringResource(R.string.pay_amount_display, text)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).testTag(PayFlowTestTags.PAY_AMOUNT_DISPLAY).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.displayMedium,
            color = if (amountPaise != null && amountPaise > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(stringResource(R.string.pay_amount_display_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

/** Who established the status, in words the user can act on. Never says "verified" for an app-reported or user-marked result. */
@Composable
fun statusSourceLabel(payment: Payment, handlerLabel: String?): String {
    val app = handlerLabel ?: payment.knownHandlerPackage ?: stringResource(R.string.pay_source_app_generic)
    val pending = payment.status == PaymentStatus.PENDING || payment.status == PaymentStatus.CREATED
    return when {
        pending && payment.isUpiAppPayment -> stringResource(R.string.pay_source_unconfirmed, app)
        pending -> stringResource(R.string.pay_source_pending_psp)
        payment.statusSource == StatusSource.UPI_APP -> stringResource(R.string.pay_source_reported_by, app)
        payment.statusSource == StatusSource.USER -> stringResource(R.string.pay_source_marked_by_user)
        else -> stringResource(R.string.pay_source_psp)
    }
}

/**
 * The payment result "receipt": big status icon, amount, payee, who reported the status, references and time; actions share,
 * pay again, view and done. A pending UPI-app payment offers the manual "I paid / not paid" settlement; a pending sandbox payment
 * offers a PSP status check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultBlock(phase: PayPhase.Done, mode: PaymentMode?, callbacks: PayCallbacks) {
    val pending = phase.status == PaymentStatus.PENDING || phase.status == PaymentStatus.CREATED
    val success = phase.status == PaymentStatus.SUCCESS || phase.status == PaymentStatus.REFUND_PENDING || phase.status == PaymentStatus.REFUNDED
    val payment = phase.payment
    val realPayment = payment?.isUpiAppPayment == true
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
        payment != null -> statusSourceLabel(payment, phase.handlerLabel)
        success -> stringResource(R.string.pay_done_success_hint)
        pending -> stringResource(R.string.pay_done_pending_hint)
        else -> null
    }
    StatusBlock(
        icon = icon,
        colors = colors,
        title = title,
        hint = hint,
        detail = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (payment != null) {
                    Text(Money.formatInr(payment.amountPaise), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag(PayFlowTestTags.PAY_RESULT_AMOUNT))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InitialAvatar(payment.payeeName, size = 32.dp)
                        Column {
                            Text(payment.payeeName, style = MaterialTheme.typography.titleMedium)
                            Text(payment.payeeVpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                phase.reason?.let { reason -> Text(reason, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
                if (pending && realPayment) {
                    InfoBanner(stringResource(R.string.pay_pending_real_hint, phase.handlerLabel ?: payment?.knownHandlerPackage ?: stringResource(R.string.pay_source_app_generic)), icon = Icons.Outlined.Info, tone = BannerTone.Warning)
                }
                if (payment != null) {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            DetailRow(stringResource(R.string.detail_txn_ref), payment.txnRef)
                            payment.upiTxnId?.let { DetailRow(stringResource(R.string.detail_upi_txn), it) }
                            payment.pspTxnId?.let { DetailRow(stringResource(R.string.detail_psp_txn), it) }
                            phase.handlerLabel?.let { DetailRow(stringResource(R.string.detail_paid_via), it) }
                            DetailRow(stringResource(R.string.detail_created), payment.formattedDate())
                        }
                    }
                }
            }
        },
    ) {
        if (pending && realPayment) {
            Button(onClick = callbacks.onMarkPaid, modifier = Modifier.testTag(PayFlowTestTags.PAY_MARK_PAID)) { Text(stringResource(R.string.pay_mark_paid)) }
            OutlinedButton(onClick = callbacks.onMarkNotPaid, modifier = Modifier.testTag(PayFlowTestTags.PAY_MARK_NOT_PAID)) { Text(stringResource(R.string.pay_mark_not_paid)) }
        } else if (pending && mode != PaymentMode.UPI_APPS) {
            OutlinedButton(onClick = callbacks.onCheckStatus, modifier = Modifier.testTag(PayFlowTestTags.PAY_CHECK_STATUS)) {
                Text(stringResource(R.string.pay_check_status))
            }
        }
        if (payment != null) {
            OutlinedButton(onClick = callbacks.onShare, modifier = Modifier.testTag(PayFlowTestTags.PAY_SHARE)) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.pay_share_receipt), modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (phase.paymentId.isNotEmpty()) {
            OutlinedButton(onClick = { callbacks.onOpenPayment(phase.paymentId) }) { Text(stringResource(R.string.pay_view_payment)) }
        }
        OutlinedButton(onClick = callbacks.onPayAgain, modifier = Modifier.testTag(PayFlowTestTags.PAY_AGAIN)) { Text(stringResource(R.string.pay_again)) }
        Button(onClick = callbacks.onDone, modifier = Modifier.testTag(PayFlowTestTags.PAY_RESET)) { Text(stringResource(R.string.pay_done)) }
    }
}
