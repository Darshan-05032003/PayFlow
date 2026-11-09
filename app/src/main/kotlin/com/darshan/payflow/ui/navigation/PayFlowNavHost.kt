package com.darshan.payflow.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import com.darshan.payflow.ui.components.SecureWindow
import com.darshan.payflow.ui.history.HistoryRoute
import com.darshan.payflow.ui.history.PaymentDetailRoute
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeRoute
import com.darshan.payflow.ui.designsystem.components.BottomNavChange
import com.darshan.payflow.ui.designsystem.components.rememberBottomNavState
import com.darshan.payflow.ui.designsystem.effects.CollectUiEffects
import com.darshan.payflow.ui.designsystem.motion.LocalMotion
import com.darshan.payflow.ui.lock.AppGate
import com.darshan.payflow.ui.lock.AppLockViewModel
import com.darshan.payflow.ui.lock.LockCallbacks
import com.darshan.payflow.ui.mandates.CreateMandateRoute
import com.darshan.payflow.ui.mandates.MandateDetailRoute
import com.darshan.payflow.ui.mandates.MandatesRoute
import com.darshan.payflow.ui.pay.PayRoute
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.receive.ReceiveRoute
import com.darshan.payflow.ui.scan.ScanRoute
import com.darshan.payflow.ui.security.SecurityRoute
import com.darshan.payflow.ui.theme.PayFlowTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    // The whole app is secure, not screen by screen: Home and History show payees and amounts, and a flag that follows the
    // screen would change mid-transition.
    SecureWindow()
    CollectUiEffects(lockViewModel.uiEffects)
    val lockCallbacks = remember(lockViewModel) {
        LockCallbacks(
            onDigit = lockViewModel::onPinDigit, onBackspace = lockViewModel::onPinBackspace, onBiometric = lockViewModel::onBiometricRequested,
            onTick = lockViewModel::onTick, onSetupDigit = lockViewModel::onSetupDigit, onSetupBackspace = lockViewModel::onSetupBackspace,
            onCancelSetup = lockViewModel::cancelSetup,
        )
    }
    // The app leaves the composition while locked. Its saved state stays here, so unlocking returns to the same screen, as a
    // rotation would, with the ViewModels of that back stack still alive.
    val appState = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalPaymentMode provides mode) {
        AppGate(lockState, lockViewModel.lastUnlockMethod, lockCallbacks) { pinConfigured ->
            appState.SaveableStateProvider(APP_STATE_KEY) {
                PayFlowNavHost(
                    upiAppLauncher, cameraController, cameraPermission, checkoutKit,
                    pinConfigured = pinConfigured,
                    onSetupPin = lockViewModel::startSetup,
                    incomingLinks = incomingLinks,
                )
            }
        }
    }
}

private const val APP_STATE_KEY = "app"

/** The destination as the shell's rules see it. */
fun NavDestination.shellRoute(): ShellRoute? = when {
    hasRoute<HomeDestination>() -> ShellRoute.Home
    hasRoute<HistoryDestination>() -> ShellRoute.History
    hasRoute<SecurityDestination>() -> ShellRoute.Me
    hasRoute<ScanDestination>() -> ShellRoute.Scan
    hasRoute<PayDestination>() -> ShellRoute.Pay
    hasRoute<ReceiveDestination>() -> ShellRoute.Receive
    hasRoute<CollectDestination>() -> ShellRoute.Collect
    hasRoute<CheckoutDestination>() -> ShellRoute.Checkout
    hasRoute<MandatesDestination>() -> ShellRoute.Mandates
    hasRoute<CreateMandateDestination>() -> ShellRoute.CreateMandate
    hasRoute<MandateDetailDestination>() -> ShellRoute.MandateDetail
    hasRoute<PaymentDetailDestination>() -> ShellRoute.PaymentDetail
    else -> null
}

/** Opens a screen on top of the current one. Single top, so a double tap on a tile or a row pushes it once. */
private fun NavController.push(destination: Any) = navigate(destination) { launchSingleTop = true }

/** Switches tab: one copy of each tab, each keeping its own state, and back from any tab goes to Home. */
internal fun NavController.openTab(destination: Any) = navigate(destination) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalSharedTransitionApi::class)
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
            if (incomingLinks.consume() != null) navController.push(payload.toPayDestination(PayViewModel.SOURCE_LINK))
        }
    }

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.shellRoute()
    val bar = rememberBottomNavState(ShellLogic.BAR.size)
    val reselects = remember { MutableSharedFlow<ShellRoute>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    // The back stack is the truth: leaving Scan, or any other way of arriving, puts the pill back where the user really is.
    LaunchedEffect(route) { ShellLogic.barIndex(route)?.let { if (bar.selected != it) bar.select(it) } }

    val motion = LocalMotion.current
    val transitions = remember(motion) { ShellTransitions(motion) }
    val background = PayFlowTheme.colors.bg

    CompositionLocalProvider(LocalBottomBarInset provides BottomBarInset, LocalTabReselects provides reselects) {
        // Painted behind the screens: mid-fade the window shows through, and the window follows the system theme, not the app's.
        SharedTransitionLayout(Modifier.fillMaxSize().drawBehind { drawRect(background) }) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeDestination,
                        enterTransition = { transitions.enter(initialState.destination.shellRoute(), targetState.destination.shellRoute()) },
                        exitTransition = { transitions.exit(initialState.destination.shellRoute(), targetState.destination.shellRoute()) },
                        popEnterTransition = { transitions.popEnter(initialState.destination.shellRoute(), targetState.destination.shellRoute()) },
                        popExitTransition = { transitions.popExit(initialState.destination.shellRoute(), targetState.destination.shellRoute()) },
                        sizeTransform = null,
                    ) {
                        screen<HomeDestination> {
                            HomeRoute(
                                HomeCallbacks(
                                    onPaymentClick = { navController.push(PaymentDetailDestination(it)) },
                                    onViewAll = { navController.openTab(HistoryDestination) },
                                    onPay = { navController.push(PayDestination()) },
                                    onScan = { navController.push(ScanDestination) },
                                    onCollect = { navController.push(CollectDestination) },
                                    onMandates = { navController.push(MandatesDestination) },
                                    onSecurity = { navController.openTab(SecurityDestination) },
                                    onCheckout = { navController.push(CheckoutDestination) },
                                    onReceive = { navController.push(ReceiveDestination) },
                                    onPayee = { navController.push(it.toPayDestination()) },
                                    onSetupPin = onSetupPin,
                                ),
                                pinConfigured = pinConfigured,
                            )
                        }
                        screen<HistoryDestination> {
                            HistoryRoute(
                                onBack = null,
                                onPaymentClick = { navController.push(PaymentDetailDestination(it)) },
                            )
                        }
                        screen<PayDestination> {
                            PayRoute(
                                upiAppLauncher = upiAppLauncher,
                                onBack = { navController.popBackStack() },
                                onOpenPayment = { navController.push(PaymentDetailDestination(it)) },
                            )
                        }
                        screen<ReceiveDestination> {
                            ReceiveRoute(onBack = { navController.popBackStack() })
                        }
                        screen<CheckoutDestination> {
                            CheckoutRoute(checkoutKit, onBack = { navController.popBackStack() }, onOpenPayment = { navController.push(PaymentDetailDestination(it)) })
                        }
                        screen<SecurityDestination> {
                            SecurityRoute(onBack = null, onSetupPin = onSetupPin)
                        }
                        screen<MandatesDestination> {
                            MandatesRoute(
                                onBack = { navController.popBackStack() },
                                onOpen = { navController.push(MandateDetailDestination(it)) },
                                onCreate = { navController.push(CreateMandateDestination) },
                            )
                        }
                        screen<CreateMandateDestination> {
                            CreateMandateRoute(
                                onBack = { navController.popBackStack() },
                                onCreated = { id -> navController.navigate(MandateDetailDestination(id)) { popUpTo<CreateMandateDestination> { inclusive = true } } },
                            )
                        }
                        screen<MandateDetailDestination> {
                            MandateDetailRoute(onBack = { navController.popBackStack() }, onOpenPayment = { navController.push(PaymentDetailDestination(it)) })
                        }
                        screen<CollectDestination> {
                            CollectRoute(onBack = { navController.popBackStack() }, onOpenPayment = { navController.push(PaymentDetailDestination(it)) })
                        }
                        screen<ScanDestination> {
                            ScanRoute(
                                cameraController = cameraController,
                                cameraPermission = cameraPermission,
                                onBack = { navController.popBackStack() },
                                onPay = { payload -> navController.navigate(payload.toPayDestination()) { popUpTo<ScanDestination> { inclusive = true } } },
                            )
                        }
                        screen<PaymentDetailDestination> { backStackEntry ->
                            val id = backStackEntry.toRoute<PaymentDetailDestination>().id
                            Box(Modifier.fillMaxSize().containerTransform(paymentContainerKey(id))) {
                                PaymentDetailRoute(onBack = { navController.popBackStack() })
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = ShellLogic.barVisible(route),
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = motion.enter(),
                        exit = motion.exit(),
                    ) {
                        ShellBottomBar(
                            state = bar,
                            onChange = { change ->
                                when (change) {
                                    is BottomNavChange.Changed -> when (ShellLogic.BAR[change.to]) {
                                        ShellRoute.Scan -> navController.push(ScanDestination)
                                        ShellRoute.History -> navController.openTab(HistoryDestination)
                                        ShellRoute.Me -> navController.openTab(SecurityDestination)
                                        else -> navController.openTab(HomeDestination)
                                    }
                                    is BottomNavChange.Reselected -> reselects.tryEmit(ShellLogic.BAR[change.index])
                                    BottomNavChange.Ignored -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A destination that hands its transition scope to [containerTransform]. */
private inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { backStackEntry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(backStackEntry) }
    }
}
