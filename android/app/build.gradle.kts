import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from a properties file kept outside the repo:
// -Psigning.props=/path/to/keystore.properties, or android/keystore.properties.
// Keys: storeFile, storePassword, keyAlias, keyPassword.
val signingProps = Properties().apply {
    val path = findProperty("signing.props") as String?
    val f = if (path != null) file(path) else rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.suppliertracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.suppliertracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        if (!signingProps.isEmpty) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // A company's own report template can be built into a private APK: put it at
    // android/private/assets/company-template.pptx (git-ignored, never in the public repo).
    sourceSets.getByName("main").assets.srcDir("../private/assets")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric downloads its Android jars; use the same Maven Central mirror as the build
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                // optional local screenshot run: -Pscreenshot.data=/path/data.json -Pscreenshot.out=/path/dir
                it.systemProperty("screenshot.data", findProperty("screenshot.data") ?: "")
                it.systemProperty("screenshot.out", findProperty("screenshot.out") ?: "")
                // optional local report run: -Preport.template=/path/template.pptx -Preport.data=/path/data.json -Preport.out=/path/dir
                for (k in listOf("report.template", "report.data", "report.out", "vendor.file", "vendor.data", "vendor.out")) it.systemProperty(k, findProperty(k) ?: "")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 brings fragment 1.2.5, whose FragmentActivity rejects the request codes of the system
    // pickers ("Can only use lower 16 bits for requestCode") and crashed the app on every file / folder picker
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // the fingerprint dialog on Android 8 is an AppCompat dialog: the app theme is AppCompat (res/values/themes.xml)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // nightly auto backup into a folder the user picks
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
