package com.cloudx.databridge

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uploads finished call-recording files (.m4a) to R2 through the same
 * r2-attachment-upload Edge Function the claim images use — audio gets its
 * own key prefix (call_recordings/) and a 15 MB cap server-side. No
 * compression/transcode: AAC-in-MP4 is already compact (~1 MB/min), and
 * re-encoding would only risk intelligibility.
 *
 * Playback reuses [AttachmentUploader.getDownloadUrl] (its key guard now
 * accepts both families) + [CallRecordingPlayer].
 */
object CallRecordingUploader {

    const val MAX_FILE_BYTES = 10L * 1024 * 1024 // keep in sync with the Edge Function's MAX_AUDIO_BYTES

    sealed class Result {
        data class Success(val objectKey: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS) // 15 MB on a slow uplink needs room
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonMedia = "application/json".toMediaType()

    suspend fun upload(
        file: File,
        displayName: String,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        if (!file.exists() || file.length() <= 0) return Result.Failed("Recording file missing")
        if (file.length() > MAX_FILE_BYTES) return Result.Failed("Audio exceeds the 10MB limit")
        if (!SupabaseConfig.isConfigured) return Result.Failed("Upload isn't configured yet")
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.Failed("Not signed in")
        val token = try { user.getIdToken(false).await().token } catch (_: Exception) { null }
            ?: return Result.Failed("Couldn't verify your sign-in — try again")

        val presigned = try {
            val payload = JSONObject()
                .put("action", "upload")
                .put("file_name", displayName.ifBlank { file.name })
                .put("content_type", CallRecordingManager.MIME_TYPE)
                .put("size_bytes", file.length())
            val req = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/r2-attachment-upload")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia)).build()
            suspendCancellableCoroutine<Pair<String, String>> { cont ->
                val call = client.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        response.use {
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                if (cont.isActive) cont.resumeWithException(IOException("HTTP ${it.code}: ${text.take(200)}"))
                                return
                            }
                            try {
                                val j = JSONObject(text)
                                if (cont.isActive) cont.resume(
                                    j.getString("upload_url") to j.getString("object_key"))
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "upload_presign_error", e.message ?: "presign failed")
            return Result.Failed("Couldn't prepare the upload — check connection and try again")
        }

        return try {
            putFile(presigned.first, file, onProgress)
            Result.Success(presigned.second)
        } catch (e: Exception) {
            FirebaseErrorLogger.log("CallRecording", "upload_put_error", e.message ?: "PUT failed")
            Result.Failed("Upload failed partway — try again")
        }
    }

    private suspend fun putFile(url: String, file: File, onProgress: ((Int) -> Unit)?): Unit =
        suspendCancellableCoroutine { cont ->
            val body = if (onProgress == null) {
                file.readBytes().toRequestBody(CallRecordingManager.MIME_TYPE.toMediaType())
            } else {
                object : okhttp3.RequestBody() {
                    override fun contentType() = CallRecordingManager.MIME_TYPE.toMediaType()
                    override fun contentLength() = file.length()
                    override fun writeTo(sink: okio.BufferedSink) {
                        file.inputStream().use { ins ->
                            val buf = ByteArray(64 * 1024)
                            var sent = 0L
                            val total = file.length()
                            var lastPct = -1
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                sink.write(buf, 0, n)
                                sent += n
                                val pct = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 100
                                if (pct != lastPct) {
                                    lastPct = pct
                                    try { onProgress(pct) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
            val call = client.newCall(Request.Builder().url(url).put(body).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (it.isSuccessful) { if (cont.isActive) cont.resume(Unit) }
                        else if (cont.isActive) cont.resumeWithException(IOException("Upload HTTP ${it.code}"))
                    }
                }
            })
        }
}
