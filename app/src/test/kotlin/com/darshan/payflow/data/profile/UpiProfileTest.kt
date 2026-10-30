package com.darshan.payflow.data.profile

import com.darshan.payflow.data.security.InMemorySecureStore
import com.darshan.payflow.data.upi.ReceiveQrBuilder
import com.darshan.payflow.domain.model.ProfileFieldError
import com.darshan.payflow.domain.model.UpiProfile
import com.darshan.payflow.domain.model.UpiProfileValidation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UpiProfileTest {
    @Test
    fun `profile validation normalises the vpa and strips invisible characters from the name`() {
        val valid = UpiProfile.validate("  Darshan​ Sonawane ", " Darshan@OKAXIS ") as UpiProfileValidation.Valid
        assertThat(valid.profile).isEqualTo(UpiProfile("Darshan Sonawane", "darshan@okaxis"))
        assertThat(UpiProfile.validate("", "")).isEqualTo(UpiProfileValidation.Invalid(ProfileFieldError.REQUIRED, ProfileFieldError.REQUIRED))
        assertThat(UpiProfile.validate("x".repeat(51), "a@b")).isEqualTo(UpiProfileValidation.Invalid(ProfileFieldError.TOO_LONG, ProfileFieldError.INVALID_VPA))
        assertThat(UpiProfile.validate("Ok", "javascript:alert(1)@x")).isEqualTo(UpiProfileValidation.Invalid(null, ProfileFieldError.INVALID_VPA))
    }

    @Test
    fun `profile round trips through the encrypted store and never sits in clear`() = runTest {
        val store = InMemorySecureStore()
        val repo = SecureUpiProfileRepository(store)
        assertThat(repo.get()).isNull()
        repo.save(UpiProfile("Darshan", "darshan@okaxis"))
        assertThat(repo.profile.first()).isEqualTo(UpiProfile("Darshan", "darshan@okaxis"))
        assertThat(store.getAll().values.single()).doesNotContain("darshan@okaxis")
        // A fresh repository over the same store reads it back; a corrupted record reads as absent.
        assertThat(SecureUpiProfileRepository(store).get()).isEqualTo(UpiProfile("Darshan", "darshan@okaxis"))
        store.put(SecureUpiProfileRepository.KEY, "{not json")
        assertThat(SecureUpiProfileRepository(store).get()).isNull()
        repo.clear()
        assertThat(repo.get()).isNull()
    }

    @Test
    fun `receive qr link is percent encoded and cannot be injected through the name`() {
        val link = ReceiveQrBuilder.build(UpiProfile("Darshan & Co=1&am=999", "darshan@okaxis"))
        assertThat(link).isEqualTo("upi://pay?pa=darshan%40okaxis&pn=Darshan%20%26%20Co%3D1%26am%3D999&cu=INR")
        assertThat(ReceiveQrBuilder.build(UpiProfile("D", "d@okaxis"), 1_50_000_50)).isEqualTo("upi://pay?pa=d%40okaxis&pn=D&am=150000.50&cu=INR")
        assertThat(ReceiveQrBuilder.build(UpiProfile("D", "d@okaxis"), 0)).doesNotContain("am=")
        // What we emit must parse back with the scanner's parser (another UPI app will do the same).
        val parsed = com.darshan.payflow.data.qr.UpiQrPayloadParser.parse(link) as com.darshan.payflow.data.qr.QrParseResult.Valid
        assertThat(parsed.payload.payeeVpa).isEqualTo("darshan@okaxis")
        assertThat(parsed.payload.payeeName).isEqualTo("Darshan & Co=1&am=999")
        assertThat(parsed.payload.amountPaise).isNull()
    }
}
