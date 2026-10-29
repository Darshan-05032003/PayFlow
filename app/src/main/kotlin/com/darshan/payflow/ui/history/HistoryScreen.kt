package com.darshan.payflow.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.PaymentRow

data class HistoryCallbacks(
    val onBack: () -> Unit = {},
    val onFilter: (HistoryFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPaymentClick: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun HistoryRoute(onBack: () -> Unit, onPaymentClick: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        callbacks = HistoryCallbacks(
            onBack = onBack,
            onFilter = viewModel::setFilter,
            onQuery = viewModel::setQuery,
            onPaymentClick = onPaymentClick,
            onRetry = viewModel::retry,
        ),
    )
}

@Composable
fun HistoryFilter.label(): String = stringResource(
    when (this) {
        HistoryFilter.ALL -> R.string.filter_all
        HistoryFilter.SUCCESS -> R.string.filter_success
        HistoryFilter.PENDING -> R.string.filter_pending
        HistoryFilter.FAILED -> R.string.filter_failed
        HistoryFilter.REFUNDED -> R.string.filter_refunded
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(state: HistoryUiState, callbacks: HistoryCallbacks) {
    val selectedFilter = when (state) {
        is HistoryUiState.Content -> state.filter
        is HistoryUiState.Empty -> state.filter
        else -> HistoryFilter.ALL
    }
    val currentQuery = when (state) {
        is HistoryUiState.Content -> state.query
        is HistoryUiState.Empty -> state.query
        else -> ""
    }
    var text by rememberSaveable(currentQuery) { mutableStateOf(currentQuery) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; callbacks.onQuery(it) },
                label = { Text(stringResource(R.string.history_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(PayFlowTestTags.SEARCH_FIELD),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == selectedFilter,
                        onClick = { callbacks.onFilter(filter) },
                        label = { Text(filter.label()) },
                        modifier = Modifier.testTag(PayFlowTestTags.filterChip(filter)),
                    )
                }
            }
            when (state) {
                HistoryUiState.Loading -> LoadingView()
                is HistoryUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry)
                is HistoryUiState.Empty -> EmptyView(
                    title = if (state.query.isNotBlank()) {
                        stringResource(R.string.history_empty_search, state.query)
                    } else {
                        stringResource(R.string.history_empty_filter, state.filter.label().lowercase())
                    },
                )
                is HistoryUiState.Content -> LazyColumn(modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.PAYMENT_LIST)) {
                    items(state.payments, key = { it.id }) { payment ->
                        PaymentRow(payment, onClick = callbacks.onPaymentClick)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
