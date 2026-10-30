package com.darshan.payflow.data.upi

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 11+ package visibility: PayFlow can only list the UPI apps its manifest `<queries>` declares.
 *
 * Real UPI apps (GPay, PhonePe, Paytm, BHIM) register `upi://pay`, i.e. scheme AND host. A host-less `<data scheme="upi"/>`
 * query does not match a filter that requires a host, so those apps stayed invisible and only force-queryable preinstalled
 * apps (Amazon on some phones) plus PayFlow's own debug handler showed up in the app sheet. Found on a real phone.
 */
class UpiPackageVisibilityTest {

    private val manifest: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.attr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private val queries: Element get() = manifest.children("queries").single()

    @Test
    fun queriesDeclareTheUpiPayHostSoRealUpiAppsAreVisible() {
        val upiPayQuery = queries.children("intent").any { intent ->
            intent.children("action").any { it.attr("name") == "android.intent.action.VIEW" } &&
                intent.children("data").any { it.attr("scheme") == "upi" && it.attr("host") == "pay" }
        }
        assertThat(upiPayQuery).isTrue()
    }

    @Test
    fun hostLessUpiQueryIsKeptForAppsThatRegisterTheSchemeOnly() {
        val schemeOnly = queries.children("intent").any { intent ->
            intent.children("data").any { it.attr("scheme") == "upi" && it.attr("host").isEmpty() }
        }
        assertThat(schemeOnly).isTrue()
    }

    @Test
    fun majorUpiAppsAreDeclaredByPackageAsAFallback() {
        val packages = queries.children("package").map { it.attr("name") }
        assertThat(packages).containsAtLeast(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp", // BHIM
            "in.amazon.mShop.android.shopping", // Amazon Pay UPI
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
