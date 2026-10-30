package com.darshan.payflow.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.domain.model.PaymentMode
import com.darshan.payflow.domain.repository.PaymentModeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Exposes the persisted payment mode to the navigation root (sandbox badge in every top bar, mode-aware footers). */
@HiltViewModel
class PaymentModeViewModel @Inject constructor(paymentModes: PaymentModeRepository, dispatchers: DispatcherProvider) : ViewModel() {
    val mode: StateFlow<PaymentMode> = paymentModes.mode
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentMode.UPI_APPS)
}
