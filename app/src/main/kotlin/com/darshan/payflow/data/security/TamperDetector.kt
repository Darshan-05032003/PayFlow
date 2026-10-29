package com.darshan.payflow.data.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raw facts about the device/process. Wrapped so the checks are pure and testable with fakes. */
interface TamperProbe {
    fun fileExists(path: String): Boolean
    fun readLines(path: String): List<String>
    fun buildTags(): String
    fun isDebuggable(): Boolean
    fun isDebuggerConnected(): Boolean
    /** SHA-256 of the current signing certificate (hex), or null if it cannot be read. */
    fun signingCertSha256(): String?
}

enum class TamperSeverity { LOW, MEDIUM, HIGH }

enum class TamperFinding(val severity: TamperSeverity, val blocksPayments: Boolean) {
    SU_BINARY(TamperSeverity.HIGH, blocksPayments = true),
    MAGISK(TamperSeverity.HIGH, blocksPayments = true),
    TEST_KEYS(TamperSeverity.MEDIUM, blocksPayments = false),
    FRIDA_PORT(TamperSeverity.HIGH, blocksPayments = true),
    FRIDA_LIBRARY(TamperSeverity.HIGH, blocksPayments = true),
    DEBUGGER(TamperSeverity.LOW, blocksPayments = false),
    DEBUGGABLE(TamperSeverity.LOW, blocksPayments = false),
    SIGNATURE_MISMATCH(TamperSeverity.HIGH, blocksPayments = true),
    SIGNATURE_UNREADABLE(TamperSeverity.LOW, blocksPayments = false),
}

data class TamperReport(val findings: List<TamperFinding>) {
    val isCompromised: Boolean get() = findings.any { it.severity == TamperSeverity.HIGH }
    val shouldBlockPayments: Boolean get() = findings.any { it.blocksPayments }

    companion object {
        val CLEAN = TamperReport(emptyList())
    }
}

/** Pure root / tamper heuristics. Each probe failure is swallowed: a check that cannot run is simply not a finding. */
object TamperChecks {
    val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
        "/data/local/bin/su", "/data/local/xbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/system/xbin/daemonsu",
    )
    val MAGISK_PATHS = listOf("/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules", "/cache/.disable_magisk", "/dev/.magisk.unblock")
    val FRIDA_PORTS_HEX = setOf("69A2", "69A3") // 27042, 27043
    val FRIDA_MARKERS = listOf("frida", "gadget", "linjector", "re.frida.server")

    fun scan(probe: TamperProbe, expectedSignatureSha256: String?): TamperReport {
        val findings = ArrayList<TamperFinding>()
        safe { if (SU_PATHS.any(probe::fileExists)) findings += TamperFinding.SU_BINARY }
        safe { if (MAGISK_PATHS.any(probe::fileExists)) findings += TamperFinding.MAGISK }
        safe { if (probe.buildTags().contains("test-keys")) findings += TamperFinding.TEST_KEYS }
        safe { if (listensOnFridaPort(probe.readLines("/proc/net/tcp")) || listensOnFridaPort(probe.readLines("/proc/net/tcp6"))) findings += TamperFinding.FRIDA_PORT }
        safe { if (probe.readLines("/proc/self/maps").any { line -> FRIDA_MARKERS.any { line.contains(it, ignoreCase = true) } }) findings += TamperFinding.FRIDA_LIBRARY }
        safe { if (probe.isDebuggerConnected()) findings += TamperFinding.DEBUGGER }
        safe { if (probe.isDebuggable()) findings += TamperFinding.DEBUGGABLE }
        val expected = expectedSignatureSha256?.let(::normaliseSha256)?.takeIf { it.isNotEmpty() }
        if (expected != null) {
            safe {
                val actual = probe.signingCertSha256()?.let(::normaliseSha256)
                when {
                    actual == null -> findings += TamperFinding.SIGNATURE_UNREADABLE
                    actual != expected -> findings += TamperFinding.SIGNATURE_MISMATCH
                }
            }
        }
        return TamperReport(findings)
    }

    /** `/proc/net/tcp` rows: `sl local_address rem_address st …`; the local port is the hex after the last ':' of field 1. */
    fun listensOnFridaPort(lines: List<String>): Boolean = lines.drop(0).any { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4 || !fields[0].endsWith(":")) return@any false
        val port = fields[1].substringAfterLast(':').uppercase()
        port in FRIDA_PORTS_HEX && fields[3] == "0A" // 0A = LISTEN
    }

    fun normaliseSha256(value: String): String = value.replace(":", "").trim().lowercase()

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { /* a probe that cannot run is not evidence */ }
    }
}

class AndroidTamperProbe @Inject constructor(@ApplicationContext private val context: Context) : TamperProbe {
    override fun fileExists(path: String): Boolean = File(path).exists()
    override fun readLines(path: String): List<String> = File(path).takeIf { it.exists() }?.useLines { it.take(4_096).toList() } ?: emptyList()
    override fun buildTags(): String = Build.TAGS ?: ""
    override fun isDebuggable(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    override fun isDebuggerConnected(): Boolean = Debug.isDebuggerConnected()

    override fun signingCertSha256(): String? = runCatching {
        val pm = context.packageManager
        val cert: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
        cert?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
    }.getOrNull()
}

interface TamperDetector {
    suspend fun scan(): TamperReport
}

@Singleton
class AndroidTamperDetector @Inject constructor(
    private val probe: AndroidTamperProbe,
    @ExpectedSignature private val expectedSignatureSha256: String?,
) : TamperDetector {
    override suspend fun scan(): TamperReport = withContext(Dispatchers.IO) { TamperChecks.scan(probe, expectedSignatureSha256) }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ExpectedSignature
