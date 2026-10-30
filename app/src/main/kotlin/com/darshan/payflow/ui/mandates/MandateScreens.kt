package com.darshan.payflow.ui.mandates

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.DetailRow
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.SectionCard
import com.darshan.payflow.ui.components.SectionHeader
import com.darshan.payflow.ui.components.StatusChip
import com.darshan.payflow.ui.theme.PayFlowTheme
import com.darshan.payflow.ui.theme.ScreenPadding
import com.darshan.payflow.ui.theme.StatusColor
import java.time.format.DateTimeFormatter
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
import com.darshan.payflow.ui.components.SecureWindow
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = callbacks.onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.mandate_create)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag(PayFlowTestTags.MANDATE_CREATE),
            )
        },
    ) { padding ->
        when (state) {
            MandatesUiState.Loading -> LoadingView(Modifier.padding(padding))
            MandatesUiState.Empty -> EmptyView(
                stringResource(R.string.mandates_empty),
                Modifier.padding(padding),
                stringResource(R.string.mandates_empty_hint),
                icon = Icons.Outlined.EventRepeat,
            )
            is MandatesUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onRetry, Modifier.padding(padding))
            is MandatesUiState.Content -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        stringResource(R.string.mandates_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(state.mandates, key = { it.id }) { mandate ->
                    Card(
                        onClick = { callbacks.onOpen(mandate.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(mandate.payeeName)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(mandate.payeeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(mandate.payeeVpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.mandate_summary, Money.formatInr(mandate.maxAmountPaise), mandate.frequency.label()), style = MaterialTheme.typography.bodyMedium)
                            }
                            StatusChip(mandate.status.label(), mandate.status.statusColor())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MandateStatus.statusColor(): StatusColor = when (this) {
    MandateStatus.ACTIVE -> PayFlowTheme.status.success
    MandateStatus.PAUSED -> PayFlowTheme.status.pending
    MandateStatus.REVOKED -> PayFlowTheme.status.failed
    MandateStatus.EXPIRED -> PayFlowTheme.status.neutral
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
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mandate_create)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val enabled = !state.submitting
            SectionCard(stringResource(R.string.mandate_section_merchant), icon = Icons.Outlined.Storefront) {
                OutlinedTextField(
                    value = state.vpa, onValueChange = callbacks.onVpaChange, enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.pay_vpa)) }, isError = state.errors.vpa != null,
                    placeholder = { Text(stringResource(R.string.pay_vpa_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
                    supportingText = state.errors.vpa?.let { { Text(it.message()) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_VPA),
                )
                OutlinedTextField(
                    value = state.name, onValueChange = callbacks.onNameChange, enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.pay_payee_name)) }, isError = state.errors.name != null,
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    supportingText = state.errors.name?.let { { Text(it.message()) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_NAME),
                )
            }
            SectionCard(stringResource(R.string.mandate_section_rules), icon = Icons.Outlined.Rule) {
                OutlinedTextField(
                    value = state.amountText, onValueChange = callbacks.onAmountChange, enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.mandate_max_amount)) }, prefix = { Text("₹") }, isError = state.errors.amount != null,
                    supportingText = { Text(state.errors.amount?.message() ?: stringResource(R.string.mandate_max_amount_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_AMOUNT),
                )
                Text(stringResource(R.string.mandate_frequency), style = MaterialTheme.typography.labelLarge)
                ChipRow(MandateFrequency.entries, state.frequency, { it.label() }, { PayFlowTestTags.mandateFrequency(it) }, callbacks.onFrequencyChange, enabled)
                Text(stringResource(R.string.mandate_validity), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(1, 3, 6, 12, 24), state.validityMonths, { stringResource(R.string.mandate_months, it) }, { PayFlowTestTags.mandateValidity(it) }, callbacks.onValidityMonthsChange, enabled)
                OutlinedTextField(
                    value = state.note, onValueChange = callbacks.onNoteChange, enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.pay_note)) }, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Notes, contentDescription = null) },
                )
            }
            state.ruleError?.let { InfoBanner(it.message(), icon = Icons.Outlined.ErrorOutline, tone = BannerTone.Error) }
            Button(onClick = callbacks.onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PayFlowTestTags.MANDATE_SUBMIT)) {
                Text(stringResource(R.string.mandate_create_submit), style = MaterialTheme.typography.titleMedium)
            }
            if (state.submitting) LoadingView(Modifier.height(72.dp))
            InfoBanner(stringResource(R.string.mandate_create_sandbox), icon = Icons.Outlined.Science, tone = BannerTone.Info)
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
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mandate_detail_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            MandateDetailUiState.Loading -> LoadingView(Modifier.padding(padding))
            MandateDetailUiState.NotFound -> EmptyView(stringResource(R.string.mandate_not_found), Modifier.padding(padding), icon = Icons.Outlined.SearchOff)
            is MandateDetailUiState.Error -> ErrorView(stringResource(R.string.error_generic), callbacks.onBack, Modifier.padding(padding))
            is MandateDetailUiState.Content -> DetailContent(state, callbacks, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DetailContent(state: MandateDetailUiState.Content, callbacks: MandateDetailCallbacks, modifier: Modifier) {
    val m = state.mandate
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InitialAvatar(m.payeeName, size = 64.dp)
                Text(m.payeeName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(m.payeeVpa, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.mandate_summary, Money.formatInr(m.maxAmountPaise), m.frequency.label()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                StatusChip(m.status.label(), m.status.statusColor())
            }
        }
        if (state.availableActions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                state.availableActions.forEach { action ->
                    val tag = Modifier.weight(1f).testTag(PayFlowTestTags.mandateAction(action))
                    val icon = when (action) {
                        MandateAction.PAUSE -> Icons.Outlined.Pause
                        MandateAction.RESUME -> Icons.Outlined.PlayArrow
                        MandateAction.REVOKE -> Icons.Outlined.Block
                        MandateAction.EXPIRE -> Icons.Outlined.Timer
                    }
                    if (action == MandateAction.REVOKE) {
                        OutlinedButton(
                            onClick = { callbacks.onAction(action) }, enabled = !state.busy, modifier = tag,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(action.label(), modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        FilledTonalButton(onClick = { callbacks.onAction(action) }, enabled = !state.busy, modifier = tag) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(action.label(), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
        SectionCard(stringResource(R.string.mandate_section_terms), icon = Icons.Outlined.Rule) {
            DetailRow(stringResource(R.string.mandate_umn), m.umn)
            DetailRow(stringResource(R.string.mandate_max_amount), Money.formatInr(m.maxAmountPaise))
            DetailRow(stringResource(R.string.mandate_frequency), m.frequency.label())
            DetailRow(stringResource(R.string.mandate_validity), stringResource(R.string.mandate_validity_range, m.startDate.format(dateFormatter), m.endDate.format(dateFormatter)))
            DetailRow(stringResource(R.string.mandate_executions), m.executionCount.toString())
            if (m.note.isNotBlank()) DetailRow(stringResource(R.string.detail_note), m.note)
        }
        if (m.status == MandateStatus.ACTIVE) {
            SectionCard(stringResource(R.string.mandate_simulate_title), icon = Icons.Outlined.Science) {
                Text(stringResource(R.string.mandate_simulate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = state.debitAmountText, onValueChange = callbacks.onDebitAmountChange, singleLine = true, enabled = !state.busy,
                    label = { Text(stringResource(R.string.pay_amount)) }, prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_DEBIT_AMOUNT),
                )
                Button(onClick = { callbacks.onSimulateDebit(state.debitAmountText) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.MANDATE_DEBIT)) {
                    Text(stringResource(R.string.mandate_simulate_button))
                }
            }
        }
        SectionHeader(stringResource(R.string.mandate_history))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            if (state.executions.isEmpty()) {
                EmptyView(stringResource(R.string.mandate_history_empty), subtitle = stringResource(R.string.mandate_history_empty_hint), icon = Icons.Outlined.ReceiptLong, fillHeight = false, modifier = Modifier.fillMaxWidth())
            } else {
                Column {
                    state.executions.forEachIndexed { index, payment ->
                        PaymentRow(payment, onClick = callbacks.onOpenPayment)
                        if (index < state.executions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
