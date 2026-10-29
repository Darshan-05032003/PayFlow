package com.darshan.payflow.testutil

import com.darshan.payflow.data.psp.PspClient
import com.darshan.payflow.data.psp.PspCollectAnswer
import com.darshan.payflow.data.psp.PspCollectRequest
import com.darshan.payflow.data.psp.PspInitiateRequest
import com.darshan.payflow.data.psp.PspInitiateResponse
import com.darshan.payflow.data.psp.PspPaymentStatus
import com.darshan.payflow.data.psp.VpaVerification

/** Base for one-off PSP stubs in tests: override only what the test needs. */
open class StubPsp : PspClient {
    override suspend fun initiatePayment(request: PspInitiateRequest): PspInitiateResponse = PspInitiateResponse("stub", request.txnRef)
    override suspend fun getPaymentStatus(txnRef: String): PspPaymentStatus = PspPaymentStatus.Pending(txnRef)
    override suspend fun verifyVpa(vpa: String): VpaVerification = VpaVerification.Verified(vpa, "Stub Payee")
    override suspend fun listCollectRequests(): List<PspCollectRequest> = emptyList()
    override suspend fun respondToCollect(requestId: String, approve: Boolean): PspCollectAnswer = error("not stubbed")
}
