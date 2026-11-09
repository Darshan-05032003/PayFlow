package com.darshan.payflow.ui.security

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.data.security.BindingStatus
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SandboxBadge
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DrawnCheckmark
import com.darshan.payflow.ui.designsystem.components.DrawnCross
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.flow.first

data class SecurityCallbacks(
    val onBack: (() -> Unit)? = {},
    val onPhoneChange: (String) -> Unit = {},
    val onRequestOtp: () -> Unit = {},
    val onOtpChange: (String) -> Unit = {},
    val onVerifyOtp: () -> Unit = {},
    val onUnbind: () -> Unit = {},
    val onCheckIntegrity: () -> Unit = {},
    val onSetupPin: () -> Unit = {},
    /** Opens "turn off app lock"; nothing is removed until [onConfirmRemoval] or [onRemoveWithBiometric] proves the owner. */
    val onStartRemoval: () -> Unit = {},
    val onCancelRemoval: () -> Unit = {},
    val onConfirmRemoval: (String) -> Unit = {},
    val onRemoveWithBiometric: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onPaymentMode: (PaymentMode) -> Unit = {},
)

@Composable
fun SecurityRoute(onBack: (() -> Unit)?, onSetupPin: () -> Unit, viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    SideEffect { viewModel.host = activity }
    val biometricTitle = stringResource(R.string.security_remove_title)
    SecurityScreen(
        state,
        SecurityCallbacks(
            onBack = onBack, onPhoneChange = viewModel::onPhoneChange, onRequestOtp = viewModel::requestOtp, onOtpChange = viewModel::onOtpChange,
            onVerifyOtp = viewModel::verifyOtp, onUnbind = viewModel::unbind, onCheckIntegrity = viewModel::checkIntegrity,
            onSetupPin = onSetupPin, onStartRemoval = viewModel::startPinRemoval, onCancelRemoval = viewModel::cancelPinRemoval,
            onConfirmRemoval = viewModel::confirmPinRemoval, onRemoveWithBiometric = { viewModel.confirmPinRemovalWithBiometric(biometricTitle) },
            onRefresh = viewModel::refresh, onPaymentMode = viewModel::setPaymentMode,
        ),
    )
}

@Composable
fun TamperFinding.label(): String = stringResource(
    when (this) {
        TamperFinding.SU_BINARY -> R.string.tamper_su
        TamperFinding.MAGISK -> R.string.tamper_magisk
        TamperFinding.TEST_KEYS -> R.string.tamper_test_keys
        TamperFinding.FRIDA_PORT, TamperFinding.FRIDA_LIBRARY -> R.string.tamper_frida
        TamperFinding.DEBUGGER -> R.string.tamper_debugger
        TamperFinding.DEBUGGABLE -> R.string.tamper_debuggable
        TamperFinding.SIGNATURE_MISMATCH -> R.string.tamper_signature
        TamperFinding.SIGNATURE_UNREADABLE -> R.string.tamper_signature_unreadable
    },
)

/**
 * The security centre. Five real checks tick in, one after another, each only once its own answer is in; a row is never ticked
 * for something that didn't run or didn't pass, and sandbox or unconfigured parts say so in a neutral voice. Each row opens a
 * sheet with what it means and what to do. One primary action: the most important next step, when there is one.
 */
@Composable
fun SecurityScreen(state: SecurityUiState, callbacks: SecurityCallbacks) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val checks = SecurityChecks.of(state)
    val revealed = rememberCheckReveal(checks, motion.reduced)
    val resolved = checks.map { it.verdict != CheckVerdict.Checking }
    val shownChecks = checks.mapIndexed { i, check ->
        if (CheckReveal.shown(i, revealed.value, resolved, motion.reduced)) check else SecurityCheck(check.id, CheckVerdict.Checking, CheckDetail.Checking)
    }
    val summary = SecurityChecks.summary(shownChecks)
    val nextStep = SecurityChecks.nextStep(shownChecks)
    var openCheck by rememberSaveable { mutableStateOf<CheckId?>(null) }
    var explainOpen by rememberSaveable { mutableStateOf(false) }
    val blockReasons = TamperWarningLogic.blockReasons(state.tamper, state.integrity)
    // The warning follows the facts: once a re-scan comes back clean, it has nothing left to explain.
    LaunchedEffect(blockReasons.isEmpty()) { if (blockReasons.isEmpty()) explainOpen = false }
    val removal = state.removal
    val overlayOpen = openCheck != null || removal != null || explainOpen

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().then(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            PayFlowTopBar(title = stringResource(R.string.security_title), onBack = callbacks.onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                SummaryHero(summary)
                PayFlowSurface(Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_CHECKS), level = SurfaceLevel.Card) {
                    Column {
                        shownChecks.forEachIndexed { index, check ->
                            if (index > 0) HairlineDivider(Modifier.padding(start = Spacing.md + Sizes.iconLarge + Spacing.sm))
                            CheckRow(check, state, onClick = { openCheck = check.id })
                        }
                    }
                }
                if (nextStep != null) {
                    NextStepButton(nextStep, callbacks, onExplain = { explainOpen = true }, onBind = { openCheck = CheckId.BINDING })
                }
                SectionHeader(stringResource(R.string.security_mode_title), overline = stringResource(R.string.security_mode_overline))
                PaymentModeSection(state, callbacks)
                Text(
                    stringResource(R.string.security_sandbox_note),
                    style = PayFlowTheme.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = Spacing.xl),
                )
            }
        }
        CheckSheet(openCheck, state, callbacks, onDismiss = { openCheck = null }, onExplain = { openCheck = null; explainOpen = true })
        RemovePinSheet(removal, callbacks)
        TamperWarningOverlay(
            visible = explainOpen && blockReasons.isNotEmpty(),
            reasons = blockReasons,
            onClose = { explainOpen = false },
            onRecheck = callbacks.onRefresh,
        )
    }
}

/** Reveals rows one [CheckReveal.STEP_MS] apart on the frame clock; waits without frames while a row's answer is out. */
@Composable
private fun rememberCheckReveal(checks: List<SecurityCheck>, reduced: Boolean): State<Int> {
    val revealed = remember { mutableIntStateOf(0) }
    val resolved by rememberUpdatedState(checks.map { it.verdict != CheckVerdict.Checking })
    LaunchedEffect(reduced) {
        if (reduced) {
            revealed.intValue = checks.size
            return@LaunchedEffect
        }
        val reveal = CheckReveal(initial = revealed.intValue)
        while (!reveal.done(checks.size)) {
            snapshotFlow { resolved.getOrElse(reveal.revealed) { false } }.first { it }
            var moved = false
            while (!moved) moved = withFrameMillis { now -> reveal.advance(now, resolved) }
            revealed.intValue = reveal.revealed
        }
    }
    return revealed
}

@Composable
private fun SummaryHero(summary: SecuritySummary) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val title = when (summary) {
        SecuritySummary.Checking -> stringResource(R.string.security_summary_checking)
        SecuritySummary.AllClear -> stringResource(R.string.security_summary_clear)
        is SecuritySummary.ClearWithNotes -> stringResource(R.string.security_summary_clear_notes)
        is SecuritySummary.NeedsYou -> pluralStringResource(R.plurals.security_summary_needs_you, summary.count, summary.count)
        SecuritySummary.PaymentsOff -> stringResource(R.string.security_summary_payments_off)
    }
    val accent = when (summary) {
        SecuritySummary.Checking -> colors.accent
        SecuritySummary.AllClear -> colors.success.strong
        is SecuritySummary.ClearWithNotes -> colors.accent
        is SecuritySummary.NeedsYou -> colors.pending.strong
        SecuritySummary.PaymentsOff -> colors.failed.strong
    }
    PayFlowSurface(Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_SUMMARY), level = SurfaceLevel.Hero, shape = Radii.largeShape) {
        Column(
            Modifier.padding(Spacing.lg).semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.security_summary_overline), style = type.label, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA))
            Text(title, style = type.display, color = colors.onHero)
            Spacer(Modifier.height(Spacing.xxs))
            Box(Modifier.size(width = Sizes.sheetHandleWidth, height = Sizes.sheetHandleHeight).background(accent, Radii.pillShape))
        }
    }
}

private const val HERO_SECONDARY_ALPHA = 0.72f

@Composable
private fun CheckId.title(): String = stringResource(
    when (this) {
        CheckId.BINDING -> R.string.security_check_binding
        CheckId.DEVICE -> R.string.security_check_device
        CheckId.INTEGRITY -> R.string.security_check_integrity
        CheckId.APP_LOCK -> R.string.security_check_app_lock
        CheckId.BIOMETRIC -> R.string.security_check_biometric
    },
)

@Composable
private fun CheckDetail.text(state: SecurityUiState): String = when (this) {
    CheckDetail.Checking -> stringResource(R.string.security_detail_checking)
    CheckDetail.BindingBound -> stringResource(R.string.security_bound, (state.binding as? BindingStatus.Bound)?.maskedPhone.orEmpty())
    CheckDetail.BindingUnbound -> stringResource(R.string.security_detail_unbound)
    CheckDetail.BindingOtpPending -> stringResource(R.string.security_otp_sent, (state.binding as? BindingStatus.OtpPending)?.maskedPhone.orEmpty())
    CheckDetail.BindingMismatch -> stringResource(R.string.security_binding_mismatch, (state.binding as? BindingStatus.Mismatch)?.maskedPhone.orEmpty())
    CheckDetail.DeviceClean -> stringResource(R.string.security_tamper_clean)
    CheckDetail.DeviceDebug -> stringResource(R.string.security_detail_debug)
    CheckDetail.DeviceWarnings -> stringResource(R.string.security_detail_warnings)
    CheckDetail.DeviceBlocked -> stringResource(R.string.security_tamper_blocked)
    CheckDetail.IntegrityVerified -> stringResource(R.string.security_integrity_ok)
    CheckDetail.IntegritySandbox -> stringResource(R.string.security_detail_integrity_sandbox)
    CheckDetail.IntegrityNotConfigured -> stringResource(R.string.security_detail_integrity_not_configured)
    CheckDetail.IntegrityUnevaluated -> stringResource(R.string.security_integrity_unevaluated)
    CheckDetail.IntegrityError -> stringResource(R.string.security_integrity_error, (state.integrity as? IntegrityStatus.Error)?.code.orEmpty())
    CheckDetail.IntegrityFailed -> stringResource(R.string.security_integrity_failed)
    CheckDetail.AppLockOn -> stringResource(R.string.security_pin_on)
    CheckDetail.AppLockOff -> stringResource(R.string.security_pin_off)
    CheckDetail.BiometricReady -> stringResource(R.string.security_detail_biometric_ready)
    CheckDetail.BiometricNeedsPin -> stringResource(R.string.security_detail_biometric_needs_pin)
    CheckDetail.BiometricNotEnrolled -> stringResource(R.string.security_detail_biometric_not_enrolled)
    CheckDetail.BiometricUnavailable -> stringResource(R.string.security_detail_biometric_unavailable)
}

@Composable
private fun CheckVerdict.label(): String = stringResource(
    when (this) {
        CheckVerdict.Checking -> R.string.security_verdict_checking
        CheckVerdict.Passed -> R.string.security_verdict_passed
        CheckVerdict.Attention -> R.string.security_verdict_attention
        CheckVerdict.Failed -> R.string.security_verdict_failed
        CheckVerdict.Info -> R.string.security_verdict_info
    },
)

@Composable
private fun CheckRow(check: SecurityCheck, state: SecurityUiState, onClick: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val press = rememberPressFeedback()
    val interaction = remember { MutableInteractionSource() }
    val title = check.id.title()
    val detail = check.detail.text(state)
    val verdict = check.verdict.label()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .testTag(PayFlowTestTags.securityCheck(check.id.name))
            .trackPress(press)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .pressScale(press, pressedScale = ROW_PRESSED_SCALE)
            .drawBehind { drawRect(colors.raised, alpha = press.progress.coerceIn(0f, 1f)) }
            .clearAndSetSemantics { contentDescription = "$title. $verdict. $detail" }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CheckGlyph(check.verdict)
        Column(Modifier.weight(1f)) {
            Text(title, style = type.bodyStrong, color = colors.text)
            Text(detail, style = type.label, color = colors.textSecondary, maxLines = 3)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.icon))
    }
}

private const val ROW_PRESSED_SCALE = 0.985f

/**
 * The tick itself. A pass draws its check, a failure its cross, attention an exclamation, info a level dash; each draws once
 * when the row's verdict arrives. Under reduced motion it's simply there.
 */
@Composable
private fun CheckGlyph(verdict: CheckVerdict) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val tone = when (verdict) {
        CheckVerdict.Passed -> colors.success
        CheckVerdict.Failed -> colors.failed
        CheckVerdict.Attention -> colors.pending
        CheckVerdict.Info, CheckVerdict.Checking -> colors.neutral
    }
    val progress = remember(verdict) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(verdict, motion.reduced) {
        if (motion.reduced) progress.snapTo(1f) else progress.animateTo(1f, motion.spec(MotionToken.Small))
    }
    Box(
        Modifier
            .size(Sizes.iconLarge)
            .testTag(PayFlowTestTags.securityGlyph(verdict.name))
            .background(if (verdict == CheckVerdict.Checking) colors.raised else tone.container, Radii.pillShape),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Modifier.size(Sizes.icon)
        when (verdict) {
            CheckVerdict.Checking -> BrandLoader(size = Sizes.loaderSmall)
            CheckVerdict.Passed -> DrawnCheckmark({ progress.value }, tone.strong, glyph)
            CheckVerdict.Failed -> DrawnCross({ progress.value }, tone.strong, glyph)
            CheckVerdict.Attention -> Spacer(
                glyph.drawBehind {
                    val p = progress.value
                    val stroke = size.width * GLYPH_STROKE
                    val top = Offset(size.width / 2f, size.height * 0.2f)
                    val end = Offset(size.width / 2f, size.height * (0.2f + 0.38f * p))
                    drawLine(tone.strong, top, end, strokeWidth = stroke, cap = StrokeCap.Round)
                    if (p > DOT_AT) drawCircle(tone.strong, radius = stroke * 0.6f, center = Offset(size.width / 2f, size.height * 0.8f))
                },
            )
            CheckVerdict.Info -> Spacer(
                glyph.drawBehind {
                    val stroke = size.width * GLYPH_STROKE
                    val half = size.width * 0.28f * progress.value
                    val y = size.height / 2f
                    drawLine(tone.strong, Offset(size.width / 2f - half, y), Offset(size.width / 2f + half, y), strokeWidth = stroke, cap = StrokeCap.Round)
                },
            )
        }
    }
}

private const val GLYPH_STROKE = 0.11f
private const val DOT_AT = 0.85f

@Composable
private fun NextStepButton(step: NextStep, callbacks: SecurityCallbacks, onExplain: () -> Unit, onBind: () -> Unit) {
    val (label, action, tag) = when (step) {
        NextStep.ExplainBlock -> Triple(stringResource(R.string.security_next_explain), onExplain, PayFlowTestTags.SECURITY_NEXT_STEP)
        NextStep.SetUpLock -> Triple(stringResource(R.string.security_pin_setup), callbacks.onSetupPin, PayFlowTestTags.SECURITY_SETUP_PIN)
        NextStep.BindPhone -> Triple(stringResource(R.string.security_next_bind), onBind, PayFlowTestTags.SECURITY_NEXT_STEP)
        NextStep.CheckIntegrity -> Triple(stringResource(R.string.security_integrity_check), callbacks.onCheckIntegrity, PayFlowTestTags.SECURITY_NEXT_STEP)
    }
    PrimaryButton(label, action, Modifier.fillMaxWidth().testTag(tag))
}

/** Two radio rows with the honest explanation of each mode; UPI apps is disabled when no handler is installed. */
@Composable
private fun PaymentModeSection(state: SecurityUiState, callbacks: SecurityCallbacks) {
    PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Card) {
        Column(Modifier.padding(vertical = Spacing.xxs)) {
            ModeOption(
                selected = state.paymentMode == PaymentMode.UPI_APPS,
                enabled = state.upiAppAvailable,
                title = stringResource(R.string.security_mode_upi),
                hint = stringResource(if (state.upiAppAvailable) R.string.security_mode_upi_hint else R.string.security_mode_no_apps),
                tag = PayFlowTestTags.SECURITY_MODE_UPI,
                onClick = { callbacks.onPaymentMode(PaymentMode.UPI_APPS) },
            )
            HairlineDivider(Modifier.padding(start = Spacing.md + Sizes.icon + Spacing.sm))
            ModeOption(
                selected = state.paymentMode == PaymentMode.SANDBOX,
                enabled = true,
                title = stringResource(R.string.security_mode_sandbox),
                hint = stringResource(R.string.security_mode_sandbox_hint),
                tag = PayFlowTestTags.SECURITY_MODE_SANDBOX,
                onClick = { callbacks.onPaymentMode(PaymentMode.SANDBOX) },
                badge = { if (state.paymentMode == PaymentMode.SANDBOX) SandboxBadge() },
            )
            if (state.pinConfigured) {
                Text(
                    stringResource(R.string.security_mode_locked_hint),
                    style = PayFlowTheme.type.label,
                    color = PayFlowTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, enabled: Boolean, title: String, hint: String, tag: String, onClick: () -> Unit, badge: @Composable () -> Unit = {}) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val fill = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { fill.animateTo(if (selected) 1f else 0f, motion.spec(MotionToken.Bounce)) }
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val ring = if (enabled) colors.accent else colors.outline
        Spacer(
            Modifier.size(Sizes.icon).drawBehind {
                val stroke = Hairline.width.toPx() * 2f
                drawCircle(if (selected) ring else colors.outline, radius = size.minDimension / 2f - stroke, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                val inner = size.minDimension / 4f * fill.value
                if (inner > 0f) drawCircle(ring, radius = inner)
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = PayFlowTheme.type.bodyStrong, color = if (enabled) colors.text else colors.textSecondary)
                badge()
            }
            Text(hint, style = PayFlowTheme.type.label, color = colors.textSecondary)
        }
    }
}

// ---- per-check sheets

@Composable
private fun CheckSheet(open: CheckId?, state: SecurityUiState, callbacks: SecurityCallbacks, onDismiss: () -> Unit, onExplain: () -> Unit) {
    // The last opened check keeps drawing while the sheet leaves.
    var shown by remember { mutableStateOf(open) }
    if (open != null) shown = open
    PayFlowBottomSheet(visible = open != null, onDismissRequest = onDismiss, modifier = Modifier.testTag(PayFlowTestTags.SECURITY_CHECK_SHEET)) {
        val id = shown ?: return@PayFlowBottomSheet
        val check = SecurityChecks.of(state).first { it.id == id }
        SheetColumn {
            SheetHeading(id.title(), check.verdict.label())
            when (id) {
                CheckId.BINDING -> BindingSection(state, callbacks)
                CheckId.DEVICE -> DeviceSection(state, callbacks, onExplain)
                CheckId.INTEGRITY -> IntegritySection(state, check, callbacks)
                CheckId.APP_LOCK -> AppLockSection(state, callbacks, onDismiss)
                CheckId.BIOMETRIC -> BiometricSection(state, check, callbacks)
            }
        }
    }
}

/** Sheet content sits on the screen gutter, like every other sheet in the app. */
@Composable
private fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}

@Composable
private fun SheetHeading(title: String, verdict: String) {
    Text(title, style = PayFlowTheme.type.title, color = PayFlowTheme.colors.text, modifier = Modifier.semantics { heading() })
    Text(verdict, style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary, modifier = Modifier.padding(bottom = Spacing.xs))
}

@Composable
private fun SheetBody(text: String) {
    Text(text, style = PayFlowTheme.type.body, color = PayFlowTheme.colors.text)
}

@Composable
private fun BindingSection(state: SecurityUiState, callbacks: SecurityCallbacks) {
    val colors = PayFlowTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (val b = state.binding) {
            is BindingStatus.Bound -> {
                SheetBody(stringResource(R.string.security_bound, b.maskedPhone))
                GhostButton(stringResource(R.string.security_unbind), callbacks.onUnbind, Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_UNBIND), tone = GhostTone.Danger)
            }
            is BindingStatus.Mismatch -> {
                SheetBody(stringResource(R.string.security_binding_mismatch, b.maskedPhone))
                PrimaryButton(stringResource(R.string.security_rebind), callbacks.onUnbind, Modifier.fillMaxWidth())
            }
            is BindingStatus.OtpPending -> {
                SheetBody(stringResource(R.string.security_otp_sent, b.maskedPhone))
                state.sandboxOtp?.let { code ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.pending.container, Radii.smallShape)
                            .padding(Spacing.sm)
                            .testTag(PayFlowTestTags.SECURITY_SANDBOX_OTP),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Sms, contentDescription = null, tint = colors.pending.content, modifier = Modifier.size(Sizes.icon))
                        Text(stringResource(R.string.security_sandbox_otp, code), style = PayFlowTheme.type.bodyStrong, color = colors.pending.content)
                    }
                }
                PayFlowTextField(
                    value = state.otpInput,
                    onValueChange = callbacks.onOtpChange,
                    label = stringResource(R.string.security_otp),
                    enabled = !state.busy,
                    error = state.bindingError?.let { stringResource(if (it == BindingError.WRONG_OTP) R.string.security_otp_wrong else R.string.security_otp_expired) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    fieldTag = PayFlowTestTags.SECURITY_OTP,
                )
                PrimaryButton(
                    stringResource(R.string.security_verify),
                    callbacks.onVerifyOtp,
                    Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_VERIFY_OTP),
                    enabled = state.otpInput.length == 6,
                    loading = state.busy,
                )
                GhostButton(stringResource(R.string.security_resend), callbacks.onRequestOtp, Modifier.fillMaxWidth(), enabled = !state.busy, outlined = false)
            }
            BindingStatus.Unbound -> {
                SheetBody(stringResource(R.string.security_unbound))
                PayFlowTextField(
                    value = state.phoneInput,
                    onValueChange = callbacks.onPhoneChange,
                    label = stringResource(R.string.security_phone),
                    placeholder = stringResource(R.string.security_phone_placeholder),
                    enabled = !state.busy,
                    error = if (state.bindingError == BindingError.INVALID_PHONE) stringResource(R.string.security_phone_invalid) else null,
                    leading = { Icon(Icons.Outlined.Phone, contentDescription = null, modifier = Modifier.size(Sizes.icon)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    fieldTag = PayFlowTestTags.SECURITY_PHONE,
                )
                PrimaryButton(stringResource(R.string.security_send_otp), callbacks.onRequestOtp, Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_SEND_OTP), loading = state.busy)
            }
        }
    }
}

@Composable
private fun DeviceSection(state: SecurityUiState, callbacks: SecurityCallbacks, onExplain: () -> Unit) {
    val report = state.tamper
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when {
            report == null -> BrandLoader(Modifier.align(Alignment.CenterHorizontally))
            report.findings.isEmpty() -> SheetBody(stringResource(R.string.security_tamper_clean))
            else -> report.findings.forEach { finding ->
                Text("• " + finding.label(), style = PayFlowTheme.type.body, color = PayFlowTheme.colors.text)
            }
        }
        if (report != null && !report.shouldBlockPayments && report.findings.isNotEmpty()) {
            Text(stringResource(R.string.security_tamper_debug_hint), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
        }
        if (report?.shouldBlockPayments == true) {
            PrimaryButton(stringResource(R.string.security_next_explain), onExplain, Modifier.fillMaxWidth())
        }
        GhostButton(stringResource(R.string.security_rescan), callbacks.onRefresh, Modifier.fillMaxWidth(), outlined = report?.shouldBlockPayments != true)
    }
}

@Composable
private fun IntegritySection(state: SecurityUiState, check: SecurityCheck, callbacks: SecurityCallbacks) {
    val checking = state.integrity == IntegrityStatus.Checking
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SheetBody(check.detail.text(state))
        Text(stringResource(R.string.security_integrity_explainer), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
        GhostButton(
            stringResource(R.string.security_integrity_check),
            callbacks.onCheckIntegrity,
            Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_INTEGRITY),
            enabled = !checking,
            loading = checking,
        )
    }
}

@Composable
private fun AppLockSection(state: SecurityUiState, callbacks: SecurityCallbacks, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.pinConfigured) {
            SheetBody(stringResource(R.string.security_pin_on_detail))
            GhostButton(
                stringResource(R.string.security_pin_remove),
                { onDismiss(); callbacks.onStartRemoval() },
                Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_REMOVE_PIN),
                tone = GhostTone.Danger,
            )
        } else {
            SheetBody(stringResource(R.string.security_pin_off_detail))
            PrimaryButton(stringResource(R.string.security_pin_setup), { onDismiss(); callbacks.onSetupPin() }, Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_SETUP_PIN))
        }
    }
}

@Composable
private fun BiometricSection(state: SecurityUiState, check: SecurityCheck, callbacks: SecurityCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SheetBody(check.detail.text(state))
        Text(stringResource(R.string.security_biometric_explainer), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
        if (check.detail == CheckDetail.BiometricNeedsPin) {
            PrimaryButton(stringResource(R.string.security_pin_setup), callbacks.onSetupPin, Modifier.fillMaxWidth())
        }
    }
}

/**
 * "Turn off app lock" asks for the current PIN or a biometric. The field clears after every refused attempt, the count of
 * attempts left is shown, and a lockout says how long. The sheet can't be dragged away mid-check.
 */
@Composable
private fun RemovePinSheet(removal: PinRemovalState?, callbacks: SecurityCallbacks) {
    val colors = PayFlowTheme.colors
    var last by remember { mutableStateOf(removal) }
    if (removal != null) last = removal
    PayFlowBottomSheet(
        visible = removal != null,
        onDismissRequest = callbacks.onCancelRemoval,
        dismissible = removal?.verifying != true,
        modifier = Modifier.testTag(PayFlowTestTags.SECURITY_REMOVE_SHEET),
    ) {
        val r = last ?: return@PayFlowBottomSheet
        SheetColumn { RemovePinContent(r, callbacks) }
    }
}

@Composable
private fun RemovePinContent(r: PinRemovalState, callbacks: SecurityCallbacks) {
    val colors = PayFlowTheme.colors
    run {
        var pin by remember(r.attempts) { mutableStateOf("") }
        val lockedOut = r.error == PinRemovalError.LOCKED_OUT && r.lockoutRemainingMillis > 0
        Text(stringResource(R.string.security_remove_title), style = PayFlowTheme.type.title, color = colors.text, modifier = Modifier.semantics { heading() })
        SheetBody(stringResource(R.string.security_remove_body))
        PayFlowTextField(
            value = pin,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(PinManager.PIN_LENGTH)
                pin = digits
                if (digits.length == PinManager.PIN_LENGTH) callbacks.onConfirmRemoval(digits)
            },
            label = stringResource(R.string.security_remove_pin_label),
            enabled = !r.verifying && !lockedOut,
            error = when (r.error) {
                PinRemovalError.WRONG_PIN -> pluralStringResource(R.plurals.security_remove_wrong, r.remainingAttempts, r.remainingAttempts)
                PinRemovalError.LOCKED_OUT -> stringResource(R.string.security_remove_locked, ((r.lockoutRemainingMillis + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1))
                PinRemovalError.BIOMETRIC_FAILED -> stringResource(R.string.security_remove_biometric_failed)
                null -> null
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            fieldTag = PayFlowTestTags.SECURITY_REMOVE_PIN_FIELD,
        )
        Spacer(Modifier.height(Spacing.sm))
        GhostButton(
            stringResource(R.string.security_remove_confirm),
            { callbacks.onConfirmRemoval(pin) },
            Modifier.fillMaxWidth().testTag(PayFlowTestTags.SECURITY_REMOVE_PIN_CONFIRM),
            enabled = pin.length == PinManager.PIN_LENGTH && !lockedOut,
            loading = r.verifying,
            tone = GhostTone.Danger,
        )
        if (r.biometricAvailable) {
            GhostButton(
                stringResource(R.string.security_remove_biometric),
                callbacks.onRemoveWithBiometric,
                Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag(PayFlowTestTags.SECURITY_REMOVE_PIN_BIOMETRIC),
                enabled = !r.verifying,
                outlined = false,
                leadingIcon = Icons.Outlined.Fingerprint,
            )
        }
    }
}

private const val MINUTE_MS = 60_000L
