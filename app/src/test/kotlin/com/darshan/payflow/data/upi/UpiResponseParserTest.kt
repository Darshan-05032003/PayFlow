package com.darshan.payflow.data.upi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpiResponseParserTest {
    @Test
    fun `parses a standard success response`() {
        val r = UpiResponseParser.parse("txnId=AXI123&responseCode=00&Status=SUCCESS&txnRef=PF1&ApprovalRefNo=911234")
        assertThat(r.txnId).isEqualTo("AXI123")
        assertThat(r.responseCode).isEqualTo("00")
        assertThat(r.status).isEqualTo(UpiAppStatus.SUCCESS)
        assertThat(r.txnRef).isEqualTo("PF1")
        assertThat(r.approvalRefNo).isEqualTo("911234")
    }

    @Test
    fun `keys and status values are case insensitive, as real apps differ`() {
        val r = UpiResponseParser.parse("TXNID=x&responsecode=ZA&status=failure&TxnRef=PF1")
        assertThat(r.txnId).isEqualTo("x")
        assertThat(r.status).isEqualTo(UpiAppStatus.FAILURE)
        assertThat(r.txnRef).isEqualTo("PF1")
        assertThat(UpiResponseParser.parse("Status=Submitted").status).isEqualTo(UpiAppStatus.SUBMITTED)
        assertThat(UpiResponseParser.parse("Status=Pending").status).isEqualTo(UpiAppStatus.SUBMITTED)
    }

    @Test
    fun `garbage, empty and null responses parse to unknown without throwing`() {
        for (raw in listOf(null, "", "   ", "=&=&", "nonsense", "Status", "&&&", "a=b=c&d")) {
            val r = UpiResponseParser.parse(raw)
            assertThat(r.status).isEqualTo(UpiAppStatus.UNKNOWN)
            assertThat(r.txnRef).isNull()
        }
    }

    @Test
    fun `huge responses and oversized values are bounded`() {
        val raw = "Status=SUCCESS&txnRef=" + "A".repeat(200_000) + "&junk=" + "B".repeat(200_000)
        val r = UpiResponseParser.parse(raw)
        assertThat(r.status).isEqualTo(UpiAppStatus.SUCCESS)
        assertThat(r.txnRef!!.length).isAtMost(UpiResponseParser.MAX_VALUE_LENGTH)
    }

    @Test
    fun `first occurrence wins on duplicate keys and values are url decoded`() {
        val r = UpiResponseParser.parse("txnRef=PF1&txnRef=EVIL&Status=SUCCESS%20&txnId=a%26b")
        assertThat(r.txnRef).isEqualTo("PF1")
        assertThat(r.status).isEqualTo(UpiAppStatus.SUCCESS)
        assertThat(r.txnId).isEqualTo("a&b")
    }

    @Test
    fun `control characters are stripped from values`() {
        val r = UpiResponseParser.parse("txnRef=PF\u00001&Status=SUCCESS\n")
        assertThat(r.txnRef).isEqualTo("PF1")
        assertThat(r.status).isEqualTo(UpiAppStatus.SUCCESS)
    }
}
