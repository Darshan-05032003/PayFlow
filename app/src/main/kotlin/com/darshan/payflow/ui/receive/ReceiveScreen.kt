package com.darshan.payflow.ui.receive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.QrCodeGenerator
import com.darshan.payflow.data.upi.QrMatrix
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.components.SectionCard
import com.darshan.payflow.ui.theme.ScreenPadding
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
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.receive_copied)
    val shareFailed = stringResource(R.string.receive_share_failed)
    val shareTitle = stringResource(R.string.receive_share)
    fun shareText() {
        val profile = state.profile ?: return
        val content = state.qrContent ?: return
        val text = resources.getString(R.string.receive_share_text, profile.name, profile.vpa, content)
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), shareTitle)) }
    }
    fun shareImage() {
        val content = state.qrContent ?: return
        scope.launch {
            val uri = runCatching { withContext(Dispatchers.IO) { writeQrPng(context, content) } }.getOrNull()
            if (uri == null) { snackbar.showSnackbar(shareFailed); return@launch }
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }.onFailure { snackbar.showSnackbar(shareFailed) }
        }
    }
    ReceiveScreen(
        state,
        ReceiveCallbacks(
            onBack = onBack, onNameChange = viewModel::onNameChange, onVpaChange = viewModel::onVpaChange, onAmountChange = viewModel::onAmountChange,
            onSave = viewModel::save, onEdit = viewModel::edit, onCancelEdit = viewModel::cancelEdit,
            onCopy = {
                state.profile?.let { clipboard.setText(AnnotatedString(it.vpa)); scope.launch { snackbar.showSnackbar(copied) } }
            },
            onShareText = ::shareText,
            onShareImage = ::shareImage,
        ),
        snackbar,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(state: ReceiveUiState, callbacks: ReceiveCallbacks, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    if (!state.editing && state.profile != null) {
                        IconButton(onClick = callbacks.onEdit, modifier = Modifier.testTag(PayFlowTestTags.RECEIVE_EDIT)) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.receive_edit))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            LoadingView(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.editing || state.profile == null) ProfileForm(state, callbacks) else QrContent(state, callbacks)
            InfoBanner(stringResource(R.string.receive_note), icon = Icons.Outlined.Info, tone = BannerTone.Info)
        }
    }
}

@Composable
private fun ProfileForm(state: ReceiveUiState, callbacks: ReceiveCallbacks) {
    SectionCard(stringResource(R.string.receive_setup_title), icon = Icons.Outlined.QrCode2) {
        Text(stringResource(R.string.receive_setup_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.nameInput, onValueChange = callbacks.onNameChange, singleLine = true, enabled = !state.saving,
            label = { Text(stringResource(R.string.receive_name)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            isError = state.nameError != null,
            supportingText = state.nameError?.let { { Text(it.message()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_NAME),
        )
        OutlinedTextField(
            value = state.vpaInput, onValueChange = callbacks.onVpaChange, singleLine = true, enabled = !state.saving,
            label = { Text(stringResource(R.string.receive_vpa)) },
            placeholder = { Text(stringResource(R.string.pay_vpa_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
            isError = state.vpaError != null,
            supportingText = state.vpaError?.let { { Text(it.message()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_VPA),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = callbacks.onSave, enabled = !state.saving, modifier = Modifier.testTag(PayFlowTestTags.RECEIVE_SAVE)) { Text(stringResource(R.string.receive_save)) }
            if (state.profile != null) TextButton(onClick = callbacks.onCancelEdit) { Text(stringResource(R.string.receive_cancel)) }
        }
    }
}

@Composable
private fun QrContent(state: ReceiveUiState, callbacks: ReceiveCallbacks) {
    val profile = state.profile ?: return
    val content = state.qrContent ?: return
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InitialAvatar(profile.name, size = 56.dp)
            Text(profile.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.vpa, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = callbacks.onCopy, modifier = Modifier.size(36.dp).testTag(PayFlowTestTags.RECEIVE_COPY)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.receive_copy), modifier = Modifier.size(18.dp))
                }
            }
            QrCode(content, description = stringResource(R.string.receive_qr_description, profile.vpa), modifier = Modifier.padding(vertical = 8.dp))
            state.amountPaise?.let { Text(Money.formatInr(it), style = MaterialTheme.typography.headlineMedium) }
            Text(stringResource(R.string.receive_scan_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    OutlinedTextField(
        value = state.amountText, onValueChange = callbacks.onAmountChange, singleLine = true,
        label = { Text(stringResource(R.string.receive_amount)) },
        prefix = { Text("₹") },
        supportingText = { Text(stringResource(R.string.receive_amount_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag(PayFlowTestTags.RECEIVE_AMOUNT),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = callbacks.onShareText, modifier = Modifier.weight(1f).testTag(PayFlowTestTags.RECEIVE_SHARE)) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.receive_share), modifier = Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = callbacks.onShareImage, modifier = Modifier.weight(1f).testTag(PayFlowTestTags.RECEIVE_SHARE_IMAGE)) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.receive_share_image), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Draws the QR on a white card with a quiet zone, always black-on-white so any scanner reads it in dark mode too. */
@Composable
fun QrCode(content: String, description: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 220.dp) {
    val matrix: QrMatrix? = remember(content) { runCatching { QrCodeGenerator.encode(content) }.getOrNull() }
    Canvas(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .padding(12.dp)
            .testTag(PayFlowTestTags.RECEIVE_QR)
            .semantics { contentDescription = description },
    ) {
        if (matrix == null) return@Canvas
        val cell = this.size.minDimension / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
