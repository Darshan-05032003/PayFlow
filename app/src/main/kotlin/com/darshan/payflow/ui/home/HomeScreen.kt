package com.darshan.payflow.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.darshan.payflow.ui.PayFlowTestTags
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.HistorySummary
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.PaymentRow

data class HomeCallbacks(
    val onPaymentClick: (String) -> Unit = {},
    val onViewAll: () -> Unit = {},
    val onPay: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun HomeRoute(callbacks: HomeCallbacks, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, callbacks = callbacks.copy(onRetry = viewModel::retry))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, callbacks: HomeCallbacks) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = callbacks.onScan, modifier = Modifier.testTag(PayFlowTestTags.HOME_SCAN)) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.scan_title))
                    }
                    IconButton(onClick = callbacks.onViewAll) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.history_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = callbacks.onPay,
                icon = { Icon(Icons.Default.Send, contentDescription = null) },
                text = { Text(stringResource(R.string.home_pay)) },
                modifier = Modifier.testTag(PayFlowTestTags.HOME_PAY),
            )
        },
    ) { padding ->
        when (state) {
            HomeUiState.Loading -> LoadingView(Modifier.padding(padding))
            HomeUiState.Empty -> EmptyView(
                title = stringResource(R.string.home_empty_title),
                subtitle = stringResource(R.string.home_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            is HomeUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry, Modifier.padding(padding))
            is HomeUiState.Content -> HomeContent(state, callbacks, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(state: HomeUiState.Content, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { SummaryCard(state.summary) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = callbacks.onViewAll) { Text(stringResource(R.string.home_view_all)) }
            }
        }
        items(state.recent, key = { it.id }) { payment ->
            PaymentRow(payment, onClick = callbacks.onPaymentClick)
            HorizontalDivider()
        }
    }
}

@Composable
private fun SummaryCard(summary: HistorySummary) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_sent), style = MaterialTheme.typography.labelMedium)
            Text(Money.formatInr(summary.sentPaise), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.home_summary_line, summary.success, summary.pending, summary.failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
