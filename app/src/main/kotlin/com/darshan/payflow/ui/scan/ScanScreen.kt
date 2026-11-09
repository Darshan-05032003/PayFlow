package com.darshan.payflow.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.designsystem.components.AmountDecimals
import com.darshan.payflow.ui.designsystem.components.AmountText
import com.darshan.payflow.ui.designsystem.components.BrandLoader
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.ErrorState
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.Illustration
import com.darshan.payflow.ui.designsystem.components.IllustrationKind
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowIconButton
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.effects.LocalHaptics
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.designsystem.motion.MotionDurations
import com.darshan.payflow.ui.designsystem.motion.MotionEasing
import com.darshan.payflow.ui.designsystem.motion.MotionToken
import com.darshan.payflow.ui.designsystem.tokens.AurumDark
import com.darshan.payflow.ui.designsystem.tokens.Hairline
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.LocalNavAnimatedScope
import com.darshan.payflow.ui.pay.PAYEE_CONTAINER_KEY
import com.darshan.payflow.ui.navigation.containerTransform
import com.darshan.payflow.ui.theme.LocalPayFlowColors
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.delay

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
    val haptics = LocalHaptics.current
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
    // A code was read: one confirm haptic, a beat for the frame to lock on, then Pay. Only ever once per screen.
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ScanEvent.PayloadReady && !leaving) {
                leaving = true
                haptics.confirm()
                delay(MotionDurations.Small.toLong())
                onPay(event.payload)
            }
        }
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
            onPay = {
                val found = state as? ScanUiState.Found
                if (found != null && !leaving) {
                    leaving = true
                    onPay(found.payload)
                }
            },
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

/** The main views; a state update inside one doesn't cross-fade. */
private enum class ScanView { Checking, Permission, Camera, Found, CameraError, Decoding, ImageFailed }

private fun ScanUiState.view(): ScanView = when (this) {
    ScanUiState.CheckingPermission -> ScanView.Checking
    is ScanUiState.PermissionRequired, ScanUiState.PermissionDenied -> ScanView.Permission
    is ScanUiState.Scanning -> ScanView.Camera
    is ScanUiState.Found -> ScanView.Found
    is ScanUiState.Error -> ScanView.CameraError
    ScanUiState.DecodingImage -> ScanView.Decoding
    is ScanUiState.ImageFailed -> ScanView.ImageFailed
}

/**
 * The camera is graphite in both themes, like the splash: a viewfinder reads best on near-black, and a light top bar over a
 * live camera would flash. Everything that isn't the camera follows the user's theme.
 */
@Composable
fun ScanScreen(state: ScanUiState, callbacks: ScanCallbacks, cameraController: CameraController) {
    SecureWindow()
    val motion = LocalMotion.current
    val camera = state is ScanUiState.Scanning
    val colors = if (camera) AurumDark else PayFlowTheme.colors
    CompositionLocalProvider(LocalPayFlowColors provides colors) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(colors.bg) }) {
            AnimatedContent(state.view(), transitionSpec = { motion.fadeThrough() }, label = "scanView", modifier = Modifier.fillMaxSize()) { view ->
                when (view) {
                    ScanView.Camera -> (state as? ScanUiState.Scanning)?.let { CameraView(it, callbacks, cameraController) }
                    else -> Column(Modifier.fillMaxSize()) {
                        PayFlowTopBar(stringResource(R.string.scan_title), onBack = callbacks.onBack)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (view) {
                                ScanView.Checking -> BrandLoader(Modifier.testTag(PayFlowTestTags.LOADING))
                                ScanView.Permission -> PermissionView(state, callbacks)
                                ScanView.Found -> (state as? ScanUiState.Found)?.let { FoundView(it, callbacks) }
                                ScanView.CameraError -> ErrorState(
                                    title = stringResource(R.string.scan_camera_error),
                                    message = stringResource(R.string.scan_camera_error_hint),
                                    retryLabel = stringResource(R.string.retry),
                                    onRetry = callbacks.onRescan,
                                    illustration = IllustrationKind.CameraOff,
                                    modifier = Modifier.testTag(PayFlowTestTags.ERROR),
                                )
                                ScanView.Decoding -> DecodingView()
                                ScanView.ImageFailed -> (state as? ScanUiState.ImageFailed)?.let { ImageFailedView(it, callbacks) }
                                ScanView.Camera -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}

/** Asked or refused, the way forward is plain, and a screenshot of a QR is always an alternative. */
@Composable
private fun PermissionView(state: ScanUiState, callbacks: ScanCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val denied = state is ScanUiState.PermissionDenied
    CenteredColumn {
        Illustration(IllustrationKind.CameraOff)
        Text(
            stringResource(if (denied) R.string.scan_permission_denied else R.string.scan_permission_rationale),
            style = type.title, color = colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(if (denied) R.string.scan_permission_denied_hint else R.string.scan_permission_hint),
            style = type.body, color = colors.textSecondary, textAlign = TextAlign.Center,
        )
        PrimaryButton(
            stringResource(if (denied) R.string.scan_open_settings else R.string.scan_allow_camera),
            if (denied) callbacks.onOpenSettings else callbacks.onRequestPermission,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        )
        Text(stringResource(R.string.scan_gallery_or), style = type.label, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.sm))
        GalleryButton(callbacks.onPickImage, Modifier.fillMaxWidth())
    }
}

/** "Upload from gallery": icon + text, with a fuller content description for screen readers. */
@Composable
private fun GalleryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.scan_gallery_description)
    GhostButton(
        stringResource(R.string.scan_gallery),
        onClick,
        leadingIcon = Icons.Outlined.PhotoLibrary,
        modifier = modifier.testTag(PayFlowTestTags.SCAN_GALLERY).semantics { contentDescription = description },
    )
}

@Composable
private fun DecodingView() {
    CenteredColumn {
        BrandLoader(Modifier.testTag(PayFlowTestTags.LOADING))
        Text(stringResource(R.string.scan_decoding), style = PayFlowTheme.type.title, color = PayFlowTheme.colors.text, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ImageFailedView(state: ScanUiState.ImageFailed, callbacks: ScanCallbacks) {
    val colors = PayFlowTheme.colors
    val message = stringResource(
        when (state.error) {
            ImageError.NO_QR -> R.string.scan_image_no_qr
            ImageError.NOT_UPI -> R.string.scan_image_not_upi
            ImageError.UNREADABLE -> R.string.scan_image_unreadable
        },
    )
    CenteredColumn {
        Illustration(if (state.error == ImageError.NO_QR) IllustrationKind.NoResults else IllustrationKind.ErrorReceipt)
        Text(message, style = PayFlowTheme.type.title, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        PrimaryButton(stringResource(R.string.scan_try_another), callbacks.onPickImage, leadingIcon = Icons.Outlined.PhotoLibrary, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag(PayFlowTestTags.SCAN_TRY_ANOTHER))
        GhostButton(stringResource(if (state.cameraAvailable) R.string.scan_use_camera else R.string.back), callbacks.onDismissImageError, tone = GhostTone.Neutral, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The live camera. The preview starts only once the container transform from Home has finished, and uses a TextureView, which
 * moves with Compose layers; a SurfaceView inside an animating shared bound is a separate surface that can draw in the wrong
 * place or not at all. Until then the frame is graphite, which is what the transform grows from anyway.
 */
@Composable
private fun CameraView(state: ScanUiState.Scanning, callbacks: ScanCallbacks, controller: CameraController) {
    val colors = PayFlowTheme.colors
    val transition = LocalNavAnimatedScope.current?.transition
    val settled = transition == null || !transition.isRunning
    var started by remember { mutableStateOf(false) }
    if (settled) started = true
    Box(Modifier.fillMaxSize()) {
        if (started) CameraPreview(callbacks, controller, state.torchOn)
        Viewfinder(Modifier.align(Alignment.Center))
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            PayFlowTopBar(stringResource(R.string.scan_title), onBack = callbacks.onBack) {
                val label = stringResource(if (state.torchOn) R.string.scan_torch_off else R.string.scan_torch_on)
                // A tonal button shows its circle, so the circle (not the 48dp target) sits on the screen gutter like the title.
                PayFlowIconButton(
                    if (state.torchOn) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn,
                    label,
                    callbacks.onToggleTorch,
                    Modifier.padding(end = Spacing.md),
                    tonal = true,
                    tint = colors.text,
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            HintPill(state.hint)
            GalleryButton(callbacks.onPickImage, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CameraPreview(callbacks: ScanCallbacks, controller: CameraController, torchOn: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { context -> PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } },
        update = { view -> controller.start(lifecycleOwner, view, callbacks.onBarcode, callbacks.onCameraError) },
        modifier = Modifier.fillMaxSize().testTag(PayFlowTestTags.SCAN_PREVIEW),
    )
    DisposableEffect(controller) { onDispose { controller.stop() } }
    LaunchedEffect(torchOn) { controller.setTorch(torchOn) }
}

/** Says what the camera is seeing. A code that isn't a payment is named plainly, in the failed tone, and read out. */
@Composable
private fun HintPill(hint: QrError?) {
    val colors = PayFlowTheme.colors
    val text = hint?.message() ?: stringResource(R.string.scan_hint)
    val tone = if (hint != null) colors.failed else colors.neutral
    Box(
        Modifier
            .clip(Radii.pillShape)
            .drawBehind { drawRect(tone.container) }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = PayFlowTheme.type.label, color = tone.content, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Four champagne brackets that breathe, and a beam that sweeps between them. Both are decorative: off under reduced motion, and
 * read only in the draw phase. The beam is an AGSL band on API 33+ and a gradient below.
 */
@Composable
private fun Viewfinder(modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val accent = PayFlowTheme.colors.accent
    val phase: State<Float> = if (motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "viewfinder").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(MotionDurations.Ambient, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "viewfinderPhase",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val beamPhase: State<Float> = if (motion.decorativeEnabled) {
        rememberInfiniteTransition(label = "beam").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(MotionDurations.Ambient * 2, easing = MotionEasing.Standard.curve), RepeatMode.Restart),
            label = "beamPhase",
        )
    } else {
        remember { mutableFloatStateOf(-1f) }
    }
    val useShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Spacer(
        modifier
            .size(Sizes.viewfinder)
            .drawWithCache {
                val arm = Sizes.viewfinderBracket.toPx()
                val stroke = (Hairline.width * 4).toPx()
                val beamHeight = Spacing.xxl.toPx()
                val beamBrush = if (useShader) shaderBeam(size.width, beamHeight, accent) else Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.45f), Color.Transparent))
                onDrawBehind {
                    val inset = size.minDimension * ViewfinderMotion.breath(phase.value)
                    val l = inset
                    val t = inset
                    val r = size.width - inset
                    val b = size.height - inset
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(accent, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
                        drawLine(accent, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
                    }
                    corner(l, t, 1f, 1f)
                    corner(r, t, -1f, 1f)
                    corner(l, b, 1f, -1f)
                    corner(r, b, -1f, -1f)
                    val beam = beamPhase.value
                    if (beam >= 0f) {
                        val y = t + (b - t - beamHeight) * ViewfinderMotion.beam(beam)
                        drawContext.canvas.save()
                        drawContext.canvas.translate(0f, y)
                        drawRect(beamBrush, topLeft = Offset(l + stroke, 0f), size = Size(r - l - stroke * 2, beamHeight))
                        drawContext.canvas.restore()
                    }
                }
            },
    )
}

/** A soft band brightest at its centre line, with fine grain so it reads as light rather than a stripe. */
private const val BEAM_AGSL = """
uniform float2 resolution;
layout(color) uniform half4 tint;
half4 main(float2 p) {
    float y = p.y / resolution.y;
    float d = abs(y - 0.5) * 2.0;
    float band = pow(1.0 - clamp(d, 0.0, 1.0), 2.2);
    float grain = 0.9 + 0.1 * fract(sin(dot(floor(p), float2(12.9898, 78.233))) * 43758.5453);
    float a = band * grain * tint.a;
    return half4(tint.rgb * a, a);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun shaderBeam(width: Float, height: Float, color: Color): Brush {
    val shader = RuntimeShader(BEAM_AGSL).apply {
        setFloatUniform("resolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        setColorUniform("tint", color.copy(alpha = 0.55f).toArgb())
    }
    return ShaderBrush(shader)
}

/**
 * The payee from the code. Its card is the shared element that becomes the payee card on Pay. A single code moves on by itself
 * after the lock-on beat; several codes in one image wait here for the user to confirm which payee it is.
 */
@Composable
private fun FoundView(state: ScanUiState.Found, callbacks: ScanCallbacks) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    val motion = LocalMotion.current
    val payload = state.payload
    val name = DisplayText.safe(payload.payeeName)
    val lock = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { lock.animateTo(1f, motion.spec(MotionToken.Bounce)) }
    CenteredColumn {
        Text(stringResource(R.string.scan_found_label), style = type.label, color = colors.textSecondary)
        PayFlowSurface(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Arrives the way the frame locked on: from the code's size up to the card's.
                    val scale = ViewfinderMotion.lockScale(1f - lock.value)
                    scaleX = scale
                    scaleY = scale
                    alpha = lock.value
                }
                .containerTransform(PAYEE_CONTAINER_KEY),
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PayFlowAvatar(name, size = Sizes.avatarLarge)
                Text(stringResource(R.string.scan_found_title), style = type.label, color = colors.textSecondary)
                Text(name, style = type.title, color = colors.text, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(DisplayText.safe(payload.payeeVpa), style = type.label, color = colors.textSecondary, textAlign = TextAlign.Center)
                if (payload.amountPaise != null) {
                    AmountText(payload.amountPaise, Modifier.padding(top = Spacing.xs), style = type.display, color = colors.text, decimals = AmountDecimals.Always)
                } else {
                    Text(stringResource(R.string.scan_enter_amount), style = type.body, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs))
                }
                if (payload.note.isNotBlank()) Text(DisplayText.safe(payload.note), style = type.body, color = colors.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.codesInImage > 1) {
            Text(
                stringResource(R.string.scan_multiple, state.codesInImage),
                style = type.body, color = colors.pending.strong, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (payload.amountPaise == null) {
            Text(stringResource(R.string.scan_static_qr_hint), style = type.label, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
        PrimaryButton(stringResource(R.string.scan_pay), callbacks.onPay, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag(PayFlowTestTags.SCAN_PAY))
        GhostButton(stringResource(R.string.scan_again), callbacks.onRescan, tone = GhostTone.Neutral, outlined = false, modifier = Modifier.fillMaxWidth())
    }
}
