package com.darshan.payflow.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.crypto.AEADBadTagException

class AesGcmCipherTest {
    private val box = InMemoryCryptoBox()

    @Test
    fun `round trips bytes and strings with a fresh iv every time`() {
        val plain = "alice@okbank|9876543210".toByteArray()
        val a = box.encrypt(plain)
        val b = box.encrypt(plain)
        assertThat(a).isNotEqualTo(b) // random 12-byte IV
        assertThat(box.decrypt(a)).isEqualTo(plain)
        assertThat(box.decrypt(b)).isEqualTo(plain)
        assertThat(box.decryptString(box.encryptString("hello ₹"))).isEqualTo("hello ₹")
        assertThat(a.size).isEqualTo(AesGcmCipher.IV_BYTES + plain.size + AesGcmCipher.TAG_BYTES)
    }

    @Test
    fun `any flipped bit is detected`() {
        val cipher = box.encrypt("secret".toByteArray())
        for (i in cipher.indices step 3) {
            val tampered = cipher.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThat(runCatching { box.decrypt(tampered) }.exceptionOrNull()).isInstanceOf(AEADBadTagException::class.java)
        }
    }

    @Test
    fun `aad binds the ciphertext to its purpose`() {
        val cipher = box.encrypt("pin-record".toByteArray(), aad = "pin".toByteArray())
        assertThat(box.decrypt(cipher, aad = "pin".toByteArray())).isEqualTo("pin-record".toByteArray())
        assertThat(runCatching { box.decrypt(cipher, aad = "binding".toByteArray()) }.exceptionOrNull()).isInstanceOf(AEADBadTagException::class.java)
        assertThat(runCatching { box.decrypt(cipher) }.exceptionOrNull()).isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `a different key cannot decrypt and garbage is rejected`() {
        val other = InMemoryCryptoBox()
        val cipher = box.encrypt("x".toByteArray())
        assertThat(runCatching { other.decrypt(cipher) }.exceptionOrNull()).isInstanceOf(AEADBadTagException::class.java)
        assertThat(runCatching { box.decrypt(ByteArray(0)) }.isFailure).isTrue()
        assertThat(runCatching { box.decrypt(ByteArray(5)) }.isFailure).isTrue()
    }

    @Test
    fun `empty and large payloads work`() {
        assertThat(box.decrypt(box.encrypt(ByteArray(0)))).isEmpty()
        val big = ByteArray(1024 * 1024) { it.toByte() }
        assertThat(box.decrypt(box.encrypt(big))).isEqualTo(big)
    }
}
