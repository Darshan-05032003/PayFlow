package com.darshan.payflow.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.usecase.CheckoutOrder
import com.darshan.payflow.domain.usecase.RecordCheckoutResultUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A sandbox merchant item. */
data class Product(val id: String, val name: String, val pricePaise: Long)

sealed interface CheckoutOutcome {
    data class Recorded(val payment: Payment) : CheckoutOutcome
    data object Cancelled : CheckoutOutcome
}

data class CheckoutUiState(
    val products: List<Product> = CheckoutViewModel.CATALOGUE,
    val activeOrder: CheckoutOrder? = null,
    val lastOutcome: CheckoutOutcome? = null,
    val recording: Boolean = false,
)

/** Hosts the merchant demo: creates an order, hands it to the CheckoutKit sheet and records whatever comes back. */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val recordResult: RecordCheckoutResultUseCase,
    private val dispatchers: DispatcherProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CheckoutUiState(activeOrder = restoreOrder()))
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    /** Creates a new order for [product]; the id is unique per attempt so the SDK's idempotency is per checkout. */
    fun startOrder(product: Product): CheckoutOrder {
        val order = CheckoutOrder(
            orderId = "PF-" + product.id + "-" + java.lang.Long.toString(System.currentTimeMillis(), 36).uppercase() + random.nextInt(1_000).toString().padStart(3, '0'),
            amountPaise = product.pricePaise, description = product.name, merchantVpa = MERCHANT_VPA, merchantName = MERCHANT_NAME,
        )
        saveOrder(order)
        _uiState.update { it.copy(activeOrder = order, lastOutcome = null) }
        return order
    }

    /** The sheet closed: record the result against the active order (if any). */
    fun onResult(result: CheckoutResult) {
        val order = _uiState.value.activeOrder ?: return
        _uiState.update { it.copy(recording = true) }
        viewModelScope.launch {
            val payment = withContext(dispatchers.io) { recordResult(order, result) }
            saveOrder(null)
            _uiState.update {
                it.copy(activeOrder = null, recording = false, lastOutcome = if (payment != null) CheckoutOutcome.Recorded(payment) else CheckoutOutcome.Cancelled)
            }
        }
    }

    private fun restoreOrder(): CheckoutOrder? {
        val id = savedState.get<String>(KEY_ORDER_ID) ?: return null
        return CheckoutOrder(id, savedState[KEY_AMOUNT] ?: return null, savedState[KEY_DESCRIPTION] ?: "", MERCHANT_VPA, MERCHANT_NAME)
    }

    private fun saveOrder(order: CheckoutOrder?) {
        if (order == null) { listOf(KEY_ORDER_ID, KEY_AMOUNT, KEY_DESCRIPTION).forEach { savedState.remove<Any>(it) }; return }
        savedState[KEY_ORDER_ID] = order.orderId; savedState[KEY_AMOUNT] = order.amountPaise; savedState[KEY_DESCRIPTION] = order.description
    }

    companion object {
        const val MERCHANT_VPA = "payflow.store@okbank"
        const val MERCHANT_NAME = "PayFlow Store"
        const val KEY_ORDER_ID = "orderId"
        const val KEY_AMOUNT = "orderAmount"
        const val KEY_DESCRIPTION = "orderDescription"
        private val random = SecureRandom()
        val CATALOGUE = listOf(
            Product("beans", "Coffee beans 1 kg", 49_900),
            Product("mug", "Ceramic mug", 29_900),
            Product("grinder", "Hand grinder", 2_49_900),
        )
    }
}
