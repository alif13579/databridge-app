package com.cloudx.databridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Uploads Petty Cash request attachments (receipt photos / PDFs) to Cloudflare
 * R2, going through the `r2-attachment-upload` Supabase Edge Function to get a
 * short-lived presigned URL first.
 *
 * Deliberately does NOT hold any R2 credentials — the Edge Function is the
 * only thing that knows the R2 secret access key (see that function's own
 * doc comment). This object's job is: (1) describe the file to the Edge
 * Function, (2) PUT the file's bytes straight to the URL it returns.
 *
 * A 5 MB / image-or-PDF-only check happens here too, purely so a user gets
 * an immediate, specific error instead of waiting on a network round trip
 * only to be rejected server-side. The Edge Function re-checks both
 * independently and is the real enforcement point — this client-side check
 * is a courtesy, not the security boundary.
 */
object AttachmentUploader {

    const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5 MB — keep in sync with the Edge Function's MAX_FILE_BYTES

    /** MIME types this feature accepts — "all image formats" plus PDF, matching the Edge Function's ALLOWED_CONTENT_TYPES. */
    private val ALLOWED_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp",
        "application/pdf",
    )

    /**
     * MIME filter for the picker launch. ActivityResultContracts.GetContent()
     * only accepts a single MIME string (no comma-joined list of several
     * types), so this uses the "*/*" wildcard and relies on readFileMeta()/
     * upload() to reject anything that isn't actually an image or PDF after
     * the user picks it — the picker showing everything is a minor UX cost,
     * not a validation gap.
     */
    const val PICKER_MIME_TYPE = "*/*"

    sealed class Result {
        data class Success(val publicUrl: String, val objectKey: String, val displayName: String) : Result()
        data class Rejected(val reason: String) : Result() // client-side validation failure — no network call made
        data class Failed(val message: String) : Result()  // network/server failure
    }

    data class FileMeta(val displayName: String, val mimeType: String, val sizeBytes: Long)

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // uploads can be slower than the small JSON calls elsewhere
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonMediaType = "application/json".toMediaType()

    /** Reads display name / MIME type / size for a content:// Uri without opening the whole stream. */
    fun readFileMeta(context: Context, uri: Uri): FileMeta? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: return null
        var displayName = uri.lastPathSegment.orEmpty()
        var sizeBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.let { sizeBytes = cursor.getLong(it) }
            }
        }
        if (sizeBytes < 0) {
            // Some providers don't populate OpenableColumns.SIZE; fall back to opening the
            // stream and measuring it, so a bad actor can't hide an oversized file from the
            // client-side check by omitting the column (the Edge Function still re-checks
            // the real byte count from what actually gets uploaded, regardless).
            sizeBytes = try {
                resolver.openInputStream(uri)?.use { it.readBytes().size.toLong() } ?: -1L
            } catch (_: Exception) { -1L }
        }
        return FileMeta(displayName.ifBlank { "attachment" }, mimeType, sizeBytes)
    }

    /**
     * Full upload flow for one file. Safe to call from any coroutine — internally
     * suspends on the presign network call and the upload PUT, both off the main thread
     * via OkHttp's async callback bridged through suspendCancellableCoroutine.
     */
    suspend fun upload(context: Context, uri: Uri): Result {
        val meta = readFileMeta(context, uri) ?: return Result.Rejected("Couldn't read the selected file")
        if (meta.mimeType !in ALLOWED_MIME_TYPES) return Result.Rejected("Only images or PDF files are allowed")
        if (meta.sizeBytes <= 0) return Result.Rejected("Couldn't determine the file size")
        if (meta.sizeBytes > MAX_FILE_BYTES) {
            return Result.Rejected("File exceeds the ${MAX_FILE_BYTES / (1024 * 1024)}MB limit")
        }

        if (!SupabaseConfig.isConfigured) return Result.Failed("Upload isn't configured yet")
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.Failed("Not signed in")
        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Couldn't verify your sign-in — try again")

        val presignResponse = try {
            requestPresignedUrl(token, meta)
        } catch (e: Exception) {
            log("presign_error", e.message ?: "Unknown error", meta)
            return Result.Failed("Couldn't prepare the upload — check your connection and try again")
        }

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Couldn't read the selected file")

        return try {
            putFile(presignResponse.uploadUrl, bytes, meta.mimeType)
            Result.Success(presignResponse.publicUrl, presignResponse.objectKey, meta.displayName)
        } catch (e: Exception) {
            log("put_error", e.message ?: "Unknown error", meta)
            Result.Failed("Upload failed partway through — try again")
        }
    }

    private data class PresignResponse(val uploadUrl: String, val objectKey: String, val publicUrl: String)

    private suspend fun requestPresignedUrl(firebaseToken: String, meta: FileMeta): PresignResponse =
        suspendCancellableCoroutine { continuation ->
            val payload = JSONObject()
                .put("file_name", meta.displayName)
                .put("content_type", meta.mimeType)
                .put("size_bytes", meta.sizeBytes)
            val request = Request.Builder()
                .url("${SupabaseConfig.PROJECT_URL}/functions/v1/r2-attachment-upload")
                .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $firebaseToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(IOException("HTTP ${it.code}: ${text.take(300)}")))
                            }
                            return
                        }
                        try {
                            val json = JSONObject(text)
                            val parsed = PresignResponse(
                                uploadUrl = json.getString("upload_url"),
                                objectKey = json.getString("object_key"),
                                publicUrl = json.optString("public_url", ""),
                            )
                            if (continuation.isActive) continuation.resume(parsed)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            })
        }

    private suspend fun putFile(uploadUrl: String, bytes: ByteArray, mimeType: String): Unit =
        suspendCancellableCoroutine { continuation ->
            val body = bytes.toRequestBody(mimeType.toMediaType())
            val request = Request.Builder().url(uploadUrl).put(body).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (it.isSuccessful) {
                            if (continuation.isActive) continuation.resume(Unit)
                        } else if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(IOException("Upload HTTP ${it.code}")))
                        }
                    }
                }
            })
        }

    private fun log(action: String, error: String, meta: FileMeta) = FirebaseErrorLogger.log(
        screen = "PettyCashAttachment", action = action, errorMessage = error,
        extra = mapOf("fileName" to meta.displayName, "mimeType" to meta.mimeType, "sizeBytes" to meta.sizeBytes)
    )
}
