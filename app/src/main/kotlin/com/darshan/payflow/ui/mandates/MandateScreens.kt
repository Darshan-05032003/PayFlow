package com.darshan.payflow.ui.mandates

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.ExecutionCheck
import com.darshan.payflow.domain.model.Mandate
import com.darshan.payflow.domain.model.MandateAction
import com.darshan.payflow.domain.model.MandateFrequency
import com.darshan.payflow.domain.model.MandateStatus
import com.darshan.payflow.domain.model.MandateValidation
import com.darshan.payflow.domain.usecase.ExecuteMandateResult
import com.darshan.payflow.domain.usecase.UpdateMandateResult
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.EmptyView
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.PaymentRow
import com.darshan.payflow.ui.pay.message

// ------------------------------------------------------------------ shared labels

@Composable
fun MandateFrequency.label(): String = stringResource(
    when (this) {
        MandateFrequency.DAILY -> R.string.mandate_freq_daily
        MandateFrequency.WEEKLY -> R.string.mandate_freq_weekly
        MandateFrequency.MONTHLY -> R.string.mandate_freq_monthly
        MandateFrequency.QUARTERLY -> R.string.mandate_freq_quarterly
        MandateFrequency.YEARLY -> R.string.mandate_freq_yearly
        MandateFrequency.AS_PRESENTED -> R.string.mandate_freq_as_presented
    },
)

@Composable
fun MandateStatus.label(): String = stringResource(
    when (this) {
        MandateStatus.ACTIVE -> R.string.mandate_status_active
        MandateStatus.PAUSED -> R.string.mandate_status_paused
        MandateStatus.REVOKED -> R.string.mandate_status_revoked
        MandateStatus.EXPIRED -> R.string.mandate_status_expired
    },
)

@Composable
fun MandateAction.label(): String = stringResource(
    when (this) {
        MandateAction.PAUSE -> R.string.mandate_action_pause
        MandateAction.RESUME -> R.string.mandate_action_resume
        MandateAction.REVOKE -> R.string.mandate_action_revoke
        MandateAction.EXPIRE -> R.string.mandate_status_expired
    },
)

@Composable
fun MandateValidation.message(): String = stringResource(
    when (this) {
        MandateValidation.Ok -> R.string.mandate_rule_ok
        MandateValidation.InvalidAmount -> R.string.mandate_rule_amount
        MandateValidation.StartInPast -> R.string.mandate_rule_start
        MandateValidation.EndBeforeStart -> R.string.mandate_rule_end
        MandateValidation.TooLong -> R.string.mandate_rule_too_long
        MandateValidation.InvalidPayee -> R.string.mandate_rule_payee
    },
)

@Composable
fun ExecutionCheck.message(): String = stringResource(
    when (this) {
        ExecutionCheck.Allowed -> R.string.mandate_exec_ok
        ExecutionCheck.InvalidAmount -> R.string.error_invalid_amount
        ExecutionCheck.AmountExceedsMax -> R.string.mandate_exec_exceeds
        ExecutionCheck.NotActive -> R.string.mandate_exec_not_active
        ExecutionCheck.OutsideValidity -> R.string.mandate_exec_outside
        ExecutionCheck.AlreadyExecutedThisPeriod -> R.string.mandate_exec_period
    },
)

// ------------------------------------------------------------------ list

data class MandatesCallbacks(val onBack: () -> Unit = {}, val onOpen: (String) -> Unit = {}, val onCreate: () -> Unit = {}, val onRetry: () -> Unit = {})

@Composable
fun MandatesRoute(onBack: () -> Unit, onOpen: (String) -> Unit, onCreate: () -> Unit, viewModel: MandatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MandatesScreen(state, MandatesCallbacks(onBack, onOpen, onCreate, viewModel::retry))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MandatesScreen(state: MandatesUiState, callbacks: MandatesCallbacks) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mandates_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = callbacks.onCreate, modifier = Modifier.testTag(PayFlowTestTags.MANDATE_CREATE)) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mandate_create))
            }
        },
    ) { padding ->
        when (state) {
            MandatesUiState.Loading -> LoadingView(Modifier.padding(padding))
            MandatesUiState.Empty -> EmptyView(stringResource(R.string.mandates_empty), Modifier.padding(padding), stringResource(R.string.mandates_empty_hint))
            is MandatesUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry, Modifier.padding(padding))
            is MandatesUiState.Content -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.mandates, key = { it.id }) { mandate ->
                    Column(Modifier.fillMaxWidth().clickable { callbacks.onOpen(mandate.id) }.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(mandate.payeeName, style = MaterialTheme.typography.titleMedium)
                            Text(mandate.status.label(), style = MaterialTheme.typography.labelLarge, color = mandate.status.color())
                        }
                        Text(mandate.payeeVpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.mandate_summary, Money.formatInr(mandate.maxAmountPaise), mandate.frequency.label()), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MandateStatus.color() = when (this) {
    MandateStatus.ACTIVE -> MaterialTheme.colorScheme.primary
    MandateStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    MandateStatus.REVOKED, MandateStatus.EXPIRED -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------------ create

data class CreateMandateCallbacks(
    val onBack: () -> Unit = {},
    val onVpaChange: (String) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onFrequencyChange: (MandateFrequency) -> Unit = {},
    val onValidityMonthsChange: (Int) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
)

@Composable
fun CreateMandateRoute(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: CreateMandateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val unavailable = stringResource(R.string.collect_msg_unavailable)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateMandateEvent.Created -> onCreated(event.mandateId)
                CreateMandateEvent.Unavailable -> snackbar.showSnackbar(unavailable)
            }
        }
    }
    CreateMandateScreen(
        state,
        CreateMandateCallbacks(onBack, viewModel::onVpaChange, viewModel::onNameChange, viewModel::onAmountChange, viewModel::onFrequencyChange, viewModel::onValidityMonthsChange, viewModel::onNoteChange, viewModel::submit),
        snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMandateScreen(state: CreateMandateUiState, callbacks: CreateMandateCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mandate_create)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val enabled = !state.submitting
            OutlinedTextField(
                value = state.vpa, onValueChange = callbacks.onVpaChange, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.pay_vpa)) }, isError = state.errors.vpa != null,
                supportingText = state.errors.vpa?.let { { Text(it.message()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_VPA),
            )
            OutlinedTextField(
                value = state.name, onValueChange = callbacks.onNameChange, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.pay_payee_name)) }, isError = state.errors.name != null,
                supportingText = state.errors.name?.let { { Text(it.message()) } },
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_NAME),
            )
            OutlinedTextField(
                value = state.amountText, onValueChange = callbacks.onAmountChange, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.mandate_max_amount)) }, prefix = { Text("₹") }, isError = state.errors.amount != null,
                supportingText = state.errors.amount?.let { { Text(it.message()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_AMOUNT),
            )
            Text(stringResource(R.string.mandate_frequency), style = MaterialTheme.typography.labelLarge)
            ChipRow(MandateFrequency.entries, state.frequency, { it.label() }, { PayFlowTestTags.mandateFrequency(it) }, callbacks.onFrequencyChange, enabled)
            Text(stringResource(R.string.mandate_validity), style = MaterialTheme.typography.labelLarge)
            ChipRow(listOf(1, 3, 6, 12, 24), state.validityMonths, { stringResource(R.string.mandate_months, it) }, { PayFlowTestTags.mandateValidity(it) }, callbacks.onValidityMonthsChange, enabled)
            OutlinedTextField(
                value = state.note, onValueChange = callbacks.onNoteChange, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.pay_note)) }, modifier = Modifier.fillMaxWidth(),
            )
            state.ruleError?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = callbacks.onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_SUBMIT)) {
                Text(stringResource(R.string.mandate_create_submit))
            }
            if (state.submitting) LoadingView(Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) }, enabled = enabled, modifier = Modifier.testTag(tag(option)))
        }
    }
}

// ------------------------------------------------------------------ detail

data class MandateDetailCallbacks(
    val onBack: () -> Unit = {},
    val onAction: (MandateAction) -> Unit = {},
    val onDebitAmountChange: (String) -> Unit = {},
    val onSimulateDebit: (String) -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
)

@Composable
fun MandateDetailRoute(onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: MandateDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val texts = DetailTexts(
        updated = stringResource(R.string.mandate_msg_updated), illegal = stringResource(R.string.mandate_msg_illegal),
        unavailable = stringResource(R.string.collect_msg_unavailable), executed = stringResource(R.string.mandate_msg_executed),
        rejected = stringResource(R.string.mandate_msg_rejected),
    )
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbar.showSnackbar(
                when (event) {
                    is MandateDetailEvent.ActionResult -> when (event.result) {
                        is UpdateMandateResult.Updated -> texts.updated
                        is UpdateMandateResult.IllegalTransition -> texts.illegal
                        is UpdateMandateResult.Unavailable, UpdateMandateResult.NotFound -> texts.unavailable
                    }
                    is MandateDetailEvent.ExecutionResult -> when (val r = event.result) {
                        is ExecuteMandateResult.Executed -> texts.executed
                        is ExecuteMandateResult.Rejected -> texts.rejected
                        is ExecuteMandateResult.Unavailable, ExecuteMandateResult.NotFound -> texts.unavailable
                    }
                },
            )
        }
    }
    MandateDetailScreen(state, MandateDetailCallbacks(onBack, viewModel::act, viewModel::onDebitAmountChange, viewModel::simulateDebit, onOpenPayment), snackbar)
}

private data class DetailTexts(val updated: String, val illegal: String, val unavailable: String, val executed: String, val rejected: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MandateDetailScreen(state: MandateDetailUiState, callbacks: MandateDetailCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mandate_detail_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            MandateDetailUiState.Loading -> LoadingView(Modifier.padding(padding))
            MandateDetailUiState.NotFound -> EmptyView(stringResource(R.string.mandate_not_found), Modifier.padding(padding))
            is MandateDetailUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onBack, Modifier.padding(padding))
            is MandateDetailUiState.Content -> DetailContent(state, callbacks, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DetailContent(state: MandateDetailUiState.Content, callbacks: MandateDetailCallbacks, modifier: Modifier) {
    val m = state.mandate
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.payeeName, style = MaterialTheme.typography.headlineSmall)
        Text(m.payeeVpa, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(m.status.label(), color = m.status.color(), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        DetailLine(stringResource(R.string.mandate_umn), m.umn)
        DetailLine(stringResource(R.string.mandate_max_amount), Money.formatInr(m.maxAmountPaise))
        DetailLine(stringResource(R.string.mandate_frequency), m.frequency.label())
        DetailLine(stringResource(R.string.mandate_validity), "${m.startDate} → ${m.endDate}")
        DetailLine(stringResource(R.string.mandate_executions), m.executionCount.toString())
        if (m.note.isNotBlank()) DetailLine(stringResource(R.string.detail_note), m.note)
        if (state.availableActions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availableActions.forEach { action ->
                    val tag = Modifier.testTag(PayFlowTestTags.mandateAction(action))
                    if (action == MandateAction.REVOKE) {
                        OutlinedButton(onClick = { callbacks.onAction(action) }, enabled = !state.busy, modifier = tag) { Text(action.label()) }
                    } else {
                        Button(onClick = { callbacks.onAction(action) }, enabled = !state.busy, modifier = tag) { Text(action.label()) }
                    }
                }
            }
        }
        if (m.status == MandateStatus.ACTIVE) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mandate_simulate_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.mandate_simulate_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.debitAmountText, onValueChange = callbacks.onDebitAmountChange, singleLine = true, enabled = !state.busy,
                        label = { Text(stringResource(R.string.pay_amount)) }, prefix = { Text("₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_DEBIT_AMOUNT),
                    )
                    Button(onClick = { callbacks.onSimulateDebit(state.debitAmountText) }, enabled = !state.busy, modifier = Modifier.testTag(PayFlowTestTags.MANDATE_DEBIT)) {
                        Text(stringResource(R.string.mandate_simulate_button))
                    }
                }
            }
        }
        Text(stringResource(R.string.mandate_history), style = MaterialTheme.typography.titleMedium)
        if (state.executions.isEmpty()) {
            Text(stringResource(R.string.mandate_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.executions.forEach { payment ->
                PaymentRow(payment, onClick = callbacks.onOpenPayment)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp))
    }
}
