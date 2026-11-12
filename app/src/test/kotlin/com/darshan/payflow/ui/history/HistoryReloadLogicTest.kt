package com.darshan.payflow.ui.history

import com.darshan.payflow.domain.model.HistoryFilter
import com.darshan.payflow.testutil.testPayment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryReloadLogicTest {
    private val payments = listOf(testPayment("a"))
    private val content = HistoryUiState.Content(payments, HistoryFilter.ALL, "", emptyList())
    private val empty = HistoryUiState.Empty(HistoryFilter.PENDING, "")

    @Test
    fun aRefreshInPlaceKeepsWhatWasOnScreen() {
        assertThat(HistoryReload.shown(HistoryUiState.Loading, previous = content, inPlace = true)).isEqualTo(content)
        assertThat(HistoryReload.shown(HistoryUiState.Loading, previous = empty, inPlace = true)).isEqualTo(empty)
    }

    @Test
    fun theFirstLoadAndARetryShowTheSkeleton() {
        assertThat(HistoryReload.shown(HistoryUiState.Loading, previous = null, inPlace = true)).isEqualTo(HistoryUiState.Loading)
        assertThat(HistoryReload.shown(HistoryUiState.Loading, previous = content, inPlace = false)).isEqualTo(HistoryUiState.Loading)
        // Nothing worth keeping behind an error: a reload from there is a retry.
        assertThat(HistoryReload.shown(HistoryUiState.Loading, previous = HistoryUiState.Error("x"), inPlace = true)).isEqualTo(HistoryUiState.Loading)
    }

    @Test
    fun anAnswerIsAlwaysShownAsItIs() {
        assertThat(HistoryReload.shown(content, previous = empty, inPlace = true)).isEqualTo(content)
        assertThat(HistoryReload.shown(empty, previous = content, inPlace = false)).isEqualTo(empty)
    }

    @Test
    fun theSpinnerIsOnlyForARefreshInPlace() {
        assertThat(HistoryReload.spinner(HistoryUiState.Loading, inPlace = true)).isTrue()
        assertThat(HistoryReload.spinner(HistoryUiState.Loading, inPlace = false)).isFalse()
        assertThat(HistoryReload.spinner(content, inPlace = true)).isFalse()
    }
}
