package com.darshan.payflow.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.color
import com.darshan.payflow.ui.components.formattedDate
import com.darshan.payflow.ui.components.label

@Composable
fun PaymentDetailRoute(onBack: () -> Unit, viewModel: PaymentDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PaymentDetailScreen(state, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailScreen(state: PaymentDetailUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            PaymentDetailUiState.Loading -> LoadingView(Modifier.padding(padding))
            PaymentDetailUiState.NotFound -> EmptyView(stringResource(R.string.payment_not_found), modifier = Modifier.padding(padding))
            is PaymentDetailUiState.Error -> ErrorView(stringResource(R.string.error_generic), onRetry = onBack, Modifier.padding(padding))
            is PaymentDetailUiState.Content -> PaymentDetailContent(state.payment, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PaymentDetailContent(payment: Payment, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Money.formatInr(payment.amountPaise), style = MaterialTheme.typography.displaySmall)
        Text(payment.status.label(), color = payment.status.color(), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DetailRow(stringResource(R.string.detail_payee), payment.payeeName)
        DetailRow(stringResource(R.string.detail_vpa), payment.payeeVpa)
        if (payment.note.isNotBlank()) DetailRow(stringResource(R.string.detail_note), payment.note)
        DetailRow(stringResource(R.string.detail_method), payment.method.name.lowercase().replace('_', ' '))
        DetailRow(stringResource(R.string.detail_created), payment.formattedDate())
        DetailRow(stringResource(R.string.detail_txn_ref), payment.txnRef)
        payment.pspTxnId?.let { DetailRow(stringResource(R.string.detail_psp_txn), it) }
        payment.upiTxnId?.let { DetailRow(stringResource(R.string.detail_upi_txn), it) }
        payment.refundId?.let { DetailRow(stringResource(R.string.detail_refund_id), it) }
        payment.failureReason?.let { DetailRow(stringResource(R.string.detail_failure_reason), it) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp).weight(1f, fill = false))
    }
}
