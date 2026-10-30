package com.darshan.payflow.testutil

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspCollectAnswer
import com.darshan.payflow.data.psp.PspCollectRequest
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspInitiateResponse
import com.darshan.payflow.data.psp.PspMandate
import com.darshan.payflow.data.psp.PspMandateRequest
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.psp.VpaVerification

/** Base for one-off PSP stubs in tests: override only what the test needs. */
open class StubPsp : PspClient {
    override suspend fun initiatePayment(request: PspInitiateRequest): PspInitiateResponse = PspInitiateResponse("stub", request.txnRef)
    override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus = PspPaymentStatus.Pending(txnRef)
    override suspend fun verifyVpa(vpa: String): VpaVerification = VpaVerification.Verified(vpa, "Stub Payee")
    override suspend fun listCollectRequests(): List<PspCollectRequest> = emptyList()
    override suspend fun respondToCollect(requestId: String, approve: Boolean): PspCollectAnswer = error("not stubbed")
    override suspend fun createMandate(request: PspMandateRequest): PspMandate = PspMandate("UMN-stub", com.darshan.payflow.domain.model.MandateStatus.ACTIVE)
    override suspend fun updateMandate(umn: String, action: String): PspMandate = error("not stubbed")
    override suspend fun executeMandate(umn: String, txnRef: String, amountPaise: Long): PspInitiateResponse = PspInitiateResponse("stub", txnRef)
    override suspend fun refund(txnRef: String, amountPaise: Long, refundKey: String): com.darshan.payflow.data.psp.PspRefund = error("not stubbed")
}
