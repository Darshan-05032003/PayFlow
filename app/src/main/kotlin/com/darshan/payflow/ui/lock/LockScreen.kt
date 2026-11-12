package com.darshan.payflow.ui.lock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.darshan.payflow.R
import com.darshan.payflow.data.security.PinManager
import com.darshan.payflow.data.security.PinRule
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.ShakeCurve
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LockCallbacks(
    val onDigit: (String) -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onBiometric: () -> Unit = {},
    val onTick: () -> Unit = {},
    val onSetupDigit: (String) -> Unit = {},
    val onSetupBackspace: () -> Unit = {},
    val onCancelSetup: () -> Unit = {},
)

@Composable
fun PinRule.message(): String = stringResource(
    when (this) {
        PinRule.Ok -> R.string.lock_rule_ok
        PinRule.WrongLength -> R.string.lock_rule_length
        PinRule.NotDigits -> R.string.lock_rule_digits
        PinRule.TooSimple -> R.string.lock_rule_simple
        PinRule.Mismatch -> R.string.lock_rule_mismatch
    },
)

/**
 * Full-screen lock and PIN setup, always in a secure window.
 *
 * Back is deliberately not handled while locked: it leaves the app, and nothing behind the lock is composed to fall back to.
 * During PIN setup, which the user chose to open from inside the app, back means "not now".
 */
@Composable
fun LockScreen(
    state: LockUiState,
    callbacks: LockCallbacks,
    modifier: Modifier = Modifier,
    anchors: RevealAnchors = remember { RevealAnchors() },
    interactive: Boolean = true,
) {
    SecureWindow()
    // A locked app's first full screen is its lock.
    if (state is LockUiState.Locked || state is LockUiState.Setup) ReportDrawn()
    val colors = PayFlowTheme.colors
    Box(modifier.fillMaxSize().drawBehind { drawRect(colors.bg) }, contentAlignment = Alignment.Center) {
        when (state) {
            LockUiState.Checking -> BrandLoader()
            is LockUiState.Unlocked -> Unit
            is LockUiState.Setup -> SetupContent(state, callbacks, anchors, interactive)
            is LockUiState.Locked -> LockedContent(state, callbacks, anchors)
        }
    }
}

@Composable
private fun LockedContent(state: LockUiState.Locked, callbacks: LockCallbacks, anchors: RevealAnchors) {
    val lockedOut = !LockMotionLogic.keypadEnabled(state)
    if (lockedOut) LaunchedEffect(state.lockoutRemainingMillis) { delay(TICK_MS); callbacks.onTick() }
    val message = when {
        lockedOut -> stringResource(R.string.lock_locked_out, LockMotionLogic.lockoutSeconds(state.lockoutRemainingMillis))
        state.error == LockError.WRONG_PIN -> stringResource(R.string.lock_wrong_pin, state.remainingAttempts)
        state.biometricMessage != null -> state.biometricMessage
        else -> ""
    }
    PinFrame(
        title = stringResource(R.string.lock_title),
        subtitle = stringResource(R.string.lock_enter_pin),
        dots = state.enteredDigits,
        rejectionSerial = state.rejections,
        message = message,
        keypadEnabled = !lockedOut,
        anchors = anchors,
        onDigit = callbacks.onDigit,
        onBackspace = callbacks.onBackspace,
        leftKey = {
            if (state.biometricAvailable) {
                IconKey(
                    icon = Icons.Outlined.Fingerprint,
                    description = stringResource(R.string.lock_use_biometric),
                    enabled = true,
                    tint = PayFlowTheme.colors.accent,
                    onClick = callbacks.onBiometric,
                    modifier = Modifier
                        .testTag(PayFlowTestTags.LOCK_BIOMETRIC)
                        .onGloballyPositioned { anchors.biometric = it.boundsInWindow().center },
                )
            } else {
                Spacer(Modifier.size(Sizes.pinKey))
            }
        },
    )
}

@Composable
private fun SetupContent(state: LockUiState.Setup, callbacks: LockCallbacks, anchors: RevealAnchors, interactive: Boolean) {
    BackHandler(enabled = interactive, onBack = callbacks.onCancelSetup)
    // A refused setup PIN has no serial of its own: a new error on an emptied row is the same event.
    var refusals by remember { mutableStateOf(0) }
    val error = state.error
    LaunchedEffect(error) { if (error != null) refusals++ }
    PinFrame(
        title = stringResource(if (state.step == SetupStep.ENTER) R.string.lock_setup_enter else R.string.lock_setup_confirm),
        subtitle = stringResource(if (state.step == SetupStep.ENTER) R.string.lock_setup_hint else R.string.lock_setup_confirm_hint),
        dots = state.enteredDigits,
        rejectionSerial = refusals,
        message = error?.message() ?: "",
        keypadEnabled = true,
        anchors = anchors,
        onDigit = callbacks.onSetupDigit,
        onBackspace = callbacks.onSetupBackspace,
        leftKey = { Spacer(Modifier.size(Sizes.pinKey)) },
        footer = { GhostButton(stringResource(R.string.lock_setup_later), onClick = callbacks.onCancelSetup) },
        step = state.step,
    )
}

/** Shared layout: brand mark, title, dots, message, keypad. Centred on tall windows, scrolls on short ones and at large text. */
@Composable
private fun PinFrame(
    title: String,
    subtitle: String,
    dots: Int,
    rejectionSerial: Int,
    message: String,
    keypadEnabled: Boolean,
    anchors: RevealAnchors,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    leftKey: @Composable () -> Unit,
    step: SetupStep? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark()
        Spacer(Modifier.height(Spacing.lg))
        AnimatedContent(
            targetState = step to (title to subtitle),
            transitionSpec = { motion.sharedAxisX(forward = targetState.first != SetupStep.ENTER) },
            contentKey = { it.first },
            label = "lockTitle",
        ) { (_, text) ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text.first, style = type.display, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.xxs))
                Text(text.second, style = type.body, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        PinDots(dots, rejectionSerial, anchors)
        Spacer(Modifier.height(Spacing.sm))
        // Room for one line is always kept, so an error appearing doesn't move the keypad under the finger.
        Text(
            message,
            style = type.labelStrong,
            color = colors.failed.strong,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Spacing.xl)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Spacer(Modifier.height(Spacing.sm))
        Keypad(keypadEnabled, onDigit, onBackspace, leftKey)
        if (footer != null) {
            Spacer(Modifier.height(Spacing.md))
            footer()
        }
    }
}

/** The launcher icon's mark on the same graphite, so splash, lock and home icon read as one object. */
@Composable
private fun BrandMark() {
    PayFlowSurface(Modifier.size(Sizes.avatarLarge), level = SurfaceLevel.Hero, shape = Radii.mediumShape) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The row of PIN dots. Each fill is its own spring; a refusal shakes the row, flushes it red and then drains it. Everything per
 * frame is read in the draw and layer phases. Reduced motion drops the shake and keeps the colour and the message.
 */
@Composable
private fun PinDots(entered: Int, rejectionSerial: Int, anchors: RevealAnchors) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val shakePx = with(LocalDensity.current) { Spacing.xs.toPx() }
    val shake = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }
    var rejecting by remember { mutableStateOf(false) }
    val seen = remember { intArrayOf(rejectionSerial) }
    LaunchedEffect(rejectionSerial) {
        if (!LockMotionLogic.shouldPlayRejection(seen[0], rejectionSerial)) return@LaunchedEffect
        seen[0] = rejectionSerial
        rejecting = true
        pulse.snapTo(1f)
        val fade = launch { pulse.animateTo(0f, motion.spec(MotionToken.Shake)) }
        if (!motion.reduced) {
            shake.snapTo(0f)
            shake.animateTo(1f, motion.spec(MotionToken.Shake))
        }
        fade.join()
        rejecting = false
    }
    val visible = LockMotionLogic.visibleDots(entered, rejecting)
    val description = stringResource(R.string.lock_digits_entered, entered, PinManager.PIN_LENGTH)
    Row(
        Modifier
            .testTag(PayFlowTestTags.LOCK_DOTS)
            .onGloballyPositioned { anchors.pin = it.boundsInWindow().center }
            .graphicsLayer { translationX = ShakeCurve.offset(shake.value) * shakePx }
            .drawBehind {
                val p = pulse.value
                if (p > 0f) {
                    drawRoundRect(colors.failed.container, alpha = p, cornerRadius = CornerRadius(size.height / 2f))
                }
            }
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(PinManager.PIN_LENGTH) { index ->
            PinDot(filled = index < visible, pulse = { pulse.value })
        }
    }
}

@Composable
private fun PinDot(filled: Boolean, pulse: () -> Float) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val fill = remember { Animatable(if (filled) 1f else 0f) }
    LaunchedEffect(filled, motion) {
        fill.animateTo(if (filled) 1f else 0f, motion.spec(if (filled) MotionToken.Bounce else MotionToken.Snap))
    }
    Spacer(
        Modifier
            .size(Sizes.pinDot)
            .drawWithCache {
                val ring = Stroke(width = Hairline.width.toPx())
                val full = size.minDimension / 2f
                onDrawBehind {
                    val colour = lerp(colors.accent, colors.failed.strong, pulse().coerceIn(0f, 1f))
                    drawCircle(lerp(colors.outline, colour, fill.value.coerceIn(0f, 1f)), radius = full - ring.width / 2f, style = ring)
                    val radius = full * fill.value
                    if (radius > 0f) drawCircle(colour, radius = radius, center = Offset(size.width / 2f, size.height / 2f))
                }
            },
    )
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (String) -> Unit, onBackspace: () -> Unit, leftKey: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
        DIGIT_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                row.forEach { digit -> DigitKey(digit, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            leftKey()
            DigitKey("0", enabled, onDigit)
            IconKey(
                icon = Icons.AutoMirrored.Outlined.Backspace,
                description = stringResource(R.string.lock_backspace),
                enabled = enabled,
                tint = PayFlowTheme.colors.text,
                onClick = onBackspace,
                modifier = Modifier.testTag(PayFlowTestTags.LOCK_BACKSPACE),
            )
        }
    }
}

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onDigit: (String) -> Unit) {
    val colors = PayFlowTheme.colors
    val press = rememberPressFeedback()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(Sizes.pinKey)
            .testTag(PayFlowTestTags.lockKey(digit))
            .trackPress(press, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { onDigit(digit) }
            .pressScale(press, pressedScale = KEY_PRESSED_SCALE)
            .drawWithCache {
                val ring = Stroke(width = Hairline.width.toPx())
                onDrawBehind {
                    drawCircle(lerp(colors.surface, colors.accentTint, press.progress.coerceIn(0f, 1f)))
                    drawCircle(colors.hairline, radius = size.minDimension / 2f - ring.width / 2f, style = ring)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Newsreader's numerals sit above the middle of their line; this puts them in the optical centre of the circle.
        Text(
            digit,
            style = PayFlowTheme.type.display,
            color = if (enabled) colors.text else colors.textSecondary,
            modifier = Modifier.offset(y = Sizes.numeralNudge),
        )
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, enabled: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PayFlowTheme.colors
    val press = rememberPressFeedback()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Sizes.pinKey)
            .trackPress(press, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .pressScale(press, pressedScale = KEY_PRESSED_SCALE)
            .drawBehind { drawCircle(colors.accentTint, alpha = press.progress.coerceIn(0f, 1f)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else colors.textSecondary, modifier = Modifier.size(Sizes.icon))
    }
}

private val DIGIT_ROWS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
private const val TICK_MS = 1_000L // token-audit: allow (clock tick for the lockout countdown)
private const val KEY_PRESSED_SCALE = 0.94f
