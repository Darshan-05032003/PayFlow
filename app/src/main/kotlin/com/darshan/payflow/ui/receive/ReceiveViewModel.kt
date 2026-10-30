package com.darshan.payflow.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.payflow.core.DispatcherProvider
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.upi.ReceiveQrBuilder
import com.darshan.payflow.domain.model.Payment
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.model.UpiProfileRepository
import com.darshan.payflow.domain.model.UpiProfileValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReceiveUiState(
    val loading: Boolean = true,
    val profile: UpiProfile? = null,
    /** Setup/edit form is shown (always when there is no profile yet). */
    val editing: Boolean = false,
    val nameInput: String = "",
    val vpaInput: String = "",
    val nameError: ProfileFieldError? = null,
    val vpaError: ProfileFieldError? = null,
    val amountText: String = "",
    val saving: Boolean = false,
) {
    val amountPaise: Long? get() = Money.parseInrToPaise(amountText)?.takeIf { it in 1..Payment.MAX_AMOUNT_PAISE }
    /** The `upi://pay` link encoded in the QR; null until a profile exists. */
    val qrContent: String? get() = profile?.let { ReceiveQrBuilder.build(it, amountPaise) }
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val profiles: UpiProfileRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = withContext(dispatchers.io) { profiles.get() }
            _uiState.update { it.copy(loading = false, profile = profile, editing = profile == null, nameInput = profile?.name ?: "", vpaInput = profile?.vpa ?: "") }
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(nameInput = value.take(UpiProfile.MAX_NAME_LENGTH + 10), nameError = null) }
    fun onVpaChange(value: String) = _uiState.update { it.copy(vpaInput = value.take(256), vpaError = null) }

    /** Same input rule as the Pay screen: digits, up to two decimals. */
    fun onAmountChange(value: String) {
        if (value.isNotEmpty() && !AMOUNT_INPUT.matches(value)) return
        _uiState.update { it.copy(amountText = value) }
    }

    fun edit() = _uiState.update { it.copy(editing = true, nameInput = it.profile?.name ?: "", vpaInput = it.profile?.vpa ?: "", nameError = null, vpaError = null) }

    fun cancelEdit() {
        if (_uiState.value.profile != null) _uiState.update { it.copy(editing = false, nameError = null, vpaError = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving) return
        when (val result = UpiProfile.validate(state.nameInput, state.vpaInput)) {
            is UpiProfileValidation.Invalid -> _uiState.update { it.copy(nameError = result.name, vpaError = result.vpa) }
            is UpiProfileValidation.Valid -> {
                _uiState.update { it.copy(saving = true) }
                viewModelScope.launch {
                    withContext(dispatchers.io) { profiles.save(result.profile) }
                    _uiState.update { it.copy(saving = false, editing = false, profile = result.profile, nameInput = result.profile.name, vpaInput = result.profile.vpa) }
                }
            }
        }
    }

    companion object {
        private val AMOUNT_INPUT = Regex("""^\d{0,7}(\.\d{0,2})?$""")
    }
}
