package com.cloudx.databridge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update via GitHub Releases (sideload channel — Play's In-App Updates
 * API doesn't apply off-Play).
 *
 * Release convention (repo: alif13579/databridge-app):
 * - Tag `v<versionName>` (e.g. `v6.10.104`), published (not draft/prerelease)
 * - One `.apk` asset attached (signed release build)
 * - Body contains a `versionCode=441` line (preferred compare key; falls
 *   back to tag-name compare when missing)
 *
 * Flow: silent check on launch (max once/24h) + manual check from Settings.
 * Newer release → dialog with notes → Update downloads the APK into the
 * app's cache (FileProvider-covered) → system installer. Android 8+ needs a
 * one-time "install unknown apps" grant, handled inline.
 */
object AppUpdateManager {

    private const val API_URL = "https://api.github.com/repos/alif13579/databridge-app/releases/latest"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val versionCode: Int, // 0 = unknown → compare by name
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentCode(ctx: Context): Int = try {
        val pm = ctx.packageManager
        val pInfo = pm.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pInfo.versionCode
    } catch (_: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) { "" }

    /** Latest published release with an APK asset, or null (offline / none / parse fail). */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                parseRelease(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (_: Exception) { null }
    }

    private fun parseRelease(o: JSONObject): UpdateInfo? {
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val assets = o.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size")
                break
            }
        }
        if (apkUrl.isBlank()) return null
        val tag = o.optString("tag_name").trim()
        val rawNotes = o.optString("body").trim()
        val code = Regex("""versionCode\s*[:=]\s*(\d+)""").find(rawNotes)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""[+-](\d+)$""").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0
        val name = tag.removePrefix("v").removePrefix("V").trim()
        if (name.isBlank()) return null
        val notes = rawNotes.lines()
            .filterNot { it.contains("versionCode", ignoreCase = true) }
            .joinToString("\n").trim()
        return UpdateInfo(tag, name, code, apkUrl, apkSize, notes)
    }

    fun isNewer(ctx: Context, info: UpdateInfo): Boolean {
        val curCode = currentCode(ctx)
        if (info.versionCode > 0 && curCode > 0) return info.versionCode > curCode
        return compareVersionNames(info.versionName, currentName(ctx)) > 0
    }

    private fun compareVersionNames(a: String, b: String): Int {
        val pa = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /** Silent launch check: max once per 24h, dialog only when an update exists. */
    fun silentCheck(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            try {
                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@launch
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val info = fetchLatest() ?: return@launch
                if (!activity.isFinishing && isNewer(activity, info)) showUpdateDialog(activity, info)
            } catch (_: Exception) { }
        }
    }

    fun showUpdateDialog(activity: FragmentActivity, info: UpdateInfo) {
        if (activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        val pad = (density * 16).toInt()
        val sizeTxt = if (info.apkSize > 0) " • %.1f MB".format(info.apkSize / 1048576.0) else ""
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(activity).apply { addView(box) }
        box.addView(TextView(activity).apply {
            text = if (info.notes.isNotBlank()) info.notes else "Bug fixes and improvements."
            textSize = 13f
            setTextColor(activity.getColor(R.color.theme_text_primary))
            maxLines = 10
            setPadding(0, 0, 0, pad / 2)
        })
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            setPadding(0, pad / 3, 0, 0)
        }
        box.addView(progress)
        val tvPct = TextView(activity).apply {
            textSize = 12f
            setTextColor(activity.getColor(R.color.theme_text_secondary))
            visibility = View.GONE
        }
        box.addView(tvPct)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("🎉 New version ${info.versionName}$sizeTxt")
            .setView(scroll)
            .setPositiveButton("⬇ Update", null)
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { btn ->
            if (!ensureInstallPermission(activity)) return@setOnClickListener
            btn.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
            progress.visibility = View.VISIBLE
            tvPct.visibility = View.VISIBLE
            tvPct.text = "⬇ Downloading… 0%"
            activity.lifecycleScope.launch {
                var lastPct = -1
                val file = downloadApk(activity, info) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            progress.progress = pct
                            tvPct.text = "⬇ Downloading… $pct%"
                        }
                    }
                }
                if (file != null && !activity.isFinishing) {
                    dialog.dismiss()
                    installApk(activity, file)
                } else if (!activity.isFinishing) {
                    tvPct.text = "⚠ Download failed — check net and try again"
                    btn.isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                }
            }
        }
    }

    private suspend fun downloadApk(
        ctx: Context, info: UpdateInfo, onProgress: (done: Long, total: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "exports/updates").apply { mkdirs() }
            val file = File(dir, "databridge-${info.versionName}.apk")
            if (file.exists()) file.delete()
            val req = Request.Builder().url(info.apkUrl).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    val d = done
                                    withContext(Dispatchers.Main) { onProgress(d, total) }
                                }
                            }
                        }
                        out.flush()
                        if (total <= 0) withContext(Dispatchers.Main) { onProgress(done, total) }
                    }
                }
            }
            file
        } catch (_: Exception) { null }
    }

    private fun installApk(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Android 8+ one-time "install unknown apps" grant. False = settings opened, tap Update again after allowing. */
    private fun ensureInstallPermission(activity: FragmentActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (activity.packageManager.canRequestPackageInstalls()) return true
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            Toast.makeText(activity, "Allow installs, then tap Update again", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { }
        return false
    }
}
