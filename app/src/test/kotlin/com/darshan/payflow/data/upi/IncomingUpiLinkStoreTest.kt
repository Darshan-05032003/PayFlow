package com.darshan.payflow.data.upi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingUpiLinkStoreTest {
    private val store = IncomingUpiLinkStore()

    @Test
    fun `a valid pay link is parsed, offered once and consumed once`() {
        assertThat(store.offer("upi://pay?pa=test@upi&pn=Test&am=1.00&cu=INR")).isTrue()
        val payload = store.consume()!!
        assertThat(payload.payeeVpa).isEqualTo("test@upi")
        assertThat(payload.payeeName).isEqualTo("Test")
        assertThat(payload.amountPaise).isEqualTo(100)
        assertThat(store.consume()).isNull() // a configuration change must not re-open the Pay screen
    }

    @Test
    fun `hostile or foreign links are dropped`() {
        listOf(
            null, "", "https://evil.example/upi://pay?pa=a@b", "upi://mandate?pa=a@b", "upi://pay", "upi://pay?pa=javascript:alert(1)@x",
            "upi://pay?pa=a@b&am=-5", "upi://pay?pa=a@b&am=100000.01", "upi://pay?pa=a@b&cu=USD", "upi://pay?pa=‮a@b",
            "upi://pay?pa=a@b&" + "x".repeat(10_000), "intent://pay?pa=a@b#Intent;scheme=upi;end",
        ).forEach { raw ->
            assertThat(store.offer(raw)).isFalse()
            assertThat(store.pending.value).isNull()
        }
    }

    @Test
    fun `the newest link replaces an unconsumed older one`() {
        store.offer("upi://pay?pa=first@upi")
        store.offer("upi://pay?pa=second@upi")
        assertThat(store.consume()!!.payeeVpa).isEqualTo("second@upi")
    }
}
