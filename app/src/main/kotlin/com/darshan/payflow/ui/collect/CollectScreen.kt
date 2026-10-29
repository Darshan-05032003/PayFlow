package com.darshan.payflow.ui.collect

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AssistChip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(state: CollectUiState, callbacks: CollectCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            CollectUiState.Loading -> LoadingView(Modifier.padding(padding))
            CollectUiState.Empty -> EmptyView(stringResource(R.string.collect_empty), Modifier.padding(padding), stringResource(R.string.collect_empty_hint))
            is CollectUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRefresh, Modifier.padding(padding))
            is CollectUiState.Content -> Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.requests, key = { it.request.id }) { item -> CollectCard(item, callbacks) }
                }
            }
        }
    }
}

@Composable
private fun CollectCard(item: CollectItem, callbacks: CollectCallbacks) {
    val request = item.request
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(request.payeeName, style = MaterialTheme.typography.titleMedium)
                    Text(request.payeeVpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.formatInr(request.amountPaise), style = MaterialTheme.typography.titleLarge)
            }
            if (request.note.isNotBlank()) Text(request.note, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.collect_received, request.formattedCreatedAt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (request.status) {
                CollectStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { callbacks.onApprove(request.id) },
                        enabled = !item.busy,
                        modifier = Modifier.testTag(PayFlowTestTags.collectApprove(request.id)),
                    ) { Text(stringResource(R.string.collect_approve)) }
                    OutlinedButton(
                        onClick = { callbacks.onDecline(request.id) },
                        enabled = !item.busy,
                        modifier = Modifier.testTag(PayFlowTestTags.collectDecline(request.id)),
                    ) { Text(stringResource(R.string.collect_decline)) }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text(request.status.label()) }, enabled = false)
                    request.paymentId?.let { id ->
                        OutlinedButton(onClick = { callbacks.onOpenPayment(id) }) { Text(stringResource(R.string.pay_view_payment)) }
                    }
                }
            }
        }
    }
}

private val receivedFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun com.darshan.payflow.domain.model.CollectRequest.formattedCreatedAt(): String = receivedFormatter.format(createdAt.atZone(ZoneId.systemDefault()))
