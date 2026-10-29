package com.darshan.payflow.domain.usecase

import com.darshan.payflow.data.psp.FakePsp
import com.darshan.payflow.data.psp.FakePspConfig
import com.darshan.payflow.data.psp.PspException
import com.darshan.payflow.data.psp.VpaVerification
import com.darshan.payflow.domain.model.VpaError
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VerifyVpaUseCaseTest {
    @Test
    fun `invalid format never calls the psp`() = runTest {
        val psp = FakePsp(fixedClock)
        val result = VerifyVpaUseCase(psp).invoke("not-a-vpa")
        assertThat(result).isEqualTo(VpaVerifyResult.InvalidFormat(VpaError.MISSING_HANDLE))
        assertThat(psp.verifyCalls).isEqualTo(0)
    }

    @Test
    fun `known vpa returns the registered payee name, normalised`() = runTest {
        val psp = FakePsp(fixedClock)
        val result = VerifyVpaUseCase(psp).invoke("  Alice@OkBank ")
        assertThat(result).isEqualTo(VpaVerifyResult.Verified("alice@okbank", "Alice Kumar"))
    }

    @Test
    fun `unknown handle or user is not found`() = runTest {
        val psp = FakePsp(fixedClock)
        assertThat(VerifyVpaUseCase(psp).invoke("unknown@okbank")).isEqualTo(VpaVerifyResult.NotFound("unknown@okbank"))
        assertThat(VerifyVpaUseCase(psp).invoke("alice@nobank")).isEqualTo(VpaVerifyResult.NotFound("alice@nobank"))
    }

    @Test
    fun `psp failure is unavailable, not not-found`() = runTest {
        val psp = FakePsp(fixedClock, FakePspConfig(timeoutRate = 1.0))
        val result = VerifyVpaUseCase(psp).invoke("alice@okbank")
        assertThat(result).isInstanceOf(VpaVerifyResult.Unavailable::class.java)
        assertThat((result as VpaVerifyResult.Unavailable).error).isInstanceOf(PspException.Timeout::class.java)
    }

    @Test
    fun `fake directory names are deterministic and printable`() = runTest {
        val psp = FakePsp(fixedClock)
        val a = psp.verifyVpa("ravi.s@ybl") as VpaVerification.Verified
        val b = psp.verifyVpa("ravi.s@ybl") as VpaVerification.Verified
        assertThat(a).isEqualTo(b)
        assertThat(a.payeeName).matches("^[A-Za-z .]{2,60}$")
    }
}
