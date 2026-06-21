package com.cloudx.databridge

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 *  SplashActivity.kt
 * অ্যাপ ওপেন হওয়ার প্রথম স্ক্রিন। .৫ সেকেন্ড লোগো/লোডিং দেখায়।
 * পরবর্তী ধাপে এখানে AppPreferences দিয়ে UID/Login স্টেট চেক করে
 * ডাইরেক্ট Connected স্ক্রিন বা History স্ক্রিনে নেভিগেট করা যাবে।
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Coroutine লাইফসাইল-সেফ ভাবে ডিলে ও নেভিগেশন হ্যান্ডেল করে
        lifecycleScope.launch {
            delay(1500) // ১.৫ সেকেন্ড স্প্ল্যাশ দেখানো

            // ভবিষ্যতের রুটিং লজিক (আপাতত সরাসরি MainActivity-তে যাবে)
            // val appPrefs = AppPreferences(this@SplashActivity)
            // val savedUid = appPrefs.currentUidFlow.first()
            // if (savedUid != null) { /* ডাইরেক্ট কানেক্টেড স্ক্রিন */ }
            // else { /* Connect স্ক্রিন */ }

            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish() // স্প্ল্যাশ স্ক্রিন মেমোরি থেকে রিমুভ (Back press এ ফিরে আসবে না)
        }
    }
}