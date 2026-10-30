package com.darshan.payflow.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.data.upi.IncomingUpiLinkStore
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.domain.model.RecentPayee
import com.darshan.payflow.ui.checkout.CheckoutRoute
import com.darshan.payflow.ui.collect.CollectRoute
import com.darshan.payflow.ui.components.LocalPaymentMode
import com.darshan.payflow.ui.history.HistoryRoute
import com.darshan.payflow.ui.history.PaymentDetailRoute
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeRoute
import com.darshan.payflow.ui.lock.AppLockViewModel
import com.darshan.payflow.ui.lock.LockCallbacks
import com.darshan.payflow.ui.lock.LockScreen
import com.darshan.payflow.ui.lock.LockUiState
import com.darshan.payflow.ui.mandates.CreateMandateRoute
import com.darshan.payflow.ui.mandates.MandateDetailRoute
import com.darshan.payflow.ui.mandates.MandatesRoute
import com.darshan.payflow.ui.pay.PayRoute
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.receive.ReceiveRoute
import com.darshan.payflow.ui.scan.ScanRoute
import com.darshan.payflow.ui.security.SecurityRoute
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object HistoryDestination
@Serializable data class PaymentDetailDestination(val id: String)
/**
 * Optional prefill. `amountPaise = 0` means "not set"; `source = "qr"` / `"link"` locks the payee (and the amount when present),
 * `source = "payee"` only prefills.
 */
@Serializable
data class PayDestination(
    val vpa: String? = null,
    val name: String? = null,
    val amountPaise: Long = 0,
    val note: String? = null,
    val source: String? = null,
)
@Serializable object ScanDestination
@Serializable object ReceiveDestination
@Serializable object CollectDestination
@Serializable object SecurityDestination
@Serializable object CheckoutDestination
@Serializable object MandatesDestination
@Serializable object CreateMandateDestination
@Serializable data class MandateDetailDestination(val id: String)

fun UpiQrPayload.toPayDestination(source: String = PayViewModel.SOURCE_QR) = PayDestination(
    vpa = payeeVpa, name = payeeName, amountPaise = amountPaise ?: 0, note = note.takeIf { it.isNotBlank() }, source = source,
)

fun RecentPayee.toPayDestination() = PayDestination(vpa = vpa, name = name, source = PayViewModel.SOURCE_PAYEE)

/** Root of the UI: the lock screen covers everything while the app is locked or a PIN is being set up. */
@Composable
fun AppRoot(
    upiAppLauncher: UpiAppLauncher,
    cameraController: CameraController,
    cameraPermission: CameraPermission,
    checkoutKit: CheckoutKit,
    incomingLinks: IncomingUpiLinkStore? = null,
    lockViewModel: AppLockViewModel = hiltViewModel(),
    modeViewModel: PaymentModeViewModel = hiltViewModel(),
) {
    val lockState by lockViewModel.uiState.collectAsStateWithLifecycle()
    val mode by modeViewModel.mode.collectAsStateWithLifecycle()
    lockViewModel.host = LocalActivity.current
    CompositionLocalProvider(LocalPaymentMode provides mode) {
        when (lockState) {
            is LockUiState.Unlocked -> PayFlowNavHost(
                upiAppLauncher, cameraController, cameraPermission, checkoutKit,
                pinConfigured = (lockState as LockUiState.Unlocked).pinConfigured,
                onSetupPin = lockViewModel::startSetup,
                incomingLinks = incomingLinks,
            )
            else -> LockScreen(
                lockState,
                LockCallbacks(
                    onDigit = lockViewModel::onPinDigit, onBackspace = lockViewModel::onPinBackspace, onBiometric = lockViewModel::onBiometricRequested,
                    onTick = lockViewModel::onTick, onSetupPin = lockViewModel::onSetupPin, onCancelSetup = lockViewModel::cancelSetup,
                ),
            )
        }
    }
}

@Composable
fun PayFlowNavHost(
    upiAppLauncher: UpiAppLauncher,
    cameraController: CameraController,
    cameraPermission: CameraPermission,
    checkoutKit: CheckoutKit,
    pinConfigured: Boolean = true,
    onSetupPin: () -> Unit = {},
    incomingLinks: IncomingUpiLinkStore? = null,
    navController: NavHostController = rememberNavController(),
) {
    // An incoming upi://pay link (from a browser, chat app or another QR reader) opens Pay prefilled and locked, once.
    // Only consumed while unlocked: a link that arrives against the lock screen waits here until the PIN is entered.
    if (incomingLinks != null) {
        val pendingLink by incomingLinks.pending.collectAsStateWithLifecycle()
        LaunchedEffect(pendingLink) {
            val payload = pendingLink ?: return@LaunchedEffect
            if (incomingLinks.consume() != null) navController.navigate(payload.toPayDestination(PayViewModel.SOURCE_LINK))
        }
    }
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                HomeCallbacks(
                    onPaymentClick = { navController.navigate(PaymentDetailDestination(it)) },
                    onViewAll = { navController.navigate(HistoryDestination) },
                    onPay = { navController.navigate(PayDestination()) },
                    onScan = { navController.navigate(ScanDestination) },
                    onCollect = { navController.navigate(CollectDestination) },
                    onMandates = { navController.navigate(MandatesDestination) },
                    onSecurity = { navController.navigate(SecurityDestination) },
                    onCheckout = { navController.navigate(CheckoutDestination) },
                    onReceive = { navController.navigate(ReceiveDestination) },
                    onPayee = { navController.navigate(it.toPayDestination()) },
                    onSetupPin = onSetupPin,
                ),
                pinConfigured = pinConfigured,
            )
        }
        composable<HistoryDestination> {
            HistoryRoute(
                onBack = { navController.popBackStack() },
                onPaymentClick = { navController.navigate(PaymentDetailDestination(it)) },
            )
        }
        composable<PayDestination> {
            PayRoute(
                upiAppLauncher = upiAppLauncher,
                onBack = { navController.popBackStack() },
                onOpenPayment = { navController.navigate(PaymentDetailDestination(it)) },
            )
        }
        composable<ReceiveDestination> {
            ReceiveRoute(onBack = { navController.popBackStack() })
        }
        composable<CheckoutDestination> {
            CheckoutRoute(checkoutKit, onBack = { navController.popBackStack() }, onOpenPayment = { navController.navigate(PaymentDetailDestination(it)) })
        }
        composable<SecurityDestination> {
            SecurityRoute(onBack = { navController.popBackStack() }, onSetupPin = onSetupPin)
        }
        composable<MandatesDestination> {
            MandatesRoute(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(MandateDetailDestination(it)) },
                onCreate = { navController.navigate(CreateMandateDestination) },
            )
        }
        composable<CreateMandateDestination> {
            CreateMandateRoute(
                onBack = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(MandateDetailDestination(id)) { popUpTo<CreateMandateDestination> { inclusive = true } } },
            )
        }
        composable<MandateDetailDestination> {
            MandateDetailRoute(onBack = { navController.popBackStack() }, onOpenPayment = { navController.navigate(PaymentDetailDestination(it)) })
        }
        composable<CollectDestination> {
            CollectRoute(onBack = { navController.popBackStack() }, onOpenPayment = { navController.navigate(PaymentDetailDestination(it)) })
        }
        composable<ScanDestination> {
            ScanRoute(
                cameraController = cameraController,
                cameraPermission = cameraPermission,
                onBack = { navController.popBackStack() },
                onPay = { payload -> navController.navigate(payload.toPayDestination()) { popUpTo<ScanDestination> { inclusive = true } } },
            )
        }
        composable<PaymentDetailDestination> {
            PaymentDetailRoute(onBack = { navController.popBackStack() })
        }
    }
}
