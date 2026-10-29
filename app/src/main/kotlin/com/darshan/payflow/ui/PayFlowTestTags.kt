package com.darshan.payflow.ui

import com.darshan.payflow.domain.model.HistoryFilter

/** Stable test tags for Compose UI tests. */
object PayFlowTestTags {
    const val LOADING = "payflow:loading"
    const val EMPTY = "payflow:empty"
    const val ERROR = "payflow:error"
    const val SEARCH_FIELD = "payflow:history:search"
    const val PAYMENT_LIST = "payflow:history:list"
    fun filterChip(filter: HistoryFilter) = "payflow:history:chip:${filter.name}"
    fun paymentRow(id: String) = "payflow:payment:$id"
}
