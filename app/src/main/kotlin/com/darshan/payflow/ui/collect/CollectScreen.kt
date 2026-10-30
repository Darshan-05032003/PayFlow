package com.darshan.payflow.ui.collect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.StatusChip
import com.darshan.payflow.ui.components.relativeLabel
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding
import com.darshan.payflow.ui.theme.StatusColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.CollectStatus
import com.darshan.payflow.domain.usecase.CollectActionResult
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class CollectCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onApprove: (String) -> Unit = {},
    val onDecline: (String) -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
)

@Composable
fun CollectRoute(onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: CollectViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messages = CollectMessages(
        approved = stringResource(R.string.collect_msg_approved),
        declined = stringResource(R.string.collect_msg_declined),
        expired = stringResource(R.string.collect_msg_expired),
        already = stringResource(R.string.collect_msg_already),
        unavailable = stringResource(R.string.collect_msg_unavailable),
        refreshFailed = stringResource(R.string.collect_msg_refresh_failed),
    )
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbar.showSnackbar(
                when (event) {
                    CollectEvent.RefreshFailed -> messages.refreshFailed
                    is CollectEvent.Answered -> when (event.result) {
                        is CollectActionResult.Approved -> messages.approved
                        is CollectActionResult.Declined -> messages.declined
                        is CollectActionResult.Expired -> messages.expired
                        is CollectActionResult.AlreadyAnswered -> messages.already
                        is CollectActionResult.Unavailable, CollectActionResult.NotFound -> messages.unavailable
                    }
                },
            )
        }
    }
    CollectScreen(
        state = state,
        callbacks = CollectCallbacks(onBack = onBack, onRefresh = viewModel::refresh, onApprove = viewModel::approve, onDecline = viewModel::decline, onOpenPayment = onOpenPayment),
        snackbarHostState = snackbar,
    )
}

private data class CollectMessages(val approved: String, val declined: String, val expired: String, val already: String, val unavailable: String, val refreshFailed: String)

@Composable
fun CollectStatus.label(): String = stringResource(
    when (this) {
        CollectStatus.PENDING -> R.string.collect_status_pending
        CollectStatus.APPROVED -> R.string.collect_status_approved
        CollectStatus.DECLINED -> R.string.collect_status_declined
        CollectStatus.EXPIRED -> R.string.collect_status_expired
    },
)

@Composable
private fun CollectStatus.statusColor(): StatusColor = when (this) {
    CollectStatus.PENDING -> PayFlowTheme.status.pending
    CollectStatus.APPROVED -> PayFlowTheme.status.success
    CollectStatus.DECLINED -> PayFlowTheme.status.failed
    CollectStatus.EXPIRED -> PayFlowTheme.status.neutral
}

private fun CollectStatus.icon(): ImageVector = when (this) {
    CollectStatus.PENDING -> Icons.Outlined.Schedule
    CollectStatus.APPROVED -> Icons.Outlined.CheckCircle
    CollectStatus.DECLINED -> Icons.Outlined.Cancel
    CollectStatus.EXPIRED -> Icons.Outlined.Timer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(state: CollectUiState, callbacks: CollectCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collect_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = callbacks.onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.collect_refresh)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            CollectUiState.Loading -> LoadingView(Modifier.padding(padding))
            CollectUiState.Empty -> EmptyView(
                stringResource(R.string.collect_empty),
                Modifier.padding(padding),
                stringResource(R.string.collect_empty_hint),
                icon = Icons.Outlined.RequestQuote,
                action = { OutlinedButton(onClick = callbacks.onRefresh) { Text(stringResource(R.string.collect_refresh)) } },
            )
            is CollectUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRefresh, Modifier.padding(padding))
            is CollectUiState.Content -> Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                val pending = state.requests.count { it.request.status == CollectStatus.PENDING }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "intro") {
                        InfoBanner(
                            text = if (pending > 0) pluralStringResource(R.plurals.collect_pending_banner, pending, pending) else stringResource(R.string.collect_none_pending),
                            icon = Icons.Outlined.Info,
                            tone = if (pending > 0) BannerTone.Warning else BannerTone.Info,
                        )
                    }
                    items(state.requests, key = { it.request.id }) { item -> CollectCard(item, callbacks) }
                }
            }
        }
    }
}

@Composable
private fun CollectCard(item: CollectItem, callbacks: CollectCallbacks) {
    val request = item.request
    val pending = request.status == CollectStatus.PENDING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(request.payeeName)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(request.payeeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(request.payeeVpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(Money.formatInr(request.amountPaise), style = MaterialTheme.typography.titleLarge)
            }
            if (request.note.isNotBlank()) {
                Text(request.note, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(request.status.label(), request.status.statusColor(), icon = request.status.icon())
                Text(
                    stringResource(R.string.collect_received, request.createdAt.relativeLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                Text(
                    stringResource(R.string.collect_expires, request.expiresAt.relativeExpiry()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { callbacks.onApprove(request.id) },
                        enabled = !item.busy,
                        modifier = Modifier.weight(1f).testTag(PayFlowTestTags.collectApprove(request.id)),
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.collect_approve), modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = { callbacks.onDecline(request.id) },
                        enabled = !item.busy,
                        modifier = Modifier.weight(1f).testTag(PayFlowTestTags.collectDecline(request.id)),
                    ) { Text(stringResource(R.string.collect_decline)) }
                }
                if (item.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                request.paymentId?.let { id ->
                    TextButton(onClick = { callbacks.onOpenPayment(id) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.pay_view_payment))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

private val expiryFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun java.time.Instant.relativeExpiry(): String = expiryFormatter.format(atZone(ZoneId.systemDefault()))
