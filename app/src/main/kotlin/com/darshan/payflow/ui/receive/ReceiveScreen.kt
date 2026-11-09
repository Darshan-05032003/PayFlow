package com.darshan.payflow.ui.receive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.QrCodeGenerator
import com.darshan.payflow.data.upi.QrMatrix
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AvatarRing
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTextField
import com.darshan.payflow.ui.designsystem.components.PayFlowToastHost
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.components.ToastHostState
import com.darshan.payflow.ui.designsystem.effects.ToastTone
import com.darshan.payflow.ui.designsystem.components.rememberToastHostState
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.theme.PayFlowTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReceiveCallbacks(
    val onBack: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onVpaChange: (String) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onShareText: () -> Unit = {},
    val onShareImage: () -> Unit = {},
)

@Composable
fun ReceiveRoute(onBack: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toasts = rememberToastHostState()
    val copied = stringResource(R.string.receive_copied)
    val shareFailed = stringResource(R.string.receive_share_failed)
    val shareTitle = stringResource(R.string.receive_share)
    fun shareText() {
        val profile = state.profile ?: return
        val content = state.qrContent ?: return
        val text = resources.getString(R.string.receive_share_text, profile.name, profile.vpa, content)
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), shareTitle)) }
            .onFailure { toasts.show(shareFailed, ToastTone.Failed) }
    }
    fun shareImage() {
        val content = state.qrContent ?: return
        scope.launch {
            val uri = runCatching { withContext(Dispatchers.IO) { writeQrPng(context, content) } }.getOrNull()
            if (uri == null) { toasts.show(shareFailed, ToastTone.Failed); return@launch }
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }.onFailure { toasts.show(shareFailed, ToastTone.Failed) }
        }
    }
    ReceiveScreen(
        state,
        ReceiveCallbacks(
            onBack = onBack, onNameChange = viewModel::onNameChange, onVpaChange = viewModel::onVpaChange, onAmountChange = viewModel::onAmountChange,
            onSave = viewModel::save, onEdit = viewModel::edit, onCancelEdit = viewModel::cancelEdit,
            // Your own UPI ID is meant to be shared, so it's copied as plain text, unlike payment references.
            onCopy = { state.profile?.let { clipboard.setText(AnnotatedString(it.vpa)); toasts.show(copied, ToastTone.Success) } },
            onShareText = ::shareText,
            onShareImage = ::shareImage,
        ),
        toasts,
    )
}

/** Renders the QR to a PNG in `cache/qr/` and returns a content URI from the FileProvider (read-only, revoked by the OS later). */
private fun writeQrPng(context: Context, content: String): android.net.Uri {
    val matrix = QrCodeGenerator.encode(content)
    val scale = 12
    val px = matrix.size * scale
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
    val pixels = IntArray(px * px) { i -> if (matrix[(i % px) / scale, (i / px) / scale]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    bitmap.setPixels(pixels, 0, px, 0, 0, px, px)
    val dir = File(context.cacheDir, "qr").apply { mkdirs() }
    val file = File(dir, "payflow-upi-qr.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun ProfileFieldError.message(): String = stringResource(
    when (this) {
        ProfileFieldError.REQUIRED -> R.string.receive_error_required
        ProfileFieldError.TOO_LONG -> R.string.receive_error_too_long
        ProfileFieldError.INVALID_VPA -> R.string.receive_error_vpa
    },
)

/**
 * Your QR on a graphite card, the code itself always black on white paper so any scanner reads it in either theme. It rises
 * in once; the amount, when set, sits under it in the display numerals. One primary action: share the QR.
 */
@Composable
fun ReceiveScreen(state: ReceiveUiState, callbacks: ReceiveCallbacks, toasts: ToastHostState = rememberToastHostState()) {
    val colors = PayFlowTheme.colors
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            PayFlowTopBar(
                title = stringResource(R.string.receive_title),
                onBack = callbacks.onBack,
                actions = {
                    if (!state.editing && state.profile != null) {
                        PayFlowIconButton(Icons.Outlined.Edit, stringResource(R.string.receive_edit), callbacks.onEdit, Modifier.testTag(PayFlowTestTags.RECEIVE_EDIT), tint = colors.text)
                    }
                },
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize().testTag(PayFlowTestTags.LOADING), contentAlignment = Alignment.Center) { BrandLoader() }
                return@Column
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.editing || state.profile == null) ProfileForm(state, callbacks) else QrContent(state, callbacks)
                Text(stringResource(R.string.receive_note), style = PayFlowTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = Spacing.xl))
            }
        }
        PayFlowToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun ProfileForm(state: ReceiveUiState, callbacks: ReceiveCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.receive_setup_title))
        Text(stringResource(R.string.receive_setup_hint), style = PayFlowTheme.type.body, color = PayFlowTheme.colors.textSecondary)
        PayFlowTextField(
            value = state.nameInput,
            onValueChange = callbacks.onNameChange,
            label = stringResource(R.string.receive_name),
            enabled = !state.saving,
            error = state.nameError?.message(),
            leading = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(Sizes.icon)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            fieldTag = PayFlowTestTags.RECEIVE_NAME,
        )
        PayFlowTextField(
            value = state.vpaInput,
            onValueChange = callbacks.onVpaChange,
            label = stringResource(R.string.receive_vpa),
            placeholder = stringResource(R.string.pay_vpa_placeholder),
            enabled = !state.saving,
            error = state.vpaError?.message(),
            leading = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null, modifier = Modifier.size(Sizes.icon)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            fieldTag = PayFlowTestTags.RECEIVE_VPA,
        )
        PrimaryButton(stringResource(R.string.receive_save), callbacks.onSave, Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_SAVE), loading = state.saving)
        if (state.profile != null) {
            GhostButton(stringResource(R.string.receive_cancel), callbacks.onCancelEdit, Modifier.fillMaxWidth(), outlined = false, tone = GhostTone.Neutral)
        }
    }
}

@Composable
private fun QrContent(state: ReceiveUiState, callbacks: ReceiveCallbacks) {
    val profile = state.profile ?: return
    val content = state.qrContent ?: return
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    // The card rises in once per visit; reduced motion makes that a short fade.
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = appear, enter = motion.popIn()) {
        PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Hero, shape = Radii.largeShape) {
            Column(
                Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    PayFlowAvatar(profile.name, size = Sizes.avatar, ring = AvatarRing.Accent)
                    Column(Modifier.weight(1f)) {
                        Text(DisplayText.safe(profile.name), style = type.bodyStrong, color = colors.onHero, maxLines = 2)
                        Text(profile.vpa, style = type.label, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA), maxLines = 1)
                    }
                    PayFlowIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.receive_copy), callbacks.onCopy, Modifier.testTag(PayFlowTestTags.RECEIVE_COPY), tint = colors.onHero)
                }
                QrCode(content, description = stringResource(R.string.receive_qr_description, profile.vpa), modifier = Modifier.padding(vertical = Spacing.xs))
                state.amountPaise?.let { Text(Money.formatInr(it), style = type.display, color = colors.onHero) }
                Text(stringResource(R.string.receive_scan_hint), style = type.label, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA), textAlign = TextAlign.Center)
            }
        }
    }
    PayFlowTextField(
        value = state.amountText,
        onValueChange = callbacks.onAmountChange,
        label = stringResource(R.string.receive_amount),
        helper = stringResource(R.string.receive_amount_hint),
        leading = { Text("₹", style = PayFlowTheme.type.bodyStrong, color = colors.textSecondary) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
        fieldTag = PayFlowTestTags.RECEIVE_AMOUNT,
    )
    PrimaryButton(stringResource(R.string.receive_share_image), callbacks.onShareImage, Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_SHARE_IMAGE), leadingIcon = Icons.Outlined.Image)
    GhostButton(stringResource(R.string.receive_share), callbacks.onShareText, Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_SHARE), leadingIcon = Icons.Outlined.Share)
    Spacer(Modifier.height(Spacing.xxs))
}

private const val HERO_SECONDARY_ALPHA = 0.72f

/** Draws the QR on a white tile with a quiet zone, always black on white so any scanner reads it in dark mode too. */
@Composable
fun QrCode(content: String, description: String, modifier: Modifier = Modifier, size: Dp = Sizes.qrCode) {
    val matrix: QrMatrix? = remember(content) { runCatching { QrCodeGenerator.encode(content) }.getOrNull() }
    Canvas(
        modifier = modifier
            .size(size)
            .clip(Radii.mediumShape)
            .background(Color.White) // token-audit: allow (QR paper is white in both themes so any scanner reads it)
            .padding(Spacing.sm)
            .testTag(PayFlowTestTags.RECEIVE_QR)
            .semantics { contentDescription = description },
    ) {
        if (matrix == null) return@Canvas
        val cell = this.size.minDimension / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f)) // token-audit: allow (QR modules)
            }
        }
    }
}
