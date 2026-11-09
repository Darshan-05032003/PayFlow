package com.darshan.payflow.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.darshan.payflow.R
import com.darshan.payflow.data.security.GuardReason
import com.darshan.payflow.data.security.IntegrityVerdict
import com.darshan.payflow.data.security.TamperFinding
import com.darshan.payflow.data.security.TamperReport
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

/** What the payment guard refuses on, read from the same facts the guard reads. Pure, and tested against the guard itself. */
object TamperWarningLogic {
    fun blockReasons(report: TamperReport?, integrity: IntegrityStatus): List<GuardReason> {
        val reasons = ArrayList<GuardReason>()
        val findings = report?.findings.orEmpty()
        if (findings.any { it == TamperFinding.SU_BINARY || it == TamperFinding.MAGISK }) reasons += GuardReason.ROOTED
        if (findings.any { it == TamperFinding.FRIDA_PORT || it == TamperFinding.FRIDA_LIBRARY }) reasons += GuardReason.HOOKING_FRAMEWORK
        if (TamperFinding.SIGNATURE_MISMATCH in findings) reasons += GuardReason.TAMPERED_SIGNATURE
        if ((integrity as? IntegrityStatus.Result)?.verdict == IntegrityVerdict.FAILED) reasons += GuardReason.INTEGRITY_FAILED
        return reasons
    }

    /** Only the reasons that block; warnings (a debugger, an unbound phone) never reach this screen. */
    fun blocking(reasons: List<GuardReason>): List<GuardReason> = reasons.filter { it in BLOCKING }.distinct()

    private val BLOCKING = setOf(GuardReason.ROOTED, GuardReason.HOOKING_FRAMEWORK, GuardReason.TAMPERED_SIGNATURE, GuardReason.INTEGRITY_FAILED)
}

@Composable
private fun GuardReason.finding(): String = stringResource(
    when (this) {
        GuardReason.ROOTED -> R.string.tamper_reason_rooted
        GuardReason.HOOKING_FRAMEWORK -> R.string.tamper_reason_hooking
        GuardReason.TAMPERED_SIGNATURE -> R.string.tamper_reason_signature
        GuardReason.INTEGRITY_FAILED -> R.string.tamper_reason_integrity
        GuardReason.DEBUGGER, GuardReason.INTEGRITY_UNVERIFIED, GuardReason.DEVICE_NOT_BOUND -> R.string.tamper_reason_other
    },
)

/**
 * The full-screen explanation that replaces a refused payment's toast. It changes nothing about the block: the payment guard
 * runs again before every payment, so closing this only returns to where the user was. It says, in plain words, what was
 * found, why that matters for money, what still works and what would fix it. Calm rather than red: it's a fact about the
 * phone, not something the user did wrong.
 */
@Composable
fun TamperWarningOverlay(
    visible: Boolean,
    reasons: List<GuardReason>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRecheck: (() -> Unit)? = null,
    rechecking: Boolean = false,
) {
    val motion = LocalMotion.current
    // The findings stay on screen while the panel leaves.
    var last by remember { mutableStateOf(reasons) }
    if (reasons.isNotEmpty()) last = reasons
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(visible = visible, modifier = modifier, enter = motion.enter(), exit = motion.exit()) {
        TamperWarning(last, onClose, onRecheck = onRecheck, rechecking = rechecking)
    }
}

@Composable
fun TamperWarning(
    reasons: List<GuardReason>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRecheck: (() -> Unit)? = null,
    rechecking: Boolean = false,
) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val shown = TamperWarningLogic.blocking(reasons)
    Column(modifier.fillMaxSize().background(colors.bg).testTag(PayFlowTestTags.TAMPER_WARNING)) {
        PayFlowTopBar(title = stringResource(R.string.tamper_warning_bar), onBack = onClose)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PausedShield(Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.xs))
            Text(
                stringResource(R.string.tamper_warning_title),
                style = type.display,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                stringResource(R.string.tamper_warning_body),
                style = type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            WarningSection(stringResource(R.string.tamper_warning_found)) {
                shown.forEach { reason -> Bullet(reason.finding(), dot = colors.failed.strong) }
            }
            WarningSection(stringResource(R.string.tamper_warning_still_works)) {
                Bullet(stringResource(R.string.tamper_works_history), dot = colors.success.strong)
                Bullet(stringResource(R.string.tamper_works_receive), dot = colors.success.strong)
                Bullet(stringResource(R.string.tamper_works_settings), dot = colors.success.strong)
            }
            WarningSection(stringResource(R.string.tamper_warning_fix)) {
                Text(stringResource(R.string.tamper_warning_fix_body), style = type.body, color = colors.text)
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (onRecheck != null) {
                PrimaryButton(
                    stringResource(R.string.tamper_warning_recheck),
                    onRecheck,
                    loading = rechecking,
                    modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.TAMPER_RECHECK),
                )
                GhostButton(stringResource(R.string.tamper_warning_close), onClose, Modifier.fillMaxWidth(), outlined = false, tone = GhostTone.Neutral)
            } else {
                PrimaryButton(stringResource(R.string.tamper_warning_close), onClose, Modifier.fillMaxWidth().testTag(PayFlowTestTags.TAMPER_CLOSE))
            }
        }
    }
}

@Composable
private fun WarningSection(title: String, content: @Composable () -> Unit) {
    val colors = PayFlowTheme.colors
    PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Card, shape = Radii.mediumShape) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = PayFlowTheme.type.labelStrong, color = colors.textSecondary, modifier = Modifier.semantics { heading() })
            HairlineDivider()
            content()
        }
    }
}

@Composable
private fun Bullet(text: String, dot: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = Spacing.xs).size(Spacing.xs).background(dot, Radii.pillShape))
        Text(text, style = PayFlowTheme.type.body, color = PayFlowTheme.colors.text, modifier = Modifier.weight(1f))
    }
}

/** A shield holding a pause mark: payments are paused, not the app broken. Drawn in code, no animation to wait on. */
@Composable
private fun PausedShield(modifier: Modifier = Modifier) {
    val colors = PayFlowTheme.colors
    val line = colors.failed.strong
    val fill = colors.failed.container
    val shape = remember { Path() }
    Canvas(modifier.size(Sizes.illustration)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        shape.reset()
        shape.moveTo(w * 0.5f, h * 0.12f)
        shape.lineTo(w * 0.8f, h * 0.24f)
        shape.cubicTo(w * 0.8f, h * 0.56f, w * 0.68f, h * 0.76f, w * 0.5f, h * 0.88f)
        shape.cubicTo(w * 0.32f, h * 0.76f, w * 0.2f, h * 0.56f, w * 0.2f, h * 0.24f)
        shape.close()
        drawPath(shape, fill)
        drawPath(shape, line, style = stroke)
        val barTop = h * 0.4f
        val barBottom = h * 0.6f
        drawLine(line, Offset(w * 0.44f, barTop), Offset(w * 0.44f, barBottom), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(line, Offset(w * 0.56f, barTop), Offset(w * 0.56f, barBottom), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
