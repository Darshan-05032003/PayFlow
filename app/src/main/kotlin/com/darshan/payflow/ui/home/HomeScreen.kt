package com.darshan.payflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.LocalPaymentMode
import com.darshan.payflow.ui.components.SandboxBadgeIfActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.PaymentRow
import com.darshan.payflow.ui.components.SectionHeader
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding

data class HomeCallbacks(
    val onPaymentClick: (String) -> Unit = {},
    val onViewAll: () -> Unit = {},
    val onPay: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onCollect: () -> Unit = {},
    val onMandates: () -> Unit = {},
    val onSecurity: () -> Unit = {},
    val onCheckout: () -> Unit = {},
    val onReceive: () -> Unit = {},
    val onPayee: (RecentPayee) -> Unit = {},
    val onSetupPin: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun HomeRoute(callbacks: HomeCallbacks, pinConfigured: Boolean = true, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, callbacks = callbacks.copy(onRetry = viewModel::retry), pinConfigured = pinConfigured)
}

/**
 * Dashboard: brand bar, app-lock prompt, "this month" summary card, labelled quick actions, the last five payments
 * and the Pay FAB. Loading / error replace the list area only, so the actions stay reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, callbacks: HomeCallbacks, pinConfigured: Boolean = true) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { BrandTitle() },
                actions = { SandboxBadgeIfActive(Modifier.padding(end = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = callbacks.onPay,
                icon = { Icon(Icons.Default.Send, contentDescription = null) },
                text = { Text(stringResource(R.string.home_pay)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag(PayFlowTestTags.HOME_PAY),
            )
        },
    ) { padding ->
        // A plain scrolling column: at most ~12 children, and every node stays in the tree for accessibility and tests.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!pinConfigured) {
                InfoBanner(
                    text = stringResource(R.string.home_lock_banner),
                    icon = Icons.Outlined.Shield,
                    tone = BannerTone.Warning,
                    modifier = Modifier.testTag(PayFlowTestTags.HOME_LOCK_BANNER),
                    action = { TextButton(onClick = callbacks.onSetupPin) { Text(stringResource(R.string.home_lock_setup)) } },
                )
            }
            SummaryCard(
                month = (state as? HomeUiState.Content)?.month ?: HistorySummary.EMPTY,
                allTime = (state as? HomeUiState.Content)?.summary ?: HistorySummary.EMPTY,
            )
            SectionHeader(stringResource(R.string.home_quick_actions))
            QuickActions(callbacks)
            val payees = (state as? HomeUiState.Content)?.recentPayees.orEmpty()
            if (payees.isNotEmpty()) {
                SectionHeader(stringResource(R.string.home_recent_payees))
                RecentPayeesRow(payees, callbacks.onPayee)
            }
            SectionHeader(
                stringResource(R.string.home_recent),
                actionLabel = if (state is HomeUiState.Content) stringResource(R.string.home_view_all) else null,
                onAction = callbacks.onViewAll,
            )
            RecentSection(state, callbacks)
            val sandbox = LocalPaymentMode.current == PaymentMode.SANDBOX
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                Icon(if (sandbox) Icons.Outlined.Science else Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(if (sandbox) R.string.home_sandbox_footer else R.string.home_real_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CurrencyRupee, contentDescription = null, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryCard(month: HistorySummary, allTime: HistorySummary) {
    val hero = PayFlowTheme.status.hero
    val summaryLine = stringResource(R.string.home_summary_line, month.success, month.pending, month.failed)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = hero.content),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(hero.start, hero.end)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.home_sent_this_month), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Text(Money.formatInr(month.sentPaise), style = MaterialTheme.typography.displaySmall)
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(summaryLine)
            }
            Text(
                stringResource(R.string.home_all_time, Money.formatInr(allTime.sentPaise), allTime.total),
                style = MaterialTheme.typography.bodySmall,
                color = hero.content.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(color = Color.White.copy(alpha = 0.16f), contentColor = PayFlowTheme.status.hero.content, shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

private data class QuickAction(val label: Int, val icon: ImageVector, val tag: String, val onClick: () -> Unit)

/** Two rows of four, like the action grid of the big UPI apps. */
@Composable
private fun QuickActions(callbacks: HomeCallbacks) {
    val actions = listOf(
        QuickAction(R.string.home_action_pay, Icons.Default.Send, "${PayFlowTestTags.HOME_PAY}:tile", callbacks.onPay),
        QuickAction(R.string.home_action_receive, Icons.Outlined.QrCode2, PayFlowTestTags.HOME_RECEIVE, callbacks.onReceive),
        QuickAction(R.string.home_action_scan, Icons.Outlined.QrCodeScanner, PayFlowTestTags.HOME_SCAN, callbacks.onScan),
        QuickAction(R.string.home_action_requests, Icons.Outlined.RequestQuote, PayFlowTestTags.HOME_COLLECT, callbacks.onCollect),
        QuickAction(R.string.home_action_autopay, Icons.Outlined.EventRepeat, PayFlowTestTags.HOME_MANDATES, callbacks.onMandates),
        QuickAction(R.string.home_action_checkout, Icons.Outlined.ShoppingBag, PayFlowTestTags.HOME_CHECKOUT, callbacks.onCheckout),
        QuickAction(R.string.home_action_history, Icons.Outlined.History, PayFlowTestTags.HOME_HISTORY, callbacks.onViewAll),
        QuickAction(R.string.home_action_security, Icons.Outlined.Shield, PayFlowTestTags.HOME_SECURITY, callbacks.onSecurity),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ActionTile(it) } }
        }
    }
}

/** Horizontally scrolling avatars of people paid before; a tap opens Pay with the payee prefilled. */
@Composable
private fun RecentPayeesRow(payees: List<RecentPayee>, onPayee: (RecentPayee) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.HOME_PAYEES),
    ) {
        items(payees, key = { it.vpa }) { payee ->
            val description = stringResource(R.string.home_payee_description, payee.name, payee.vpa)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(68.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onPayee(payee) }
                    .padding(vertical = 4.dp)
                    .testTag(PayFlowTestTags.homePayee(payee.vpa))
                    .semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                InitialAvatar(payee.name, size = 52.dp)
                Text(
                    payee.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.ActionTile(action: QuickAction) {
    val label = stringResource(action.label)
    Card(
        onClick = action.onClick,
        modifier = Modifier.weight(1f).testTag(action.tag).semantics(mergeDescendants = true) { contentDescription = label },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = CircleShape, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RecentSection(state: HomeUiState, callbacks: HomeCallbacks) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        when (state) {
            HomeUiState.Loading -> LoadingView(Modifier.height(180.dp))
            HomeUiState.Empty -> EmptyView(
                title = stringResource(R.string.home_empty_title),
                subtitle = stringResource(R.string.home_empty_subtitle),
                icon = Icons.Outlined.ReceiptLong,
                fillHeight = false,
                modifier = Modifier.fillMaxWidth(),
            )
            is HomeUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry, Modifier.height(240.dp))
            is HomeUiState.Content -> Column {
                state.recent.forEachIndexed { index, payment ->
                    PaymentRow(payment, onClick = callbacks.onPaymentClick)
                    if (index < state.recent.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
