package com.cloudx.databridge

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp

/**
 * 🔹 DataBridgeApplication.kt (Production-Ready v2.0)
 * ✅ গ্লোবাল অ্যাপ্লিকেশন কনটেক্সট হ্যান্ডলার
 * ✅ লাইফসাইকল অবজার্ভার ম্যানেজমেন্ট
 * ✅ ফায়ারবেস ইনিশিয়ালাইজেশন চেক
 * ✅ নতুন: DataBridgeService রেফারেন্স হোল্ডার (যেকোনো জায়গা থেকে অ্যাক্সেসের জন্য)
 */
class DataBridgeApplication : Application() {

    private var lifecycleObserver: AppLifecycleObserver? = null
    private val TAG = "DataBridgeApp"

    // ✅ নতুন: DataBridgeService রেফারেন্স (বাইরে থেকে শুধু রিড, রাইট প্রাইভেট)
    var dataBridgeService: DataBridgeService? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ ১. ফায়ারবেস ইনিশিয়ালাইজেশন চেক (অটো হওয়ার কথা, তবুও সেফটি)
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "✅ Firebase initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase init failed: ${e.message}")
        }

        // ✅ ২. গ্লোবাল লাইফসাইকল অবজার্ভার রেজিস্টার
        lifecycleObserver = AppLifecycleObserver(this)
        lifecycleObserver?.register()
        Log.d(TAG, "🔹 AppLifecycleObserver registered")
    }

    override fun onTerminate() {
        // ⚠️ নোট: এই মেথডটি প্রোডাকশন ডিভাইসে কল হয় না, শুধু এমুলেটরে কাজ করে।
        // তাই গুরুত্বপূর্ণ ক্লিনআপের জন্য এর ওপর নির্ভর করবেন না।
        super.onTerminate()
        lifecycleObserver?.unregister()
        lifecycleObserver = null
        // ✅ সার্ভিস রেফারেন্সও ক্লিয়ার করে দিন (মেমোরি লিক প্রতিরোধ)
        dataBridgeService = null
        Log.d(TAG, "🔹 AppLifecycleObserver unregistered (Emulator only)")
    }

    // ✅ নতুন: সার্ভিস রেফারেন্স সেট/ক্লিয়ার করার পাবলিক মেথড
    fun setDataBridgeService(service: DataBridgeService?) {
        dataBridgeService = service
        Log.d(TAG, "🔹 DataBridgeService reference ${if (service != null) "set" else "cleared"}")
    }
}
