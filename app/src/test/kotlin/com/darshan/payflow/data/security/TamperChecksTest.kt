package com.darshan.payflow.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TamperChecksTest {
    private class FakeProbe(
        val files: Set<String> = emptySet(),
        val lines: Map<String, List<String>> = emptyMap(),
        val tags: String = "release-keys",
        val debuggable: Boolean = false,
        val debugger: Boolean = false,
        val signature: String? = "abc",
    ) : TamperProbe {
        override fun fileExists(path: String) = path in files
        override fun readLines(path: String): List<String> = lines[path] ?: emptyList()
        override fun buildTags() = tags
        override fun isDebuggable() = debuggable
        override fun isDebuggerConnected() = debugger
        override fun signingCertSha256() = signature
    }

    private fun scan(probe: TamperProbe, expectedSignature: String? = "abc") = TamperChecks.scan(probe, expectedSignature)

    @Test
    fun `a clean device reports nothing`() {
        val report = scan(FakeProbe())
        assertThat(report.findings).isEmpty()
        assertThat(report.isCompromised).isFalse()
        assertThat(report.shouldBlockPayments).isFalse()
    }

    @Test
    fun `su binaries, test keys and magisk are root indicators`() {
        assertThat(scan(FakeProbe(files = setOf("/system/xbin/su"))).findings).containsExactly(TamperFinding.SU_BINARY)
        assertThat(scan(FakeProbe(files = setOf("/sbin/.magisk"))).findings).containsExactly(TamperFinding.MAGISK)
        assertThat(scan(FakeProbe(tags = "test-keys")).findings).containsExactly(TamperFinding.TEST_KEYS)
        val rooted = scan(FakeProbe(files = setOf("/system/bin/su", "/data/adb/magisk")))
        assertThat(rooted.findings).containsExactly(TamperFinding.SU_BINARY, TamperFinding.MAGISK)
        assertThat(rooted.isCompromised).isTrue()
        assertThat(rooted.shouldBlockPayments).isTrue()
    }

    @Test
    fun `frida is detected by its default ports in proc net tcp and by its libraries in the process maps`() {
        // 27042 = 0x69A2 listening on all interfaces
        val tcp = listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 00000000:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0",
        )
        assertThat(scan(FakeProbe(lines = mapOf("/proc/net/tcp" to tcp))).findings).containsExactly(TamperFinding.FRIDA_PORT)
        val tcp6 = listOf("   0: 00000000000000000000000000000000:69A3 00000000000000000000000000000000:0000 0A ...")
        assertThat(scan(FakeProbe(lines = mapOf("/proc/net/tcp6" to tcp6))).findings).containsExactly(TamperFinding.FRIDA_PORT)
        val maps = listOf("7f0000000000-7f0000001000 r-xp 00000000 fd:00 1 /data/local/tmp/re.frida.server/frida-agent-64.so")
        assertThat(scan(FakeProbe(lines = mapOf("/proc/self/maps" to maps))).findings).containsExactly(TamperFinding.FRIDA_LIBRARY)
        // an unrelated port that merely contains the hex digits is not a hit
        val other = listOf("   0: 00000000:169A2 00000000:0000 0A ...", "   1: 0100007F:1F90 00000000:0000 0A ...")
        assertThat(scan(FakeProbe(lines = mapOf("/proc/net/tcp" to other))).findings).isEmpty()
    }

    @Test
    fun `debugger and debuggable flag are low severity, signature mismatch is high`() {
        assertThat(scan(FakeProbe(debugger = true)).findings).containsExactly(TamperFinding.DEBUGGER)
        assertThat(scan(FakeProbe(debuggable = true)).findings).containsExactly(TamperFinding.DEBUGGABLE)
        val tampered = scan(FakeProbe(signature = "zzz"))
        assertThat(tampered.findings).containsExactly(TamperFinding.SIGNATURE_MISMATCH)
        assertThat(tampered.shouldBlockPayments).isTrue()
        assertThat(scan(FakeProbe(debugger = true)).shouldBlockPayments).isFalse()
    }

    @Test
    fun `signature check is skipped when no expected value is configured or the cert cannot be read`() {
        assertThat(scan(FakeProbe(signature = "zzz"), expectedSignature = null).findings).isEmpty()
        assertThat(scan(FakeProbe(signature = "zzz"), expectedSignature = "").findings).isEmpty()
        assertThat(scan(FakeProbe(signature = null)).findings).containsExactly(TamperFinding.SIGNATURE_UNREADABLE)
        assertThat(TamperChecks.normaliseSha256("AB:CD:ef")).isEqualTo("abcdef")
    }

    @Test
    fun `probe failures never crash the scan`() {
        val throwing = object : TamperProbe {
            override fun fileExists(path: String) = throw SecurityException("no")
            override fun readLines(path: String) = throw java.io.IOException("no")
            override fun buildTags() = throw IllegalStateException("no")
            override fun isDebuggable() = false
            override fun isDebuggerConnected() = false
            override fun signingCertSha256() = "abc"
        }
        assertThat(scan(throwing).findings).isEmpty()
    }
}
