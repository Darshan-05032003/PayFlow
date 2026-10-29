import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

// Local, gitignored configuration. PLAY_INTEGRITY_CLOUD_PROJECT is the Google Cloud project number
// used by the Play Integrity API request. It is an identifier, not a secret, but it stays out of git.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProperty(name: String): String? =
    (localProperties.getProperty(name) ?: System.getenv(name))?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
val playIntegrityCloudProject: Long = localProperty("PLAY_INTEGRITY_CLOUD_PROJECT")?.toLongOrNull() ?: 0L
val pspBaseUrl: String = localProperty("PSP_BASE_URL") ?: ""

android {
    namespace = "com.darshan.payflow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.darshan.payflow"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "PLAY_INTEGRITY_CLOUD_PROJECT", "${playIntegrityCloudProject}L")
        buildConfigField("String", "PSP_BASE_URL", "\"$pspBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Stress / load simulations live in the `stress` package. They are excluded from the normal unit test
// run and executed only by `./gradlew stressTest`.
val stressPackage = "com.darshan.payflow.stress.*"

tasks.withType<Test>().configureEach {
    if (name != "stressTest") filter { excludeTestsMatching(stressPackage) }
}

tasks.register<Test>("stressTest") {
    description = "Runs the PayFlow load/stress simulations (excluded from testDebugUnitTest)."
    group = "verification"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTest.dependsOn)
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    filter { includeTestsMatching(stressPackage) }
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}

// Coverage: the ≥ 80 % line-coverage rule applies to the domain and data layers (generated code excluded).
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("stressTest")
        }
    }
    reports {
        filters {
            includes {
                classes("com.darshan.payflow.domain.*", "com.darshan.payflow.data.*")
            }
            excludes {
                classes(
                    "*_Impl", "*_Impl\$*", "*_Factory", "*_Factory\$*", "*_HiltModules*", "*_MembersInjector",
                    "*\$\$serializer", "*ComposableSingletons*", "*.BuildConfig",
                )
                annotatedBy("androidx.compose.runtime.Composable", "dagger.Module", "dagger.hilt.InstallIn")
            }
        }
        verify {
            rule("domain and data line coverage") {
                minBound(80)
            }
        }
    }
}
