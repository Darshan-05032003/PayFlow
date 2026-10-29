package com.darshan.payflow.data.upi

import java.net.URLDecoder

enum class UpiAppStatus { SUCCESS, FAILURE, SUBMITTED, UNKNOWN }

/** What the UPI app said. This is *informational only*: the PSP status check decides the outcome. */
data class UpiAppResponse(
    val txnId: String?,
    val responseCode: String?,
    val status: UpiAppStatus,
    val txnRef: String?,
    val approvalRefNo: String?,
) {
    companion object {
        val UNKNOWN = UpiAppResponse(null, null, UpiAppStatus.UNKNOWN, null, null)
    }
}

/**
 * Parses the `response` extra returned by UPI apps (`txnId=…&responseCode=…&Status=…&txnRef=…&ApprovalRefNo=…`).
 * Hostile input (huge, duplicated keys, control chars, odd casing) is tolerated and bounded; it never throws.
 */
object UpiResponseParser {
    const val MAX_RAW_LENGTH = 16 * 1024
    const val MAX_VALUE_LENGTH = 256

    fun parse(raw: String?): UpiAppResponse {
        if (raw.isNullOrBlank()) return UpiAppResponse.UNKNOWN
        val map = HashMap<String, String>()
        for (pair in raw.take(MAX_RAW_LENGTH).split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).trim().lowercase()
            if (key.isEmpty() || map.containsKey(key)) continue // first occurrence wins
            map[key] = clean(pair.substring(eq + 1))
        }
        if (map.isEmpty()) return UpiAppResponse.UNKNOWN
        val status = when (map["status"]?.trim()?.lowercase()) {
            "success" -> UpiAppStatus.SUCCESS
            "failure", "failed" -> UpiAppStatus.FAILURE
            "submitted", "pending" -> UpiAppStatus.SUBMITTED
            else -> UpiAppStatus.UNKNOWN
        }
        return UpiAppResponse(
            txnId = map["txnid"]?.takeIf { it.isNotEmpty() },
            responseCode = map["responsecode"]?.takeIf { it.isNotEmpty() },
            status = status,
            txnRef = map["txnref"]?.takeIf { it.isNotEmpty() },
            approvalRefNo = map["approvalrefno"]?.takeIf { it.isNotEmpty() },
        )
    }

    private fun clean(value: String): String {
        val decoded = runCatching { URLDecoder.decode(value.take(MAX_VALUE_LENGTH * 3), "UTF-8") }.getOrDefault(value)
        return decoded.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.take(MAX_VALUE_LENGTH)
    }
}
