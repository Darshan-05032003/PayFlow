package com.darshan.payflow.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.darshan.payflow.data.upi.UpiAppLauncher
import com.darshan.payflow.ui.history.HistoryRoute
import com.darshan.payflow.ui.pay.PayRoute
import com.darshan.payflow.ui.history.PaymentDetailRoute
import com.darshan.payflow.ui.home.HomeCallbacks
import com.darshan.payflow.ui.home.HomeRoute
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object HistoryDestination
@Serializable data class PaymentDetailDestination(val id: String)
@Serializable object PayDestination

@Composable
fun PayFlowNavHost(upiAppLauncher: UpiAppLauncher, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                HomeCallbacks(
                    onPaymentClick = { navController.navigate(PaymentDetailDestination(it)) },
                    onViewAll = { navController.navigate(HistoryDestination) },
                    onPay = { navController.navigate(PayDestination) },
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
        composable<PaymentDetailDestination> {
            PaymentDetailRoute(onBack = { navController.popBackStack() })
        }
    }
}
