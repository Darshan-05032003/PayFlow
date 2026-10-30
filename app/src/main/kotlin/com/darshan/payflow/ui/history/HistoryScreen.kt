package com.darshan.payflow.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import com.darshan.payflow.ui.theme.ScreenPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Composable
private fun HistoryFilter.emptyTitle(): String = stringResource(
    when (this) {
        HistoryFilter.ALL -> R.string.history_empty_all
        HistoryFilter.SUCCESS -> R.string.history_empty_success
        HistoryFilter.PENDING -> R.string.history_empty_pending
        HistoryFilter.FAILED -> R.string.history_empty_failed
        HistoryFilter.REFUNDED -> R.string.history_empty_refunded
    },
)

private fun HistoryFilter.emptyIcon(): ImageVector = when (this) {
    HistoryFilter.ALL -> Icons.Outlined.ReceiptLong
    HistoryFilter.SUCCESS -> Icons.Outlined.CheckCircle
    HistoryFilter.PENDING -> Icons.Outlined.Schedule
    HistoryFilter.FAILED -> Icons.Outlined.ErrorOutline
    HistoryFilter.REFUNDED -> Icons.Outlined.Replay
}

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; callbacks.onQuery(it) },
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        IconButton(onClick = { text = ""; callbacks.onQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_clear_search))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).testTag(PayFlowTestTags.SEARCH_FIELD),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == selectedFilter,
                        onClick = { callbacks.onFilter(filter) },
                        label = { Text(filter.label()) },
                        leadingIcon = if (filter == selectedFilter) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(PayFlowTestTags.filterChip(filter)),
                    )
                }
            }
            when (state) {
                HistoryUiState.Loading -> LoadingView()
                is HistoryUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry)
                is HistoryUiState.Empty -> if (state.query.isNotBlank()) {
                    EmptyView(
                        title = stringResource(R.string.history_empty_search, state.query),
                        subtitle = stringResource(R.string.history_empty_search_hint),
                        icon = Icons.Outlined.SearchOff,
                    )
                } else {
                    EmptyView(
                        title = state.filter.emptyTitle(),
                        subtitle = stringResource(R.string.history_empty_hint),
                        icon = state.filter.emptyIcon(),
                    )
                }
                is HistoryUiState.Content -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.PAYMENT_LIST),
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
                ) {
                    item(key = "count") {
                        Text(
                            stringResource(R.string.history_count, state.payments.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                        )
                    }
                    itemsIndexed(state.payments, key = { _, p -> p.id }) { index, payment ->
                        val shape = when {
                            state.payments.size == 1 -> MaterialTheme.shapes.medium
                            index == 0 -> MaterialTheme.shapes.medium.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
                            index == state.payments.lastIndex -> MaterialTheme.shapes.medium.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
                            else -> RectangleShape
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                PaymentRow(payment, onClick = callbacks.onPaymentClick)
                                if (index < state.payments.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
