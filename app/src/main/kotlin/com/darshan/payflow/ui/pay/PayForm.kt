package com.darshan.payflow.ui.pay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.model.SandboxDemoPayees
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountOdometer
import com.darshan.payflow.ui.designsystem.components.AvatarBadge
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.DrawnCheckmark
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.ShakeCurve
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.modifiers.pressScale
import com.darshan.payflow.ui.designsystem.modifiers.rememberPressFeedback
import com.darshan.payflow.ui.designsystem.modifiers.trackPress
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.theme.PayFlowTheme

/** The shared-element key of the payee: the card that grows out of a scanned QR is the one on the Pay screen. */
const val PAYEE_CONTAINER_KEY = "payee"

private val QUICK_AMOUNTS = listOf(100, 500, 1_000, 2_000)

/** Payee, amount, keypad, slider. The form scrolls; the slider stays reachable, whatever the keyboard or window height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayFormContent(state: PayUiState, callbacks: PayCallbacks, onConfirmRequest: () -> Unit, modifier: Modifier = Modifier) {
    val busy = state.phase !is PayPhase.Idle
    val imeOpen = WindowInsets.isImeVisible
    val showKeypad = !state.amountLocked && !imeOpen
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val dockKeypad = maxHeight >= Sizes.keypadDockMinHeight
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                PayeeSection(state, callbacks, busy)
                AmountSection(state)
                if (!state.amountLocked) QuickAmounts(state, callbacks, busy)
                NoteField(state, callbacks, busy)
                if (showKeypad && !dockKeypad) AmountKeypad(!busy, callbacks.onAmountKey)
                Spacer(Modifier.height(Spacing.xs))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screen)
                    .padding(bottom = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (showKeypad && dockKeypad) AmountKeypad(!busy, callbacks.onAmountKey)
                PaySlider(state, callbacks, onConfirmRequest)
                ModeCaption(state.paymentMode)
            }
        }
    }
}

@Composable
private fun PaySlider(state: PayUiState, callbacks: PayCallbacks, onConfirmRequest: () -> Unit) {
    val block = PayReadiness.blockOf(state)
    val processing = PayBackPolicy.isRunning(state.phase)
    val idle = state.phase is PayPhase.Idle
    val label = when {
        processing -> stringResource(R.string.pay_swipe_starting)
        block != null -> block.message()
        else -> stringResource(R.string.pay_swipe)
    }
    val paise = state.amountPaise ?: 0L
    val payee = DisplayText.safe(state.name.ifBlank { state.vpa })
    val description = if (block == null && !processing) stringResource(R.string.pay_swipe_description, Money.formatInr(paise), payee) else label
    SwipeToPay(
        label = label,
        description = description,
        enabled = block == null && idle,
        processing = processing,
        refusals = state.attemptsRefused,
        onCommit = callbacks.onSubmit,
        onPlainAction = onConfirmRequest,
    )
    GhostButton(
        stringResource(R.string.pay_use_button),
        onConfirmRequest,
        outlined = false,
        tone = GhostTone.Neutral,
        enabled = block == null && idle,
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.PAY_PLAIN_ACTION),
    )
}

@Composable
private fun PayBlock.message(): String = when (this) {
    PayBlock.ENTER_PAYEE -> stringResource(R.string.pay_block_enter_payee)
    PayBlock.INVALID_PAYEE -> stringResource(R.string.pay_block_invalid_payee)
    PayBlock.PAYEE_NOT_FOUND -> stringResource(R.string.pay_block_not_found)
    PayBlock.CHECKING_PAYEE -> stringResource(R.string.pay_block_checking)
    PayBlock.ENTER_NAME -> stringResource(R.string.pay_block_enter_name)
    PayBlock.ENTER_AMOUNT -> stringResource(R.string.pay_block_enter_amount)
    PayBlock.AMOUNT_TOO_LARGE -> stringResource(R.string.pay_block_too_large)
}

@Composable
private fun PayeeSection(state: PayUiState, callbacks: PayCallbacks, busy: Boolean) {
    if (state.payeeLocked) {
        LockedPayeeCard(state)
    } else {
        EditablePayee(state, callbacks, busy)
    }
}

/** A payee from a QR or link can't be edited. Name and ID are its own rows, with the reason they're locked. */
@Composable
private fun LockedPayeeCard(state: PayUiState) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val lockedLabel = stringResource(R.string.pay_field_locked)
    val name = DisplayText.safe(state.name)
    PayFlowSurface(Modifier.fillMaxWidth().containerTransform(PAYEE_CONTAINER_KEY).testTag(PayFlowTestTags.PAY_PAYEE_CARD)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PayFlowAvatar(name, badge = if (state.verification is VpaVerificationState.Verified) AvatarBadge.Verified else null)
                Column(Modifier.weight(1f)) {
                    LockedRow(name, type.bodyStrong, colors.text, PayFlowTestTags.PAY_NAME, lockedLabel)
                    LockedRow(DisplayText.safe(state.vpa), type.label, colors.textSecondary, PayFlowTestTags.PAY_VPA, lockedLabel)
                }
            }
            VerificationLine(state)
        }
    }
}

@Composable
private fun LockedRow(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, tag: String, lockedLabel: String) {
    Row(
        Modifier.fillMaxWidth().semantics { disabled() }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(text, style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Outlined.Lock, contentDescription = lockedLabel, tint = PayFlowTheme.colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
    }
}

/** What the directory said. The check draws itself along its path, and the name fades in beside it. */
@Composable
private fun VerificationLine(state: PayUiState) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    when (val v = state.verification) {
        VpaVerificationState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            BrandLoader(size = Sizes.iconSmall)
            Text(stringResource(R.string.pay_verifying), style = type.label, color = colors.textSecondary)
        }
        is VpaVerificationState.Verified -> {
            val draw = remember { Animatable(if (motion.reduced) 1f else 0f) }
            LaunchedEffect(Unit) { draw.animateTo(1f, motion.spec(MotionToken.Small)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                DrawnCheckmark({ draw.value }, colors.success.strong, Modifier.size(Sizes.iconSmall))
                Text(
                    stringResource(R.string.pay_verified, DisplayText.safe(v.payeeName)),
                    style = type.label,
                    color = colors.success.strong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = draw.value }.testTag(PayFlowTestTags.PAY_VERIFIED),
                )
            }
        }
        VpaVerificationState.NotFound -> Text(stringResource(R.string.error_vpa_not_found), style = type.label, color = colors.failed.strong)
        VpaVerificationState.Unavailable -> Text(stringResource(R.string.pay_verify_unavailable), style = type.label, color = colors.pending.strong)
        VpaVerificationState.Skipped -> Text(stringResource(R.string.pay_vpa_hint_real), style = type.label, color = colors.textSecondary)
        VpaVerificationState.Idle -> Unit
    }
}

@Composable
private fun EditablePayee(state: PayUiState, callbacks: PayCallbacks, busy: Boolean) {
    val verified = state.verification as? VpaVerificationState.Verified
    val realMode = state.paymentMode == PaymentMode.UPI_APPS
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PayFlowTextField(
            value = state.vpa,
            onValueChange = callbacks.onVpaChange,
            label = stringResource(R.string.pay_vpa),
            placeholder = stringResource(R.string.pay_vpa_placeholder),
            error = state.errors.vpa?.message(),
            enabled = !busy,
            trailing = if (state.verification is VpaVerificationState.Verifying) ({ BrandLoader(size = Sizes.loaderSmall) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            fieldTag = PayFlowTestTags.PAY_VPA,
        )
        if (state.errors.vpa == null) VerificationHint(state)
        if (state.vpa.isBlank() && state.paymentMode == PaymentMode.SANDBOX) DemoPayees(callbacks.onVpaChange, !busy)
        AnimatedVisibility(verified != null) { if (verified != null) RegisteredName(verified.payeeName) }
        if (verified == null) {
            PayFlowTextField(
                value = state.name,
                onValueChange = callbacks.onNameChange,
                label = stringResource(if (realMode) R.string.pay_payee_name_optional else R.string.pay_payee_name),
                error = state.errors.name?.message(),
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                fieldTag = PayFlowTestTags.PAY_NAME,
            )
        }
    }
}

@Composable
private fun VerificationHint(state: PayUiState) {
    if (state.verification is VpaVerificationState.Idle) {
        Text(stringResource(R.string.pay_vpa_hint), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
    } else {
        VerificationLine(state)
    }
}

/** The directory's name replaces whatever was typed, and can't be edited. */
@Composable
private fun RegisteredName(name: String) {
    val colors = PayFlowTheme.colors
    val safe = DisplayText.safe(name)
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xxs).semantics { disabled() }.testTag(PayFlowTestTags.PAY_NAME),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PayFlowAvatar(safe, badge = AvatarBadge.Verified)
        Text(safe, style = PayFlowTheme.type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_name_registered), tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
    }
}

/** Sandbox only: one tap to each outcome, so the whole flow can be walked without knowing the magic payees. */
@Composable
private fun DemoPayees(onPick: (String) -> Unit, enabled: Boolean) {
    val demos = listOf(
        R.string.pay_demo_success to SandboxDemoPayees.SUCCESS,
        R.string.pay_demo_slow to SandboxDemoPayees.SLOW,
        R.string.pay_demo_stuck to SandboxDemoPayees.STUCK,
        R.string.pay_demo_declined to SandboxDemoPayees.DECLINED,
        R.string.pay_demo_refund to SandboxDemoPayees.REFUNDED,
        R.string.pay_demo_mismatch to SandboxDemoPayees.MISMATCH,
    )
    Text(stringResource(R.string.pay_demo_title), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        demos.forEach { (label, vpa) ->
            PayFlowChoiceChip(stringResource(label), selected = false, onClick = { onPick(vpa) }, enabled = enabled, modifier = Modifier.testTag(PayFlowTestTags.payDemo(vpa)))
        }
    }
}

/** The big number. Digits roll in, it shrinks to fit, and a refused key nudges it. */
@Composable
private fun AmountSection(state: PayUiState) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val paise = state.amountPaise?.takeIf { it > 0 }
    val amountText = paise?.let(Money::formatInr) ?: stringResource(R.string.pay_amount_placeholder)
    val spoken = stringResource(R.string.pay_amount_display, amountText)
    val nudge = remember { Animatable(1f) }
    val seen = remember { intArrayOf(state.amountNudges) }
    val shakePx = with(LocalDensity.current) { Spacing.xs.toPx() }
    LaunchedEffect(state.amountNudges) {
        if (state.amountNudges != seen[0]) {
            seen[0] = state.amountNudges
            if (!motion.reduced) {
                nudge.snapTo(0f)
                nudge.animateTo(1f, motion.spec(MotionToken.Shake))
            }
        }
    }
    val locked = state.amountLocked
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (locked) Modifier.semantics { disabled() } else Modifier)
            .testTag(PayFlowTestTags.PAY_AMOUNT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = ShakeCurve.offset(nudge.value) * shakePx }
                .testTag(PayFlowTestTags.PAY_AMOUNT_DISPLAY)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    text = AnnotatedString(amountText)
                },
            contentAlignment = Alignment.Center,
        ) {
            AmountOdometer(
                paise = paise ?: 0L,
                style = type.hero,
                color = if (paise != null) colors.text else colors.textSecondary,
                decimals = if (locked || AmountInput.showsDecimals(state.amountText)) AmountDecimals.Always else AmountDecimals.WhenNonZero,
            )
        }
        val error = state.errors.amount?.message()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            if (locked) Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pay_field_locked), tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
            Text(
                error ?: if (locked) stringResource(R.string.pay_amount_locked_hint) else stringResource(R.string.pay_amount_hint, Money.formatInr(Payment.MAX_AMOUNT_PAISE)),
                style = type.label,
                color = if (error != null) colors.failed.strong else colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickAmounts(state: PayUiState, callbacks: PayCallbacks, busy: Boolean) {
    val current = state.amountPaise
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally)) {
        QUICK_AMOUNTS.forEach { rupees ->
            PayFlowChoiceChip(
                label = "₹%,d".format(rupees),
                selected = current == rupees * 100L,
                onClick = { callbacks.onAmountChange(rupees.toString()) },
                enabled = !busy,
                modifier = Modifier.testTag(PayFlowTestTags.payQuick(rupees)),
            )
        }
    }
}

@Composable
private fun NoteField(state: PayUiState, callbacks: PayCallbacks, busy: Boolean) {
    PayFlowTextField(
        value = state.note,
        onValueChange = callbacks.onNoteChange,
        label = stringResource(R.string.pay_note),
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        fieldTag = PayFlowTestTags.PAY_NOTE,
    )
}

@Composable
private fun ModeCaption(mode: PaymentMode?) {
    val text = when (mode) {
        PaymentMode.SANDBOX -> stringResource(R.string.pay_mode_caption_sandbox)
        PaymentMode.UPI_APPS -> stringResource(R.string.pay_mode_caption_real)
        null -> return
    }
    Text(text, style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** Rows of [AmountKey]. No debounce on purpose: fast typing is legitimate, and the ViewModel reads the latest text per press. */
@Composable
fun AmountKeypad(enabled: Boolean, onKey: (AmountKey) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        AmountKey.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key -> KeypadKey(key, enabled, onKey, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeypadKey(key: AmountKey, enabled: Boolean, onKey: (AmountKey) -> Unit, modifier: Modifier) {
    val colors = PayFlowTheme.colors
    val press = rememberPressFeedback()
    val interaction = remember { MutableInteractionSource() }
    val description = when (key) {
        AmountKey.Dot -> stringResource(R.string.pay_key_dot)
        AmountKey.Backspace -> stringResource(R.string.pay_key_backspace)
        else -> key.label
    }
    Box(
        modifier
            .height(Sizes.keypadKey)
            .pressScale(press)
            .clip(Radii.mediumShape)
            .drawBehind { drawRect(colors.raised, alpha = press.progress) }
            .trackPress(press, enabled)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) { onKey(key) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(PayFlowTestTags.payKey(key.name)),
        contentAlignment = Alignment.Center,
    ) {
        if (key == AmountKey.Backspace) {
            Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = null, tint = colors.text, modifier = Modifier.size(Sizes.icon))
        } else {
            Text(key.label, style = PayFlowTheme.type.amount, color = if (enabled) colors.text else colors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
