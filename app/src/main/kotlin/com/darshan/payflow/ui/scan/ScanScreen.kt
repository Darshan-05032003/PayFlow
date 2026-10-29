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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
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
import com.darshan.payflow.ui.components.EmptyView
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
                            Icon(Icons.Default.Star, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ScanUiState.CheckingPermission -> LoadingView()
                is ScanUiState.PermissionRequired -> PermissionBlock(
                    title = stringResource(R.string.scan_permission_rationale),
                    button = stringResource(R.string.scan_allow_camera),
                    onClick = callbacks.onRequestPermission,
                )
                ScanUiState.PermissionDenied -> PermissionBlock(
                    title = stringResource(R.string.scan_permission_denied),
                    button = stringResource(R.string.scan_open_settings),
                    onClick = callbacks.onOpenSettings,
                )
                is ScanUiState.Scanning -> CameraPreview(state, callbacks, cameraController)
                is ScanUiState.Found -> FoundCard(state.payload, callbacks)
                is ScanUiState.Error -> ErrorView(stringResource(R.string.scan_camera_error), onRetry = callbacks.onRescan)
            }
        }
    }
}

@Composable
private fun PermissionBlock(title: String, button: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onClick, modifier = Modifier.padding(top = 16.dp)) { Text(button) }
    }
}

@Composable
private fun CameraPreview(state: ScanUiState.Scanning, callbacks: ScanCallbacks, controller: CameraController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> PreviewView(context) },
            update = { view -> controller.start(lifecycleOwner, view, callbacks.onBarcode, callbacks.onCameraError) },
            modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.SCAN_PREVIEW),
        )
        DisposableEffect(controller) { onDispose { controller.stop() } }
        LaunchedEffect(state.torchOn) { controller.setTorch(state.torchOn) }
        val hint = state.hint?.message() ?: stringResource(R.string.scan_hint)
        Card(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Text(hint, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FoundCard(payload: UpiQrPayload, callbacks: ScanCallbacks) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scan_found_title), style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(payload.payeeName, style = MaterialTheme.typography.titleLarge)
                Text(payload.payeeVpa, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    payload.amountPaise?.let { Money.formatInr(it) } ?: stringResource(R.string.scan_enter_amount),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (payload.note.isNotBlank()) Text(payload.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = callbacks.onPay, modifier = Modifier.testTag(PayFlowTestTags.SCAN_PAY)) { Text(stringResource(R.string.scan_pay)) }
            OutlinedButton(onClick = callbacks.onRescan) { Text(stringResource(R.string.scan_again)) }
        }
        if (payload.amountPaise == null) {
            EmptyView(title = "", subtitle = stringResource(R.string.scan_static_qr_hint), modifier = Modifier.fillMaxWidth())
        }
    }
}
