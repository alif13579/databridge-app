package com.cloudx.databridge

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // Supabase client — must init before any Fragment uses it
        SupabaseClientManager.init()

        // ✅ App-এর পুরো UI শুধু light theme ধরে ডিজাইন করা; values-night/themes.xml
        // কখনো real branding-এর সাথে মেলানো হয়নি (এখনো Android Studio-র পুরনো
        // template placeholder রং রয়ে গেছে)। ফলে device system dark mode-এ থাকলে
        // popup/menu/dialog-এ mismatched, প্রায়-অদৃশ্য (dark-on-dark) UI দেখাচ্ছিল
        // -- যেমন Deposit list-এর Channel filter popup। System setting যাই হোক,
        // এখন সবসময় light theme force করা হচ্ছে।
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // ✅ ১. ফায়ারবেস ইনিশিয়ালাইজেশন চেক (অটো হওয়ার কথা, তবুও সেফটি)
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "✅ Firebase initialized")
            // A token is per app-installation and can rotate at any time. Register it
            // after every auth-state change so the Edge Function can target this device.
            // Retried with backoff: a single failed attempt at login (offline, blip)
            // used to leave the device push-blind until the next auth event.
            FirebaseAuth.getInstance().addAuthStateListener { firebaseAuth ->
                if (firebaseAuth.currentUser == null) return@addAuthStateListener
                fetchAndRegisterPushToken(attempt = 0)
            }
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

    /** Fetches the FCM token and registers it (with retry inside). A failed
     *  fetch itself is retried twice — without this, an offline login left
     *  the device push-blind with zero trace beyond the warning below. */
    private fun fetchAndRegisterPushToken(attempt: Int) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                SupabaseRemarkValidationWriter.registerPushTokenWithRetry(token)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "⚠️ FCM token fetch failed (attempt ${attempt + 1}): ${e.message}")
                if (attempt < 2) {
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(if (attempt == 0) 30_000L else 300_000L)
                        if (FirebaseAuth.getInstance().currentUser != null) fetchAndRegisterPushToken(attempt + 1)
                    }
                }
            }
    }
}
