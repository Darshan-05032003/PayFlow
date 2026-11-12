package com.darshan.payflow.ui.checkout

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.CoffeeMaker
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.rememberCheckoutSheet
import com.darshan.payflow.BuildConfig
import com.darshan.payflow.R
import com.darshan.payflow.domain.model.PaymentStatus
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.label
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.StatusKind
import com.darshan.payflow.ui.designsystem.components.StatusPill
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.PayFlowColors
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme

data class CheckoutCallbacks(val onBack: () -> Unit = {}, val onBuy: (Product) -> Unit = {}, val onOpenPayment: (String) -> Unit = {})

/** The merchant demo screen: the CheckoutKit sheet is the only payment UI here. */
@Composable
fun CheckoutRoute(checkoutKit: CheckoutKit, onBack: () -> Unit, onOpenPayment: (String) -> Unit, viewModel: CheckoutViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The sheet secures its own window; debug screenshot mode (see SecureWindow) turns that off too.
    val options = remember { CheckoutSheetOptions(merchantName = CheckoutViewModel.MERCHANT_NAME, secureWindow = !BuildConfig.ALLOW_SCREENSHOTS) }
    val sheet = rememberCheckoutSheet(checkoutKit, options, onResult = viewModel::onResult)
    CheckoutScreen(
        state,
        CheckoutCallbacks(
            onBack = onBack,
            onBuy = { product ->
                val order = viewModel.startOrder(product)
                sheet.launch(CheckoutRequest(order.orderId, order.amountPaise, description = order.description))
            },
            onOpenPayment = onOpenPayment,
        ),
    )
    CheckoutKitTheme { CheckoutSheet(sheet) }
}

/**
 * CheckoutKit 1.0.0 takes no colours or shapes (its options are methods, merchant name and the secure flag), but its sheet
 * reads the ambient Material theme. This scopes one built from PayFlow's tokens around it: surface, text, accent, error, the
 * scrim and the top corner radius, so the sheet sits in Aurum Noir rather than stock Material. A theming API in CheckoutKit
 * itself is logged in docs/DECISIONS.md.
 */
@Composable
fun CheckoutKitTheme(content: @Composable () -> Unit) {
    val colors = PayFlowTheme.colors
    val base = MaterialTheme.colorScheme
    val scheme = remember(colors, base) { base.forCheckoutKit(colors) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes.copy(extraLarge = Radii.topLargeShape), content = content)
}

internal fun androidx.compose.material3.ColorScheme.forCheckoutKit(colors: PayFlowColors) = copy(
    surfaceContainerLow = colors.surface,
    surface = colors.surface,
    onSurface = colors.text,
    onSurfaceVariant = colors.textSecondary,
    primary = colors.primary,
    onPrimary = colors.onPrimary,
    secondaryContainer = colors.accentTint,
    onSecondaryContainer = colors.onAccentTint,
    outline = colors.outline,
    error = colors.failed.strong,
    scrim = colors.scrim,
)

/**
 * The store. While CheckoutKit's sheet is up the store recedes on PayFlow's Settle spring (a slight scale and a rounded edge),
 * and comes forward when the sheet closes; under reduced motion it stays still. Each product has a quiet Buy; the outcome of
 * the last checkout is a card with its status pill and a way to the payment.
 */
@Composable
fun CheckoutScreen(state: CheckoutUiState, callbacks: CheckoutCallbacks) {
    val colors = PayFlowTheme.colors
    val motion = LocalMotion.current
    val recede = remember { Animatable(0f) }
    LaunchedEffect(state.sheetOpen, motion.reduced) {
        val target = if (state.sheetOpen && !motion.reduced) 1f else 0f
        if (motion.reduced) recede.snapTo(target) else recede.animateTo(target, motion.spec(MotionToken.Settle))
    }
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = recede.value
                    val scale = 1f - RECEDE_SCALE * p
                    scaleX = scale
                    scaleY = scale
                    shape = Radii.largeShape
                    clip = p > 0f
                }
                // Inside the layer, so its bounds follow the recede.
                .testTag(PayFlowTestTags.CHECKOUT_STORE)
                .background(colors.bg),
        ) {
            PayFlowTopBar(title = stringResource(R.string.checkout_title), onBack = callbacks.onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StoreHero()
                SectionHeader(stringResource(R.string.checkout_section_products))
                val busy = state.activeOrder != null || state.recording
                PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Card) {
                    Column {
                        state.products.forEachIndexed { index, product ->
                            if (index > 0) HairlineDivider(Modifier.padding(start = Spacing.md + Sizes.avatarMedium + Spacing.sm))
                            ProductRow(product, enabled = !busy, onBuy = { callbacks.onBuy(product) })
                        }
                    }
                }
                if (state.recording) Recording()
                Outcome(state.lastOutcome, callbacks)
                Text(stringResource(R.string.checkout_test_hint), style = PayFlowTheme.type.label, color = colors.textSecondary)
                Text(stringResource(R.string.checkout_powered_by), style = PayFlowTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = Spacing.xl))
            }
        }
    }
}

private const val RECEDE_SCALE = 0.06f
private const val HERO_SECONDARY_ALPHA = 0.72f

@Composable
private fun StoreHero() {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Hero, shape = Radii.largeShape) {
        Column(Modifier.padding(Spacing.lg).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.checkout_overline), style = type.label, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA))
            Text(CheckoutViewModel.MERCHANT_NAME, style = type.display, color = colors.onHero)
            Text(stringResource(R.string.checkout_intro), style = type.body, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA))
        }
    }
}

@Composable
private fun ProductRow(product: Product, enabled: Boolean, onBuy: () -> Unit) {
    val colors = PayFlowTheme.colors
    val icon = when (product.id) {
        "beans" -> Icons.Outlined.Coffee
        "mug" -> Icons.Outlined.LocalCafe
        "grinder" -> Icons.Outlined.CoffeeMaker
        else -> Icons.Outlined.Inventory2
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(Sizes.avatarMedium).clip(Radii.mediumShape).background(colors.accentTint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onAccentTint, modifier = Modifier.size(Sizes.icon))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(product.name, style = PayFlowTheme.type.bodyStrong, color = colors.text)
            AmountText(product.pricePaise, color = colors.text)
        }
        GhostButton(stringResource(R.string.checkout_buy), onBuy, Modifier.testTag(PayFlowTestTags.checkoutBuy(product.id)), enabled = enabled)
    }
}

@Composable
private fun Recording() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BrandLoader(size = Sizes.loaderSmall)
        Text(stringResource(R.string.checkout_recording), style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary)
    }
}

@Composable
private fun Outcome(outcome: CheckoutOutcome?, callbacks: CheckoutCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    if (outcome == null) return
    SectionHeader(stringResource(R.string.checkout_section_outcome))
    PayFlowSurface(Modifier.fillMaxWidth().testTag(PayFlowTestTags.CHECKOUT_OUTCOME), level = SurfaceLevel.Card) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (outcome) {
                CheckoutOutcome.Cancelled -> {
                    StatusPill(StatusKind.Neutral, stringResource(R.string.checkout_cancelled_pill))
                    Text(stringResource(R.string.checkout_cancelled), style = type.body, color = colors.text)
                }
                is CheckoutOutcome.Recorded -> {
                    val payment = outcome.payment
                    StatusPill(payment.status.kind(), payment.status.label())
                    Text(
                        stringResource(
                            when (payment.status) {
                                PaymentStatus.SUCCESS -> R.string.checkout_outcome_success
                                PaymentStatus.FAILED -> R.string.checkout_outcome_failed
                                PaymentStatus.REFUNDED -> R.string.checkout_outcome_refunded
                                else -> R.string.checkout_outcome_pending
                            },
                        ),
                        style = type.title,
                        color = colors.text,
                    )
                    AmountText(payment.amountPaise, color = colors.text)
                    payment.failureReason?.let { Text(it, style = type.label, color = colors.failed.strong) }
                    GhostButton(
                        stringResource(R.string.pay_view_payment),
                        { callbacks.onOpenPayment(payment.id) },
                        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.CHECKOUT_VIEW_PAYMENT),
                        tone = GhostTone.Accent,
                    )
                }
            }
        }
    }
}

private fun PaymentStatus.kind(): StatusKind = when (this) {
    PaymentStatus.SUCCESS -> StatusKind.Success
    PaymentStatus.FAILED -> StatusKind.Failed
    PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED -> StatusKind.Refund
    PaymentStatus.PENDING -> StatusKind.Pending
    PaymentStatus.CREATED -> StatusKind.Neutral
}
