package com.darshan.payflow.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.darshan.payflow.data.qr.CameraController
import com.darshan.payflow.data.qr.CameraPermission
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.pay.PayViewModel
import com.darshan.payflow.ui.scan.ScanRoute
import com.darshan.payflow.ui.history.HistoryRoute
import com.darshan.payflow.ui.pay.PayRoute
import com.darshan.payflow.ui.history.PaymentDetailRoute
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeRoute
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object HistoryDestination
@Serializable data class PaymentDetailDestination(val id: String)
/** Optional prefill (from a scanned QR). `amountPaise = 0` means "not set"; `source = "qr"` locks the payee. */
@Serializable
data class PayDestination(
    val vpa: String? = null,
    val name: String? = null,
    val amountPaise: Long = 0,
    val note: String? = null,
    val source: String? = null,
)
@Serializable object ScanDestination

fun UpiQrPayload.toPayDestination() = PayDestination(
    vpa = payeeVpa, name = payeeName, amountPaise = amountPaise ?: 0, note = note.takeIf { it.isNotBlank() }, source = PayViewModel.SOURCE_QR,
)

@Composable
fun PayFlowNavHost(
    upiAppLauncher: UpiAppLauncher,
    cameraController: CameraController,
    cameraPermission: CameraPermission,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                HomeCallbacks(
                    onPaymentClick = { navController.navigate(PaymentDetailDestination(it)) },
                    onViewAll = { navController.navigate(HistoryDestination) },
                    onPay = { navController.navigate(PayDestination()) },
                    onScan = { navController.navigate(ScanDestination) },
                ),
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
