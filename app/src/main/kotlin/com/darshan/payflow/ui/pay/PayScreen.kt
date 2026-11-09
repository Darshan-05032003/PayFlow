package com.darshan.payflow.ui.pay

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.UpiApp
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SandboxBadge
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.effects.CollectUiEffects
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PayCallbacks(
    val onBack: () -> Unit = {},
    val onVpaChange: (String) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onAmountKey: (AmountKey) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onCheckStatus: () -> Unit = {},
    val onOpenPayment: (String) -> Unit = {},
    val onReset: () -> Unit = {},
    val onAppChosen: (String) -> Unit = {},
    val onDismissChooser: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onShareImage: () -> Unit = {},
    val onPayAgain: () -> Unit = {},
    /** "Done" on the result screen: leave the Pay screen. */
    val onDone: () -> Unit = {},
    val onMarkPaid: () -> Unit = {},
    val onMarkNotPaid: () -> Unit = {},
)

@Composable
fun PayRoute(
    upiAppLauncher: UpiAppLauncher,
    onBack: () -> Unit,
    onOpenPayment: (String) -> Unit,
    viewModel: PayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toasts = rememberToastHostState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noUpiApp = stringResource(R.string.pay_no_upi_app)
    val shareTitle = stringResource(R.string.pay_share_receipt)
    val shareFailed = stringResource(R.string.pay_share_failed)
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onUpiAppResult(upiAppLauncher.parseActivityResult(result.resultCode, result.data))
    }
    val blocked = stringResource(R.string.pay_blocked)
    val warning = stringResource(R.string.pay_guard_warning)
    CollectUiEffects(viewModel.uiEffects)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayEvent.LaunchUpiApp -> {
                    val intent = event.packageName?.let { upiAppLauncher.targetedIntent(event.upiUri, it) } ?: upiAppLauncher.chooserIntent(event.upiUri)
                    runCatching { activityLauncher.launch(intent) }.onFailure { toasts.show(noUpiApp, ToastTone.Failed) }
                }
                PayEvent.NoUpiApp -> toasts.show(noUpiApp, ToastTone.Failed)
                is PayEvent.Blocked -> toasts.show(blocked, ToastTone.Failed)
                is PayEvent.Warning -> toasts.show(warning, ToastTone.Pending)
                is PayEvent.ShareReceipt -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, event.text)
                    runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }.onFailure { toasts.show(shareFailed, ToastTone.Failed) }
                }
                is PayEvent.ShareReceiptImage -> scope.launch {
                    val uri = runCatching {
                        withContext(Dispatchers.Default) {
                            val file = ReceiptExporter.write(context, ReceiptImage.render(event.receipt, context))
                            ReceiptExporter.uriOf(context, file)
                        }
                    }.getOrNull()
                    if (uri == null) {
                        toasts.show(shareFailed, ToastTone.Failed)
                    } else {
                        runCatching { context.startActivity(Intent.createChooser(ReceiptExporter.shareIntent(uri), shareTitle)) }.onFailure { toasts.show(shareFailed, ToastTone.Failed) }
                    }
                }
            }
        }
    }
    PayScreen(
        state = state,
        callbacks = PayCallbacks(
            onBack = onBack,
            onVpaChange = viewModel::onVpaChange,
            onNameChange = viewModel::onNameChange,
            onAmountChange = viewModel::onAmountChange,
            onAmountKey = viewModel::onAmountKey,
            onNoteChange = viewModel::onNoteChange,
            onSubmit = viewModel::submit,
            onCheckStatus = viewModel::checkStatus,
            onOpenPayment = onOpenPayment,
            onReset = viewModel::reset,
            onAppChosen = viewModel::onAppChosen,
            onDismissChooser = viewModel::dismissChooser,
            onShare = viewModel::shareReceipt,
            onShareImage = viewModel::shareReceiptImage,
            onPayAgain = viewModel::payAgain,
            onDone = { ReceiptExporter.clear(context); viewModel.reset(); onBack() },
            onMarkPaid = { viewModel.markManually(paid = true) },
            onMarkNotPaid = { viewModel.markManually(paid = false) },
        ),
        toasts = toasts,
    )
}

@Composable
fun PayFieldError.message(): String = when (this) {
    PayFieldError.REQUIRED -> stringResource(R.string.error_required)
    PayFieldError.INVALID_VPA -> stringResource(R.string.error_invalid_vpa)
    PayFieldError.VPA_NOT_FOUND -> stringResource(R.string.error_vpa_not_found)
    PayFieldError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_amount)
    PayFieldError.AMOUNT_TOO_LARGE -> stringResource(R.string.error_amount_too_large, Money.formatInr(Payment.MAX_AMOUNT_PAISE))
}

/** Which of the main views the phase belongs to. Keyed so a change of phase is one fade, not a rebuild per state update. */
private enum class PayView { Form, Awaiting, Confirming, Result, Unresolved }

private fun PayPhase.view(): PayView = when (this) {
    PayPhase.Idle, PayPhase.Submitting, is PayPhase.ChoosingApp -> PayView.Form
    is PayPhase.AwaitingApp -> PayView.Awaiting
    is PayPhase.Confirming -> PayView.Confirming
    is PayPhase.Done -> PayView.Result
    is PayPhase.Unresolved -> PayView.Unresolved
}

/** How long the current wait has lasted, in the steps the copy and the back rule care about. It delays; it doesn't tick. */
@Composable
private fun rememberWaitMillis(key: Any?, waiting: Boolean): Long {
    val elapsed by produceState(0L, key, waiting) {
        value = 0L
        if (!waiting) return@produceState
        delay(ProcessingStage.STILL_CHECKING_MS)
        value = ProcessingStage.STILL_CHECKING_MS
        delay(PayBackPolicy.BLOCK_LIMIT_MS - ProcessingStage.STILL_CHECKING_MS)
        value = PayBackPolicy.BLOCK_LIMIT_MS
    }
    return elapsed
}

@Composable
fun PayScreen(state: PayUiState, callbacks: PayCallbacks, toasts: ToastHostState = rememberToastHostState()) {
    SecureWindow()
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val haptics = LocalHaptics.current
    val phase = state.phase

    val waitKey: Any = (phase as? PayPhase.Confirming)?.paymentId ?: (phase as? PayPhase.Done)?.paymentId ?: (phase as? PayPhase.Unresolved)?.paymentId ?: phase.view()
    val running = PayBackPolicy.isRunning(phase)
    val waitMillis = rememberWaitMillis(waitKey, running || state.polling)
    val stage = ProcessingStage.at(waitMillis)
    val backBlocked = PayBackPolicy.blocksBack(phase, waitMillis)

    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    var againOpen by rememberSaveable { mutableStateOf(false) }
    var playedMoment by rememberSaveable { mutableStateOf("") }
    // A refused attempt closes the confirmation: the screen behind it says what went wrong.
    LaunchedEffect(state.attemptsRefused) { confirmOpen = false }

    val confirmVisible = confirmOpen && (phase is PayPhase.Idle || phase is PayPhase.Submitting)
    val appSheet = phase as? PayPhase.ChoosingApp
    val shareVisible = shareOpen && phase is PayPhase.Done
    val againVisible = againOpen && phase is PayPhase.Done
    val sheetOpen = confirmVisible || appSheet != null || shareVisible || againVisible
    val blockedMessage = stringResource(R.string.pay_back_blocked)

    // Registered before the sheets' own handlers, so an open sheet still takes back first.
    BackHandler(enabled = backBlocked) {
        haptics.reject()
        toasts.show(blockedMessage, ToastTone.Pending)
    }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                // Sheets are in-window, so without this TalkBack would walk the form behind one.
                .then(if (sheetOpen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            PayFlowTopBar(
                title = stringResource(R.string.pay_title),
                onBack = callbacks.onBack.takeUnless { backBlocked },
                actions = { if (state.paymentMode == PaymentMode.SANDBOX) SandboxBadge(Modifier.padding(end = Spacing.sm)) },
            )
            AnimatedContent(
                targetState = phase.view(),
                transitionSpec = { motion.fadeThrough() },
                label = "payView",
                modifier = Modifier.fillMaxSize(),
            ) { view ->
                // The target's data, read once it's current: a leaving view keeps drawing its last good state.
                when (view) {
                    PayView.Form -> PayFormContent(state, callbacks, onConfirmRequest = { confirmOpen = true })
                    PayView.Awaiting -> (phase as? PayPhase.AwaitingApp)?.let { AwaitingAppView(it, state.paymentMode, callbacks) }
                    PayView.Confirming -> ConfirmingView(state.paymentMode, stage, callbacks)
                    PayView.Result -> (phase as? PayPhase.Done)?.let {
                        ResultView(
                            it, state.paymentMode, state.polling, stage, callbacks,
                            onShareRequest = { shareOpen = true }, onPayAgainRequest = { againOpen = true },
                            playedKey = playedMoment, onPlayed = { key -> playedMoment = key },
                        )
                    }
                    PayView.Unresolved -> (phase as? PayPhase.Unresolved)?.let { UnresolvedView(it, state.polling, stage, callbacks) }
                }
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter))
        ConfirmSheet(confirmVisible, state, phase is PayPhase.Submitting && backBlocked, callbacks) { confirmOpen = false }
        AppChooserSheet(appSheet, state, backBlocked, callbacks)
        ShareSheet(shareVisible, onDismiss = { shareOpen = false }, callbacks)
        PayAgainSheet(againVisible, onDismiss = { againOpen = false }, callbacks)
    }
}

/**
 * The plain way to pay: TalkBack's activate and the button under the slider both land here, and so does anyone who'd rather not
 * swipe. It's a separate confirmation, so no route to a payment is a single accidental action.
 */
@Composable
private fun ConfirmSheet(visible: Boolean, state: PayUiState, locked: Boolean, callbacks: PayCallbacks, onDismiss: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val paise = state.amountPaise ?: 0L
    PayFlowBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        dismissible = !locked,
        modifier = Modifier.testTag(PayFlowTestTags.PAY_CONFIRM_SHEET),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.pay_confirm_title), style = type.label, color = colors.textSecondary)
            AmountText(paise, style = type.display, color = colors.text, decimals = AmountDecimals.Always)
            Text(stringResource(R.string.pay_confirm_to, DisplayText.safe(state.name.ifBlank { state.vpa })), style = type.bodyStrong, color = colors.text, maxLines = 2)
            Text(DisplayText.safe(state.vpa), style = type.label, color = colors.textSecondary, maxLines = 1)
            PrimaryButton(
                stringResource(R.string.pay_submit_amount, Money.formatInr(paise)),
                callbacks.onSubmit,
                loading = state.phase is PayPhase.Submitting,
                enabled = visible,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag(PayFlowTestTags.PAY_SUBMIT),
            )
            GhostButton(stringResource(R.string.pay_cancel), onDismiss, outlined = false, tone = GhostTone.Neutral, enabled = !locked, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** PayFlow's own "Pay with" sheet. Once an app is picked it locks, and only opens up again at the back limit. */
@Composable
private fun AppChooserSheet(phase: PayPhase.ChoosingApp?, state: PayUiState, backBlocked: Boolean, callbacks: PayCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    // Kept through the exit animation.
    val last = remember { mutableStateOf<PayPhase.ChoosingApp?>(null) }
    if (phase != null) last.value = phase
    val shown = last.value
    val picked = phase?.picked
    PayFlowBottomSheet(
        visible = phase != null,
        onDismissRequest = if (picked != null) callbacks.onBack else callbacks.onDismissChooser,
        dismissible = !(picked != null && backBlocked),
        modifier = Modifier.testTag(PayFlowTestTags.PAY_APP_SHEET),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
            Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.pay_chooser_title), style = type.title, color = colors.text)
                Text(
                    stringResource(R.string.pay_chooser_subtitle, state.amountPaise?.let(Money::formatInr) ?: "", DisplayText.safe(state.vpa)),
                    style = type.body,
                    color = colors.textSecondary,
                )
            }
            shown?.apps?.forEach { app -> AppRow(app, picked, callbacks) }
            Text(
                stringResource(R.string.pay_chooser_note),
                style = type.label,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.screen).padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun AppRow(app: UpiApp, picked: String?, callbacks: PayCallbacks) {
    val description = stringResource(R.string.pay_chooser_app, app.label)
    val bitmap = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    PayFlowListRow(
        title = app.label,
        leading = {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(Sizes.avatar))
            } else {
                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = PayFlowTheme.colors.accent, modifier = Modifier.size(Sizes.avatar))
            }
        },
        trailing = { if (picked == app.packageName) BrandLoader(size = Sizes.loaderSmall) },
        onClick = if (picked == null) ({ callbacks.onAppChosen(app.packageName) }) else null,
        modifier = Modifier.testTag(PayFlowTestTags.payApp(app.packageName)).semantics { contentDescription = description },
    )
}

/** Sharing is the user's choice of format: screenshots are blocked app-wide, so this is the only way a receipt leaves. */
@Composable
private fun ShareSheet(visible: Boolean, onDismiss: () -> Unit, callbacks: PayCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowBottomSheet(visible, onDismiss, Modifier.testTag(PayFlowTestTags.PAY_SHARE_SHEET)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.pay_share_title), style = type.title, color = colors.text)
            Text(stringResource(R.string.pay_share_note), style = type.body, color = colors.textSecondary)
            PrimaryButton(stringResource(R.string.pay_share_as_image), { onDismiss(); callbacks.onShareImage() }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag(PayFlowTestTags.PAY_SHARE_IMAGE))
            GhostButton(stringResource(R.string.pay_share_as_text), { onDismiss(); callbacks.onShare() }, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_SHARE_TEXT))
        }
    }
}

/** Paying again while the last payment is unresolved is how people pay twice, so it takes a second, plainly worded step. */
@Composable
private fun PayAgainSheet(visible: Boolean, onDismiss: () -> Unit, callbacks: PayCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowBottomSheet(visible, onDismiss, Modifier.testTag(PayFlowTestTags.PAY_AGAIN_SHEET)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.pay_again_title), style = type.title, color = colors.text)
            Text(stringResource(R.string.pay_again_warning), style = type.body, color = colors.textSecondary)
            PrimaryButton(stringResource(R.string.pay_again_wait), onDismiss, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs))
            GhostButton(stringResource(R.string.pay_again_anyway), { onDismiss(); callbacks.onPayAgain() }, tone = GhostTone.Danger, modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_AGAIN_ANYWAY))
        }
    }
}
