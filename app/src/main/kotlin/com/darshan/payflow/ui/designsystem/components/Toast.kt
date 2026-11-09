package com.darshan.payflow.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.delay

@Immutable
data class ToastItem(val id: Long, val message: String, val tone: ToastTone)

/** Pure toast queue: one visible, a bounded queue behind it, repeats collapse. A flood can't grow memory or block the screen for minutes. */
class ToastQueue(private val maxPending: Int = DEFAULT_MAX_PENDING) {
    private val pending = ArrayDeque<ToastItem>()
    private var nextId = 0L

    var current: ToastItem? = null
        private set

    fun enqueue(message: String, tone: ToastTone) {
        val capped = cap(message)
        if (current?.message == capped || pending.lastOrNull()?.message == capped) return
        val item = ToastItem(nextId++, capped, tone)
        if (current == null) {
            current = item
        } else {
            pending.addLast(item)
            while (pending.size > maxPending) pending.removeFirst()
        }
    }

    fun dismissCurrent() {
        current = pending.removeFirstOrNull()
    }

    private fun cap(message: String): String {
        if (message.length <= MAX_MESSAGE_LENGTH) return message
        val end = if (message[MAX_MESSAGE_LENGTH - 1].isHighSurrogate()) MAX_MESSAGE_LENGTH - 1 else MAX_MESSAGE_LENGTH
        return message.substring(0, end)
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 200
        private const val DEFAULT_MAX_PENDING = 3
    }
}

/** Keep one per screen or app root. Call [show] from anywhere on the main thread, usually from `CollectUiEffects`. */
@Stable
class ToastHostState {
    private val queue = ToastQueue()
    var current: ToastItem? by mutableStateOf(null)
        private set

    fun show(message: String, tone: ToastTone = ToastTone.Info) {
        queue.enqueue(message, tone)
        current = queue.current
    }

    fun dismiss() {
        queue.dismissCurrent()
        current = queue.current
    }
}

@Composable
fun rememberToastHostState(): ToastHostState = remember { ToastHostState() }

private const val TOAST_MS = 3500L
private const val TOAST_FAILED_MS = 5000L

/**
 * Shows the current toast above whatever sits below it. Place it last in a full-screen `Box`, aligned to the bottom.
 * Announced politely by TalkBack; tap to dismiss. Failures stay a little longer so they can be read.
 */
@Composable
fun PayFlowToastHost(state: ToastHostState, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val item = state.current
    val last = remember { arrayOfNulls<ToastItem>(1) }
    if (item != null) last[0] = item
    LaunchedEffect(item?.id) {
        val active = item ?: return@LaunchedEffect
        delay(if (active.tone == ToastTone.Failed) TOAST_FAILED_MS else TOAST_MS)
        state.dismiss()
    }
    AnimatedVisibility(
        visible = item != null,
        modifier = modifier.fillMaxWidth(),
        enter = motion.enter(),
        exit = motion.exit(),
    ) {
        last[0]?.let { PayFlowToast(it.message, it.tone, onDismiss = state::dismiss) }
    }
}

/** A single toast. Use [PayFlowToastHost] on screens; this is public for the catalog. */
@Composable
fun PayFlowToast(message: String, tone: ToastTone, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val colors = PayFlowTheme.colors
    Row(
        modifier
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .clip(Radii.mediumShape)
            .drawBehind { drawRect(colors.toast) }
            .border(Hairline.width, colors.onToast.copy(alpha = 0.12f), Radii.mediumShape)
            .then(
                if (onDismiss != null) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ToastGlyph(tone)
        Text(message, style = PayFlowTheme.type.bodyStrong, color = colors.onToast, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ToastGlyph(tone: ToastTone) {
    val color = PayFlowTheme.colors.onToast
    val size = Modifier.size(Sizes.iconSmall + Spacing.xxs)
    when (tone) {
        ToastTone.Success -> DrawnCheckmark({ 1f }, color, size)
        ToastTone.Failed -> DrawnCross({ 1f }, color, size)
        ToastTone.Pending, ToastTone.Info -> Box(size, contentAlignment = Alignment.Center) {
            Spacer(
                Modifier.size(Spacing.xs).drawBehind {
                    drawCircle(color, alpha = if (tone == ToastTone.Pending) 0.7f else 1f)
                },
            )
        }
    }
}
