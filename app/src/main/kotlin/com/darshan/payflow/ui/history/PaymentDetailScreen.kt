package com.darshan.payflow.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentDirection
import com.darshan.payflow.domain.model.PaymentMethod
import com.darshan.payflow.ui.components.DetailRow
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.StatusChip
import com.darshan.payflow.ui.components.formattedDate
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.pay.statusSourceLabel
import com.darshan.payflow.ui.theme.ScreenPadding
import kotlinx.coroutines.launch

data class PaymentDetailCallbacks(
    val onBack: () -> Unit = {},
    val onMarkPaid: () -> Unit = {},
    val onMarkNotPaid: () -> Unit = {},
)

@Composable
fun PaymentDetailRoute(onBack: () -> Unit, viewModel: PaymentDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PaymentDetailScreen(
        state,
        PaymentDetailCallbacks(onBack = onBack, onMarkPaid = { viewModel.markManually(paid = true) }, onMarkNotPaid = { viewModel.markManually(paid = false) }),
    )
}

@Composable
fun PaymentMethod.label(): String = stringResource(
    when (this) {
        PaymentMethod.UPI_INTENT -> R.string.method_upi_intent
        PaymentMethod.QR_SCAN -> R.string.method_qr_scan
        PaymentMethod.COLLECT -> R.string.method_collect
        PaymentMethod.MANDATE -> R.string.method_mandate
        PaymentMethod.CHECKOUT -> R.string.method_checkout
        PaymentMethod.DEEP_LINK -> R.string.method_deep_link
    },
)

@Composable
fun PaymentDetailScreen(state: PaymentDetailUiState, onBack: () -> Unit) = PaymentDetailScreen(state, PaymentDetailCallbacks(onBack = onBack))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailScreen(state: PaymentDetailUiState, callbacks: PaymentDetailCallbacks) {
    val onBack = callbacks.onBack
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (state) {
            PaymentDetailUiState.Loading -> LoadingView(Modifier.padding(padding))
            PaymentDetailUiState.NotFound -> EmptyView(stringResource(R.string.payment_not_found), modifier = Modifier.padding(padding), icon = Icons.Outlined.SearchOff)
            is PaymentDetailUiState.Error -> ErrorView(stringResource(R.string.error_generic), onRetry = onBack, Modifier.padding(padding))
            is PaymentDetailUiState.Content -> PaymentDetailContent(state, callbacks, snackbar, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PaymentDetailContent(state: PaymentDetailUiState.Content, callbacks: PaymentDetailCallbacks, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val payment = state.payment
    val outgoing = payment.direction == PaymentDirection.OUTGOING
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InitialAvatar(payment.payeeName, size = 64.dp)
                Text(
                    stringResource(if (outgoing) R.string.payment_to else R.string.payment_from) + " " + payment.payeeName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(payment.payeeVpa, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.formatInr(payment.amountPaise), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp))
                StatusChip(payment.status, withIcon = true)
                Text(
                    statusSourceLabel(payment, state.handlerLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                payment.failureReason?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        }
        if (payment.canBeMarkedManually) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.detail_mark_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.detail_mark_hint, state.handlerLabel ?: payment.handlerPackage.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = callbacks.onMarkPaid, enabled = !state.marking, modifier = Modifier.testTag(PayFlowTestTags.DETAIL_MARK_PAID)) { Text(stringResource(R.string.detail_mark_paid)) }
                        OutlinedButton(onClick = callbacks.onMarkNotPaid, enabled = !state.marking, modifier = Modifier.testTag(PayFlowTestTags.DETAIL_MARK_NOT_PAID)) { Text(stringResource(R.string.detail_mark_not_paid)) }
                    }
                }
            }
        }
        DetailCard(stringResource(R.string.detail_section_details)) {
            if (payment.note.isNotBlank()) DetailRow(stringResource(R.string.detail_note), payment.note)
            DetailRow(stringResource(R.string.detail_method), payment.method.label())
            state.handlerLabel?.let { DetailRow(stringResource(R.string.detail_paid_via), it) }
            DetailRow(stringResource(R.string.detail_created), payment.formattedDate())
        }
        DetailCard(stringResource(R.string.detail_section_references)) {
            CopyableRow(stringResource(R.string.detail_txn_ref), payment.txnRef, snackbar)
            payment.pspTxnId?.let { CopyableRow(stringResource(R.string.detail_psp_txn), it, snackbar) }
            payment.upiTxnId?.let { CopyableRow(stringResource(R.string.detail_upi_txn), it, snackbar) }
            payment.refundId?.let { CopyableRow(stringResource(R.string.detail_refund_id), it, snackbar) }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** A reference line with a copy button; the value is what tests and support staff look for. */
@Composable
private fun CopyableRow(label: String, value: String, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.detail_copied, label)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        DetailRow(label, value, modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(value))
                scope.launch { snackbar.showSnackbar(copied) }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.detail_copy, label), modifier = Modifier.size(18.dp))
        }
    }
}
