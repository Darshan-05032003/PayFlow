package com.darshan.payflow.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.darshan.payflow.ui.components.BannerTone
import com.darshan.payflow.ui.components.InfoBanner
import com.darshan.payflow.ui.components.InitialAvatar
import com.darshan.payflow.ui.components.StateIcon
import com.darshan.payflow.ui.theme.ScreenPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.qr.QrError
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.components.ErrorView
import com.darshan.payflow.ui.components.LoadingView
import com.darshan.payflow.ui.components.SecureWindow

data class ScanCallbacks(
    val onBack: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onBarcode: (String) -> Unit = {},
    val onCameraError: (Throwable) -> Unit = {},
    val onToggleTorch: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onPay: () -> Unit = {},
    /** Opens the Photo Picker (images only, no storage permission). */
    val onPickImage: () -> Unit = {},
    val onDismissImageError: () -> Unit = {},
)

@Composable
fun ScanRoute(
    cameraController: CameraController,
    cameraPermission: CameraPermission,
    onBack: () -> Unit,
    onPay: (UpiQrPayload) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain = granted || (context as? Activity)?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: true
        viewModel.onPermissionResult(granted, canAskAgain)
    }
    LaunchedEffect(Unit) {
        if (cameraPermission.isGranted()) viewModel.onPermissionResult(granted = true, canAskAgain = true)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event -> if (event is ScanEvent.PayloadReady) onPay(event.payload) }
    }
    // Android Photo Picker: the user grants this one image to PayFlow; no READ_MEDIA_IMAGES / storage permission is involved.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.onImagePicked(uri?.toString()) }
    ScanScreen(
        state = state,
        callbacks = ScanCallbacks(
            onBack = onBack,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onBarcode = viewModel::onBarcode,
            onCameraError = viewModel::onCameraError,
            onToggleTorch = viewModel::toggleTorch,
            onRescan = viewModel::rescan,
            onPay = { (state as? ScanUiState.Found)?.let { onPay(it.payload) } },
            onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDismissImageError = viewModel::dismissImageError,
        ),
        cameraController = cameraController,
    )
}

@Composable
fun QrError.message(): String = stringResource(
    when (this) {
        QrError.NOT_UPI -> R.string.scan_error_not_upi
        QrError.UNSUPPORTED -> R.string.scan_error_unsupported
        QrError.TOO_LONG -> R.string.scan_error_too_long
        QrError.MISSING_VPA, QrError.INVALID_VPA -> R.string.scan_error_vpa
        QrError.INVALID_AMOUNT -> R.string.scan_error_amount
        QrError.INVALID_CURRENCY -> R.string.scan_error_currency
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(state: ScanUiState, callbacks: ScanCallbacks, cameraController: CameraController) {
    SecureWindow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state is ScanUiState.Scanning) {
                        val label = stringResource(if (state.torchOn) R.string.scan_torch_off else R.string.scan_torch_on)
                        IconButton(onClick = callbacks.onToggleTorch, modifier = Modifier.semantics { contentDescription = label }) {
                            Icon(if (state.torchOn) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ScanUiState.CheckingPermission -> LoadingView()
                is ScanUiState.PermissionRequired -> PermissionBlock(
                    title = stringResource(R.string.scan_permission_rationale),
                    hint = stringResource(R.string.scan_permission_hint),
                    button = stringResource(R.string.scan_allow_camera),
                    onClick = callbacks.onRequestPermission,
                    onPickImage = callbacks.onPickImage,
                )
                ScanUiState.PermissionDenied -> PermissionBlock(
                    title = stringResource(R.string.scan_permission_denied),
                    hint = stringResource(R.string.scan_permission_denied_hint),
                    button = stringResource(R.string.scan_open_settings),
                    onClick = callbacks.onOpenSettings,
                    icon = Icons.Outlined.NoPhotography,
                    onPickImage = callbacks.onPickImage,
                )
                is ScanUiState.Scanning -> CameraPreview(state, callbacks, cameraController)
                is ScanUiState.Found -> FoundCard(state.payload, callbacks)
                is ScanUiState.Error -> ErrorView(stringResource(R.string.scan_camera_error), onRetry = callbacks.onRescan)
                ScanUiState.DecodingImage -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                    Text(stringResource(R.string.scan_decoding), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                }
                is ScanUiState.ImageFailed -> ImageFailedBlock(state, callbacks)
            }
        }
    }
}

@Composable
private fun PermissionBlock(title: String, hint: String, button: String, onClick: () -> Unit, icon: ImageVector = Icons.Outlined.CameraAlt, onPickImage: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        StateIcon(icon)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = onClick, modifier = Modifier.padding(top = 20.dp)) { Text(button) }
        // Users who refuse the camera can still pay from a screenshot.
        Text(stringResource(R.string.scan_gallery_or), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
        GalleryButton(onPickImage, Modifier.padding(top = 8.dp))
    }
}

/** "Upload from gallery": icon + text, with a fuller content description for screen readers. */
@Composable
private fun GalleryButton(onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val description = stringResource(R.string.scan_gallery_description)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.scan_gallery), modifier = Modifier.padding(start = 8.dp))
    }
    val tagged = modifier.testTag(PayFlowTestTags.SCAN_GALLERY).semantics { contentDescription = description }
    if (filled) FilledTonalButton(onClick = onClick, modifier = tagged, content = content) else OutlinedButton(onClick = onClick, modifier = tagged, content = content)
}

@Composable
private fun ImageFailedBlock(state: ScanUiState.ImageFailed, callbacks: ScanCallbacks) {
    val message = stringResource(
        when (state.error) {
            ImageError.NO_QR -> R.string.scan_image_no_qr
            ImageError.NOT_UPI -> R.string.scan_image_not_upi
            ImageError.UNREADABLE -> R.string.scan_image_unreadable
        },
    )
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        StateIcon(Icons.Outlined.ImageNotSupported, container = MaterialTheme.colorScheme.errorContainer, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Button(onClick = callbacks.onPickImage, modifier = Modifier.padding(top = 20.dp).testTag(PayFlowTestTags.SCAN_TRY_ANOTHER)) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.scan_try_another), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = callbacks.onDismissImageError, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(if (state.cameraAvailable) R.string.scan_use_camera else R.string.back))
        }
    }
}

@Composable
private fun CameraPreview(state: ScanUiState.Scanning, callbacks: ScanCallbacks, controller: CameraController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> PreviewView(context) },
            update = { view -> controller.start(lifecycleOwner, view, callbacks.onBarcode, callbacks.onCameraError) },
            modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.SCAN_PREVIEW),
        )
        DisposableEffect(controller) { onDispose { controller.stop() } }
        LaunchedEffect(state.torchOn) { controller.setTorch(state.torchOn) }
        ViewfinderFrame(Modifier.align(Alignment.Center))
        val hint = state.hint?.message()
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GalleryButton(callbacks.onPickImage, Modifier.padding(bottom = 12.dp), filled = true)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (hint != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = if (hint != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (hint != null) Icons.Outlined.ErrorOutline else Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(hint ?: stringResource(R.string.scan_hint), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        }
    }
}

/** Four corner brackets over the preview so the user knows where to hold the code. */
@Composable
private fun ViewfinderFrame(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(240.dp)) {
        val len = 36.dp.toPx()
        val stroke = 4.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * len, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * len), stroke, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
    }
}

@Composable
private fun FoundCard(payload: UpiQrPayload, callbacks: ScanCallbacks) {
    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InitialAvatar(payload.payeeName, size = 64.dp)
                Text(stringResource(R.string.scan_found_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(payload.payeeName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(payload.payeeVpa, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    payload.amountPaise?.let { Money.formatInr(it) } ?: stringResource(R.string.scan_enter_amount),
                    style = if (payload.amountPaise != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (payload.note.isNotBlank()) Text(payload.note, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        if (payload.amountPaise == null) {
            InfoBanner(stringResource(R.string.scan_static_qr_hint), icon = Icons.Outlined.Info, tone = BannerTone.Info)
        }
        Button(onClick = callbacks.onPay, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PayFlowTestTags.SCAN_PAY)) {
            Text(stringResource(R.string.scan_pay), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = callbacks.onRescan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.scan_again), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
