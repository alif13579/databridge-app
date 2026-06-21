plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services") // Firebase
    id("org.jetbrains.kotlin.kapt")      // Room Database-এর জন্য
}

android {
    namespace = "com.cloudx.databridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudx.databridge"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // স্ট্যান্ডার্ড অ্যান্ড্রয়েড লাইব্রেরি
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // ✅ SwipeRefreshLayout সাপোর্টের জন্য
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("com.google.code.gson:gson:2.10.1")

    // ✅ Google Sign-In (Play Services Auth)
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // ফায়ারবেস
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")

    // রুম ডেটাবেস (Room DB)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // ডাটা স্টোর (DataStore)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ভিউ মডেল এবং লাইফসাইকল
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // ✅ গ্লোবাল লাইফসাইকেল ট্র্যাকিংয়ের জন্য (ProcessLifecycleOwner)
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.2")

    // কোরুটিন্স (Coroutines)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // রিসাইক্লার ভিউ (RecyclerView)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // কিউআর স্ক্যানার
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ইউজার অ্যাভাটার লোডিং
    implementation("io.coil-kt:coil:2.5.0")

    // টেস্টিং
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}