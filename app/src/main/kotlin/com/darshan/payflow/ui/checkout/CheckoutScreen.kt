package com.darshan.payflow.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.rememberCheckoutSheet
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.label

data class CheckoutCallbacks(val onBack: () -> Unit = {}, val onBuy: (Product) -> Unit = {}, val onOpenPayment: (String) -> Unit = {})

/** The merchant demo screen: the CheckoutKit sheet is the only payment UI here. */
@Composable
fun CheckoutRoute(checkoutKit: CheckoutKit, onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: CheckoutViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet = rememberCheckoutSheet(checkoutKit, CheckoutSheetOptions(merchantName = CheckoutViewModel.MERCHANT_NAME), onResult = viewModel::onResult)
    CheckoutScreen(
        state,
        CheckoutCallbacks(
            onBack = onBack,
            onBuy = { product ->
                val order = viewModel.startOrder(product)
                sheet.launch(CheckoutRequest(order.orderId, order.amountPaise, description = order.description))
            },
            onOpenPayment = onOpenPayment,
        ),
    )
    CheckoutSheet(sheet)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(state: CheckoutUiState, callbacks: CheckoutCallbacks) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.checkout_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.checkout_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.products.forEach { product ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name, style = MaterialTheme.typography.titleMedium)
                            Text(Money.formatInr(product.pricePaise))
                        }
                        Button(onClick = { callbacks.onBuy(product) }, enabled = state.activeOrder == null && !state.recording, modifier = Modifier.testTag(PayFlowTestTags.checkoutBuy(product.id))) {
                            Text(stringResource(R.string.checkout_buy))
                        }
                    }
                }
            }
            if (state.recording) LoadingView(Modifier.padding(top = 8.dp))
            when (val outcome = state.lastOutcome) {
                null -> Unit
                CheckoutOutcome.Cancelled -> Text(stringResource(R.string.checkout_cancelled), modifier = Modifier.testTag(PayFlowTestTags.CHECKOUT_OUTCOME))
                is CheckoutOutcome.Recorded -> Card(Modifier.fillMaxWidth().testTag(PayFlowTestTags.CHECKOUT_OUTCOME)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(
                                when (outcome.payment.status) {
                                    PaymentStatus.SUCCESS -> R.string.checkout_outcome_success
                                    PaymentStatus.FAILED -> R.string.checkout_outcome_failed
                                    PaymentStatus.REFUNDED -> R.string.checkout_outcome_refunded
                                    else -> R.string.checkout_outcome_pending
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(Money.formatInr(outcome.payment.amountPaise) + " · " + outcome.payment.status.label())
                        outcome.payment.failureReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { callbacks.onOpenPayment(outcome.payment.id) }) { Text(stringResource(R.string.pay_view_payment)) }
                    }
                }
            }
        }
    }
}
