package com.darshan.payflow.data.security

import com.darshan.payflow.testutil.FIXED_INSTANT
import com.darshan.payflow.testutil.fixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class DeviceBindingTest {
    private val store = InMemorySecureStore()
    private val sms = FakeOtpSender()
    private var fingerprint = "fp-device-A"
    private val identity = object : DeviceIdentity { override fun fingerprint() = fingerprint }
    private fun manager(clock: Clock = fixedClock) = DeviceBindingManager(store, identity, sms, clock, random = kotlin.random.Random(7))

    @Test
    fun `fingerprint is a stable sha-256 of hardware identifiers`() {
        val a = AndroidDeviceIdentity.fingerprintOf(androidId = "abc123", brand = "Google", model = "Pixel 7", board = "lynx")
        val b = AndroidDeviceIdentity.fingerprintOf(androidId = "abc123", brand = "Google", model = "Pixel 7", board = "lynx")
        val c = AndroidDeviceIdentity.fingerprintOf(androidId = "abc124", brand = "Google", model = "Pixel 7", board = "lynx")
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).matches("^[0-9a-f]{64}$")
        assertThat(a).doesNotContain("abc123")
    }

    @Test
    fun `request otp validates the phone and sends a six digit code`() = runTest {
        val m = manager()
        assertThat(m.requestOtp("12345")).isEqualTo(OtpRequest.InvalidPhone)
        assertThat(m.requestOtp("+91 98765 43210")).isInstanceOf(OtpRequest.Sent::class.java)
        assertThat(sms.sent.single().phone).isEqualTo("+919876543210")
        assertThat(sms.sent.single().code).matches("^\\d{6}$")
        assertThat(m.status()).isEqualTo(BindingStatus.OtpPending(maskedPhone = "+91 ****3210"))
    }

    @Test
    fun `verify binds the device with the right code, wrong codes are limited`() = runTest {
        val m = manager()
        m.requestOtp("9876543210")
        val code = sms.sent.single().code
        assertThat(m.verifyOtp("000000")).isEqualTo(OtpVerification.Wrong(remaining = 2))
        assertThat(m.verifyOtp("000001")).isEqualTo(OtpVerification.Wrong(remaining = 1))
        assertThat(m.verifyOtp(code)).isEqualTo(OtpVerification.Bound)
        val status = m.status() as BindingStatus.Bound
        assertThat(status.maskedPhone).isEqualTo("+91 ****3210")
        assertThat(status.fingerprint).isEqualTo("fp-device-A")
        assertThat(store.getAll().values.joinToString()).doesNotContain("9876543210") // encrypted at rest (fake store keeps ciphertext)
    }

    @Test
    fun `three wrong codes burn the otp, expired codes are refused`() = runTest {
        val m = manager()
        m.requestOtp("9876543210")
        val code = sms.sent.single().code
        assertThat(m.verifyOtp("999999")).isEqualTo(OtpVerification.Wrong(remaining = 2))
        assertThat(m.verifyOtp("999999")).isEqualTo(OtpVerification.Wrong(remaining = 1))
        assertThat(m.verifyOtp("999999")).isEqualTo(OtpVerification.Expired) // third wrong code burns it
        assertThat(m.verifyOtp(code)).isEqualTo(OtpVerification.Expired)
        assertThat(m.status()).isEqualTo(BindingStatus.Unbound)

        var now = FIXED_INSTANT
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val m2 = DeviceBindingManager(InMemorySecureStore(), identity, sms, clock, random = kotlin.random.Random(1))
        m2.requestOtp("9876543210")
        val code2 = sms.sent.last().code
        now = now.plusSeconds(DeviceBindingManager.OTP_TTL_SECONDS + 1)
        assertThat(m2.verifyOtp(code2)).isEqualTo(OtpVerification.Expired)
    }

    @Test
    fun `a different device fingerprint breaks the binding`() = runTest {
        val m = manager()
        m.requestOtp("9876543210"); m.verifyOtp(sms.sent.single().code)
        assertThat(m.status()).isInstanceOf(BindingStatus.Bound::class.java)
        fingerprint = "fp-device-B" // restored backup / cloned app on another phone
        assertThat(manager().status()).isEqualTo(BindingStatus.Mismatch(boundTo = "fp-device-A", maskedPhone = "+91 ****3210"))
        manager().unbind()
        assertThat(manager().status()).isEqualTo(BindingStatus.Unbound)
    }

    @Test
    fun `otp codes are unpredictable and never reused`() = runTest {
        val m = manager()
        val codes = (1..50).map { m.requestOtp("9876543210"); sms.sent.last().code }
        assertThat(codes.toSet().size).isGreaterThan(40)
    }
}
