package com.darshan.payflow.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.CoffeeMaker
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.darshan.payflow.BuildConfig
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.SectionHeader
import com.darshan.payflow.ui.components.StatusChip
import com.darshan.payflow.ui.components.icon
import com.darshan.payflow.ui.components.statusColor
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding

data class CheckoutCallbacks(val onBack: () -> Unit = {}, val onBuy: (Product) -> Unit = {}, val onOpenPayment: (String) -> Unit = {})

/** The merchant demo screen: the CheckoutKit sheet is the only payment UI here. */
@Composable
fun CheckoutRoute(checkoutKit: CheckoutKit, onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: CheckoutViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The sheet secures the window itself; debug screenshot mode (see SecureWindow) turns that off too.
    val options = CheckoutSheetOptions(merchantName = CheckoutViewModel.MERCHANT_NAME, secureWindow = !BuildConfig.ALLOW_SCREENSHOTS)
    val sheet = rememberCheckoutSheet(checkoutKit, options, onResult = viewModel::onResult)
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storefront, contentDescription = null, modifier = Modifier.size(40.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(CheckoutViewModel.MERCHANT_NAME, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.checkout_intro), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SectionHeader(stringResource(R.string.checkout_section_products))
            val busy = state.activeOrder != null || state.recording
            state.products.forEach { product -> ProductCard(product, enabled = !busy, onBuy = { callbacks.onBuy(product) }) }
            if (state.recording) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val outcome = state.lastOutcome) {
                null -> Unit
                CheckoutOutcome.Cancelled -> {
                    SectionHeader(stringResource(R.string.checkout_section_outcome))
                    InfoBanner(
                        stringResource(R.string.checkout_cancelled),
                        icon = Icons.Outlined.Cancel,
                        tone = BannerTone.Info,
                        modifier = Modifier.testTag(PayFlowTestTags.CHECKOUT_OUTCOME),
                    )
                }
                is CheckoutOutcome.Recorded -> {
                    SectionHeader(stringResource(R.string.checkout_section_outcome))
                    val payment = outcome.payment
                    val colors = payment.status.statusColor()
                    Card(
                        Modifier.fillMaxWidth().testTag(PayFlowTestTags.CHECKOUT_OUTCOME),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(48.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(payment.status.icon(), contentDescription = null, modifier = Modifier.size(26.dp)) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(
                                        when (payment.status) {
                                            PaymentStatus.SUCCESS -> R.string.checkout_outcome_success
                                            PaymentStatus.FAILED -> R.string.checkout_outcome_failed
                                            PaymentStatus.REFUNDED -> R.string.checkout_outcome_refunded
                                            else -> R.string.checkout_outcome_pending
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(Money.formatInr(payment.amountPaise), style = MaterialTheme.typography.bodyMedium)
                                    StatusChip(payment.status)
                                }
                                payment.failureReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { callbacks.onOpenPayment(payment.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                    Text(stringResource(R.string.pay_view_payment))
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
            InfoBanner(stringResource(R.string.checkout_test_hint), icon = Icons.Outlined.Science, tone = BannerTone.Info)
            Text(
                stringResource(R.string.checkout_powered_by),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ProductCard(product: Product, enabled: Boolean, onBuy: () -> Unit) {
    val icon = when (product.id) {
        "beans" -> Icons.Outlined.Coffee
        "mug" -> Icons.Outlined.LocalCafe
        "grinder" -> Icons.Outlined.CoffeeMaker
        else -> Icons.Outlined.Inventory2
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp)) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium)
                Text(Money.formatInr(product.pricePaise), style = MaterialTheme.typography.bodyLarge, color = PayFlowTheme.status.success.content)
            }
            Button(onClick = onBuy, enabled = enabled, modifier = Modifier.height(40.dp).testTag(PayFlowTestTags.checkoutBuy(product.id))) {
                Text(stringResource(R.string.checkout_buy))
            }
        }
    }
}
