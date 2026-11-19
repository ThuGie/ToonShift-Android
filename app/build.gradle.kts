plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "nl.toonshift.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.toonshift.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.3"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (System.getenv("TOONSHIFT_KEYSTORE") != null) create("github") {
            storeFile = file(System.getenv("TOONSHIFT_KEYSTORE"))
            storePassword = System.getenv("TOONSHIFT_KEYSTORE_PASSWORD")
            keyAlias = "toonshift"
            keyPassword = System.getenv("TOONSHIFT_KEYSTORE_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.findByName("github") } }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
