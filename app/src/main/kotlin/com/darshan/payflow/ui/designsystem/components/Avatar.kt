package com.darshan.payflow.ui.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.designsystem.tokens.StatusTone
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlin.math.abs

/** Pure avatar rules. */
object AvatarLogic {
    private const val PLACEHOLDER = "?"
    private const val MAX_BADGE = 99

    /** First letter or digit of a name. Works on code points, so a character beyond the BMP stays whole; emoji-only names get a placeholder. */
    fun initial(name: String): String {
        var index = 0
        while (index < name.length) {
            val code = name.codePointAt(index)
            if (Character.isLetterOrDigit(code)) return String(Character.toChars(Character.toUpperCase(code)))
            index += Character.charCount(code)
        }
        return PLACEHOLDER
    }

    /** Stable tone for a name, ignoring case and padding. Safe for every hash, including Int.MIN_VALUE. */
    fun toneIndex(name: String, toneCount: Int): Int = abs(name.trim().lowercase().hashCode() % toneCount)

    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > MAX_BADGE -> "$MAX_BADGE+"
        else -> count.toString()
    }
}

enum class AvatarRing { None, Accent, Success, Pending }

@Immutable
sealed interface AvatarBadge {
    data object Verified : AvatarBadge
    data class Count(val count: Int) : AvatarBadge
    data class Dot(val tone: StatusTone) : AvatarBadge
}

private val RingWidth = 2.dp
private val RingGap = 2.dp

/**
 * An initial on a muted tone picked from the name, with an optional [ring] and [badge]. Decorative by default because rows
 * already show the name; pass [contentDescription] when the avatar stands alone.
 */
@Composable
fun PayFlowAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    ring: AvatarRing = AvatarRing.None,
    badge: AvatarBadge? = null,
    contentDescription: String? = null,
) {
    val colors = PayFlowTheme.colors
    val tone = remember(name, colors) { colors.avatarTones[AvatarLogic.toneIndex(name, colors.avatarTones.size)] }
    val initial = remember(name) { AvatarLogic.initial(name) }
    val ringColor: Color? = when (ring) {
        AvatarRing.None -> null
        AvatarRing.Accent -> colors.accent
        AvatarRing.Success -> colors.success.strong
        AvatarRing.Pending -> colors.pending.strong
    }
    val type = PayFlowTheme.type
    val textStyle = when {
        size >= Sizes.avatarLarge -> type.display
        size >= Sizes.avatar -> type.bodyStrong
        else -> type.labelStrong
    }
    val inset = if (ringColor != null) RingWidth + RingGap else 0.dp
    Box(
        modifier
            .size(size + inset * 2)
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val r = this.size.minDimension / 2
                    if (ringColor != null) {
                        val stroke = RingWidth.toPx()
                        drawCircle(ringColor, radius = r - stroke / 2, style = Stroke(width = stroke))
                    }
                    drawCircle(tone.container, radius = r - inset.toPx())
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, style = textStyle, color = tone.content, maxLines = 1)
        }
        if (badge != null) AvatarBadgeView(badge, size)
    }
}

@Composable
private fun BoxScope.AvatarBadgeView(badge: AvatarBadge, avatarSize: Dp) {
    val colors = PayFlowTheme.colors
    val diameter = (avatarSize * 0.36f).coerceAtLeast(14.dp)
    val align = Modifier.align(Alignment.BottomEnd)
    when (badge) {
        AvatarBadge.Verified -> Box(
            align.size(diameter).drawBehind {
                val r = size.minDimension / 2
                drawCircle(colors.surface, r)
                drawCircle(colors.success.strong, r - 1.5.dp.toPx())
            },
            contentAlignment = Alignment.Center,
        ) {
            DrawnCheckmark(progress = { 1f }, color = colors.surface, modifier = Modifier.size(diameter * 0.6f))
        }
        is AvatarBadge.Count -> {
            val text = AvatarLogic.badgeText(badge.count) ?: return
            Box(
                align.offset(x = Spacing.xxs, y = Spacing.xxs).drawBehind {
                    drawRoundRect(colors.accent, cornerRadius = CornerRadius(size.height / 2))
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = PayFlowTheme.type.labelStrong, color = colors.surface, maxLines = 1, modifier = Modifier.padding(horizontal = Spacing.xxs))
            }
        }
        is AvatarBadge.Dot -> Box(
            align.size(diameter * 0.8f).drawBehind {
                val r = size.minDimension / 2
                drawCircle(colors.surface, r)
                drawCircle(badge.tone.strong, r - 1.5.dp.toPx())
            },
        )
    }
}
