package com.cloudx.databridge

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Uploads Petty Cash request attachments (receipt photos / PDFs) to Cloudflare
 * R2, going through the `r2-attachment-upload` Supabase Edge Function to get a
 * short-lived presigned URL first — and, once a request has one, hands back a
 * fresh presigned URL to view/download it too, since the bucket is private
 * and there's no standing public URL for anything in it.
 *
 * Deliberately does NOT hold any R2 credentials — the Edge Function is the
 * only thing that knows the R2 secret access key (see that function's own
 * doc comment). For an upload, this object's job is: (1) describe the file
 * to the Edge Function, (2) PUT the file's bytes straight to the URL it
 * returns. For a download, it's simpler: hand the Edge Function the object
 * key already stored on the request, get back a presigned GET URL. Neither
 * direction ever needs the R2 secret key itself.
 *
 * A 5 MB / image-or-PDF-only check happens here too, purely so a user gets
 * an immediate, specific error instead of waiting on a network round trip
 * only to be rejected server-side. The Edge Function re-checks both
 * independently and is the real enforcement point — this client-side check
 * is a courtesy, not the security boundary.
 *
 * Any picked image over COMPRESS_TRIGGER_BYTES (1 MB) is compressed rather
 * than uploaded as-is — phone camera photos routinely land in the 3-8 MB
 * range, well past what a receipt photo actually needs. Compression stays
 * within JPEG quality 90-85, the well-established "sweet spot" where file
 * size drops sharply (40-60%) with no visible loss versus the original —
 * dimensions are only ever touched as a last resort, for a source so
 * high-resolution that quality 85 alone still doesn't fit under
 * MAX_FILE_BYTES (5 MB, the actual upload ceiling; see compressImageIfNeeded
 * for exactly where that fallback kicks in). PDFs are never compressed —
 * there's no cheap, reliable way to shrink an arbitrary PDF without a
 * dedicated library, and receipts as PDFs are rarely huge to begin with.
 * Compression only ever runs on a *copy* of the bytes in memory, and the
 * compressed result is only adopted if it's actually smaller than the
 * original — some sources (flat-color PNGs, screenshots) can end up
 * *larger* after a JPEG re-encode, in which case the original is kept as-is.
 * If compression fails for any reason (a format Bitmap can't decode, most
 * notably HEIC/HEIF on pre-Android-10 devices — see compressImageIfNeeded's
 * own comment) the original bytes are used as-is too, and the existing
 * MAX_FILE_BYTES check applies unchanged — so compression can only make
 * more images uploadable, never break a case that worked before.
 */
object AttachmentUploader {

    const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5 MB — the actual upload ceiling, keep in sync with the Edge Function's MAX_FILE_BYTES
    private const val COMPRESS_TRIGGER_BYTES = 1L * 1024 * 1024 // 1 MB — compression kicks in above this, well below MAX_FILE_BYTES

    /** Of ALLOWED_MIME_TYPES, the subset eligible for compression — i.e. everything except PDF. */
    private val COMPRESSIBLE_IMAGE_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp",
    )
    // JPEG quality 85-90 is the documented "sweet spot": near-original visual
    // quality (most viewers can't distinguish it from quality 100 on a normal
    // screen) while cutting 40-60% of the file size. Compression starts at 90
    // and steps down only as far as 85 by default — never further, so a
    // receipt photo never gets visibly worse just to save a few more KB.
    private const val COMPRESS_QUALITY_START = 90
    private const val COMPRESS_QUALITY_FLOOR = 85
    // Only reached if quality 85 alone still doesn't fit under MAX_FILE_BYTES
    // (an extremely high-resolution source) — dimension shrinking is the
    // fallback, not the default, and this floor allows a more aggressive
    // quality drop at that point since fitting under the upload ceiling at
    // all takes priority over staying at the gentle range once it's clear
    // quality alone won't get there.
    private const val COMPRESS_QUALITY_FLOOR_FALLBACK = 40
    private const val COMPRESS_MIN_DIMENSION_PX = 800 // don't shrink a receipt below readable size

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
        data class Success(val objectKey: String, val displayName: String) : Result()
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

        var bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Couldn't read the selected file")
        var mimeType = meta.mimeType
        var displayName = meta.displayName

        if (bytes.size > COMPRESS_TRIGGER_BYTES && mimeType in COMPRESSIBLE_IMAGE_MIME_TYPES) {
            // compressImageIfNeeded runs the Bitmap decode/encode off the calling
            // dispatcher (it's CPU-bound work, not something to do on whatever
            // thread the caller happens to be on).
            val compressed = compressImageIfNeeded(bytes)
            if (compressed != null && compressed.size < bytes.size) {
                // Guard against formats where JPEG re-encoding can end up
                // *larger* than the source (flat-color PNGs, screenshots) —
                // only adopt the compressed result if it actually shrank
                // the file; otherwise keep uploading the original as-is.
                bytes = compressed
                mimeType = "image/jpeg" // Bitmap.compress(JPEG, ...) always re-encodes to JPEG regardless of the source format
                // Keep the user-facing name recognizable but reflect the real
                // encoding, since the object stored in R2 is now actually a JPEG
                // (matters if this name is ever shown as a download filename).
                displayName = displayName.substringBeforeLast('.', displayName) + ".jpg"
            }
            // If compression failed (e.g. Bitmap couldn't decode this format —
            // most notably HEIC/HEIF on a pre-Android-10 device) or didn't
            // actually help, bytes/mimeType are left as the original — falls
            // through to the same size check as before compression existed,
            // so this can only add cases that now succeed, never remove one
            // that used to.
        }

        // Re-check size against whatever we're actually about to upload — either
        // the original bytes (never touched, or compression wasn't applicable/
        // didn't help) or the compressed replacement.
        if (bytes.size > MAX_FILE_BYTES) {
            return Result.Rejected("File exceeds the ${MAX_FILE_BYTES / (1024 * 1024)}MB limit")
        }
        val uploadMeta = meta.copy(displayName = displayName, mimeType = mimeType, sizeBytes = bytes.size.toLong())

        if (!SupabaseConfig.isConfigured) return Result.Failed("Upload isn't configured yet")
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.Failed("Not signed in")
        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("Couldn't verify your sign-in — try again")

        val presignResponse = try {
            requestPresignedUrl(token, uploadMeta)
        } catch (e: Exception) {
            log("presign_error", e.message ?: "Unknown error", uploadMeta)
            return Result.Failed("Couldn't prepare the upload — check your connection and try again")
        }

        return try {
            putFile(presignResponse.uploadUrl, bytes, uploadMeta.mimeType)
            Result.Success(presignResponse.objectKey, uploadMeta.displayName)
        } catch (e: Exception) {
            log("put_error", e.message ?: "Unknown error", uploadMeta)
            Result.Failed("Upload failed partway through — try again")
        }
    }

    sealed class DownloadResult {
        data class Success(val downloadUrl: String) : DownloadResult()
        data class Failed(val message: String) : DownloadResult()
    }

    /**
     * Requests a short-lived presigned GET URL for an already-uploaded
     * attachment, identified by the object key stored on the request
     * (PettyCashRequest.attachmentUrl — despite the field's name, this is
     * an R2 object key, not a URL, since the bucket is private and there is
     * no standing public URL for it). The returned download_url is only
     * valid for a few minutes — callers should request a fresh one each
     * time the user wants to open the attachment, not cache it.
     */
    suspend fun getDownloadUrl(objectKey: String): DownloadResult {
        if (objectKey.isBlank()) return DownloadResult.Failed("No attachment on this request")
        if (!SupabaseConfig.isConfigured) return DownloadResult.Failed("Attachment viewing isn't configured yet")
        val user = FirebaseAuth.getInstance().currentUser ?: return DownloadResult.Failed("Not signed in")
        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            null
        } ?: return DownloadResult.Failed("Couldn't verify your sign-in — try again")

        return try {
            val url = requestPresignedDownloadUrl(token, objectKey)
            DownloadResult.Success(url)
        } catch (e: Exception) {
            FirebaseErrorLogger.log(
                screen = "PettyCashAttachment", action = "download_presign_error",
                errorMessage = e.message ?: "Unknown error", extra = mapOf("objectKey" to objectKey),
            )
            DownloadResult.Failed("Couldn't open the attachment — check your connection and try again")
        }
    }

    private suspend fun requestPresignedDownloadUrl(firebaseToken: String, objectKey: String): String =
        suspendCancellableCoroutine { continuation ->
            val payload = JSONObject().put("action", "download").put("object_key", objectKey)
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
                            val downloadUrl = JSONObject(text).getString("download_url")
                            if (continuation.isActive) continuation.resume(downloadUrl)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            })
        }

    /**
     * Tries to bring [original] under MAX_FILE_BYTES by re-encoding as JPEG.
     * Returns null (never throws) if the image can't be decoded at all — the
     * caller falls back to uploading the original bytes untouched.
     *
     * Two tiers, gentle first:
     *   Pass 1 — quality only, from COMPRESS_QUALITY_START (90) down to
     *   COMPRESS_QUALITY_FLOOR (85), original dimensions untouched. This is
     *   the documented "sweet spot" range: file size drops sharply with no
     *   visible loss. This is the expected path for ordinary phone-camera
     *   photos and is where compression stops as soon as it fits.
     *   Pass 2 — only reached if quality 85 alone still doesn't fit under
     *   MAX_FILE_BYTES (an unusually high-resolution source). Shrinks
     *   dimensions (keeping aspect ratio) and allows quality down to
     *   COMPRESS_QUALITY_FLOOR_FALLBACK (40), since at this point getting
     *   under the actual upload ceiling takes priority over staying in the
     *   gentle range.
     *
     * Decoding can fail for reasons that have nothing to do with a corrupt
     * file: HEIC/HEIF only decodes via BitmapFactory on Android 10+ (see the
     * class doc comment), so on an older device this is expected to return
     * null for a HEIC photo, not a bug to chase.
     *
     * Runs on Dispatchers.Default since Bitmap decode/compress is CPU-bound,
     * not I/O — keeps this off whatever dispatcher the caller is on.
     */
    private suspend fun compressImageIfNeeded(original: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        val bitmap = try {
            BitmapFactory.decodeByteArray(original, 0, original.size)
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        try {
            // Pass 1: gentle quality-only reduction at the original
            // dimensions, staying within the 90-85 sweet spot.
            var quality = COMPRESS_QUALITY_START
            var encoded = encodeJpeg(bitmap, quality)
            while (encoded.size > MAX_FILE_BYTES && quality > COMPRESS_QUALITY_FLOOR) {
                quality -= 5
                encoded = encodeJpeg(bitmap, quality)
            }
            if (encoded.size <= MAX_FILE_BYTES) return@withContext encoded

            // Pass 2: the gentle range wasn't enough (very high resolution
            // source) — shrink dimensions too, keeping aspect ratio, and
            // allow a more aggressive quality drop at each smaller size.
            var width = bitmap.width
            var height = bitmap.height
            var scaled = bitmap
            while (encoded.size > MAX_FILE_BYTES &&
                width > COMPRESS_MIN_DIMENSION_PX && height > COMPRESS_MIN_DIMENSION_PX
            ) {
                width = (width * 0.75f).toInt()
                height = (height * 0.75f).toInt()
                val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (scaled !== bitmap) scaled.recycle() // drop the previous intermediate scaled copy, not the original
                scaled = resized
                quality = COMPRESS_QUALITY_START
                encoded = encodeJpeg(scaled, quality)
                while (encoded.size > MAX_FILE_BYTES && quality > COMPRESS_QUALITY_FLOOR_FALLBACK) {
                    quality -= 10
                    encoded = encodeJpeg(scaled, quality)
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            // Whatever we ended up with — under the limit or not, the caller's
            // own size check after this function returns is what actually
            // decides pass/reject either way.
            encoded
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

    private data class PresignResponse(val uploadUrl: String, val objectKey: String)

    private suspend fun requestPresignedUrl(firebaseToken: String, meta: FileMeta): PresignResponse =
        suspendCancellableCoroutine { continuation ->
            val payload = JSONObject()
                .put("action", "upload")
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
