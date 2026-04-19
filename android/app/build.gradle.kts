import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val metaAppId: String = localProps.getProperty("META_WEARABLES_APP_ID") ?: ""
val metaClientToken: String = localProps.getProperty("META_WEARABLES_CLIENT_TOKEN") ?: ""
val metaDatVersion: String = localProps.getProperty("META_WEARABLES_DAT_VERSION") ?: "0.6.0"
val metaAnalyticsOptOut: String = localProps.getProperty("META_WEARABLES_ANALYTICS_OPT_OUT") ?: "false"
val metaEnableMockDevice: String = localProps.getProperty("META_WEARABLES_ENABLE_MOCK_DEVICE") ?: "false"

android {
    namespace = "com.aegisvision.medbud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aegisvision.medbud"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // DAT SDK reads these from AndroidManifest <meta-data> entries.
        manifestPlaceholders["metaAppId"] = metaAppId
        manifestPlaceholders["metaClientToken"] = metaClientToken

        buildConfigField("String", "META_DAT_VERSION", "\"$metaDatVersion\"")
        buildConfigField("boolean", "META_ANALYTICS_OPT_OUT", metaAnalyticsOptOut)
        buildConfigField("boolean", "META_ENABLE_MOCK_DEVICE", metaEnableMockDevice)
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.3")

    // WebSocket (signaling)
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // JSON
    implementation("org.json:json:20240303")

    // Meta Wearables DAT SDK (GitHub Packages). Version from local.properties.
    implementation("com.meta.wearable:mwdat-core:$metaDatVersion")
    implementation("com.meta.wearable:mwdat-camera:$metaDatVersion")
    implementation("com.meta.wearable:mwdat-mockdevice:$metaDatVersion")
}
