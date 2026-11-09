package com.darshan.payflow.ui.designsystem.catalog

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DesignCatalogTest {
    @get:Rule val rule = createAndroidComposeRule<DesignCatalogActivity>()

    @Test
    fun every_section_is_reachable_by_scrolling() {
        for (section in CatalogSections) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(section.title))
            rule.onNodeWithText(section.title).assertExists()
        }
    }

    @Test
    fun theme_and_reduce_motion_switches_work() {
        rule.onNodeWithText("Dark theme").assertIsOff().performClick()
        rule.onNodeWithText("Dark theme").assertIsOn()
        rule.onNodeWithText("Reduce motion").assertIsOff().performClick()
        rule.onNodeWithText("Reduce motion").assertIsOn()
    }

    @Test
    fun section_keys_are_unique_because_they_name_the_baselines() {
        assertThat(CatalogSections.map { it.key }).containsNoDuplicates()
    }
}

/** The catalog is debug-only by construction. These checks fail if anything in `src/main` starts to depend on it. */
class CatalogReleaseIsolationTest {
    private val main = File("src/main")

    @Test
    fun no_file_in_main_mentions_the_catalog() {
        assertThat(main.isDirectory).isTrue()
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { file -> file.readText().contains("DesignCatalog") || file.readText().contains("designsystem.catalog") }
            .map { it.path }
            .toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun the_activity_is_declared_only_in_the_debug_manifest() {
        assertThat(File("src/main/AndroidManifest.xml").readText()).doesNotContain("catalog")
        assertThat(File("src/debug/AndroidManifest.xml").readText()).contains(".ui.designsystem.catalog.DesignCatalogActivity")
    }

    @Test
    fun the_catalog_sources_live_in_the_debug_source_set() {
        assertThat(File("src/debug/kotlin/com/darshan/payflow/ui/designsystem/catalog/DesignCatalog.kt").isFile).isTrue()
        assertThat(File("src/main/kotlin/com/darshan/payflow/ui/designsystem/catalog").exists()).isFalse()
    }

    @Test
    fun catalog_samples_hold_no_real_looking_secrets() {
        val text = File("src/debug/kotlin/com/darshan/payflow/ui/designsystem/catalog/DesignCatalog.kt").readText()
        assertThat(text).doesNotContainMatch("(?i)(api[_-]?key|secret|password|AIza)")
    }
}
