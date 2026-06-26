import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services") // Firebase
    id("org.jetbrains.kotlin.kapt")      // Room Database-এর জন্য
}

// 🔐 Keystore Properties (keystore.properties থেকে পড়া)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}
// ✅ release keystore আছে কিনা দেখা (storeFile নেই) — না থাকলে debug signing এ fallback
val hasReleaseKeystore = keystorePropertiesFile.exists() &&
    (keystoreProperties["storeFile"] as? String)?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.cloudx.databridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudx.databridge"
        minSdk = 23           // ✅ Android 6.0+ → ~99% devices covered (firebase-auth requires 23+)
        targetSdk = 34
        versionCode = 11
        versionName = "2.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true                                         // ✅ Large app support
        vectorDrawables.useSupportLibrary = true       // ✅ Vector drawable on API 21+
    }

    // 🔐 Release Signing Config (only configured if keystore is present)
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as? String ?: ""
                keyAlias = keystoreProperties["keyAlias"] as? String ?: ""
                keyPassword = keystoreProperties["keyPassword"] as? String ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ✅ keystore থাকলে release signing, না থাকলে debug — APK সবসময় signed ও installable
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // ✅ MultiDex support
    implementation("androidx.multidex:multidex:2.0.1")

    // স্ট্যান্ডার্ড অ্যান্ড্রয়েড লাইব্রেরি
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // ✅ SwipeRefreshLayout সাপোর্ট করার জন্য
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("com.google.code.gson:gson:2.10.1")

    // ✅ Google Sign-In (Play Services Auth) — used for Sheets/Drive OAuth account picker
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // ✅ OkHttp — Google Drive + Sheets REST API calls (ConfigSheetFragment)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ✅ Coroutines play-services — for Tasks.await() in Firebase calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ফায়ারবেস লাইব্রেরি
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")

    // রুম ডেটাবেস লাইব্রেরি (Room DB)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // পছন্দ স্টোরেজ (DataStore)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // লাইফ সাইকেল ম্যানেজ লাইব্রেরি
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // ✅ লাইফসাইকেল লাইব্রেরি ব্যাকগ্রাউন্ড ট্র্যাকিং করার জন্য (ProcessLifecycleOwner)
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.2")

    // করুটিন্স ম্যানেজ (Coroutines)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // রিসাইক্লার ভিউ ম্যানেজ (RecyclerView)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // কিউআর স্ক্যানিং লাইব্রেরি
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ইমেজ লোডিং লাইব্রেরি
    implementation("io.coil-kt:coil:2.5.0")

    // টেস্টিং
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
