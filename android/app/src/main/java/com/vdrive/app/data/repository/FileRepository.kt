package com.vdrive.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

// ponytail: set B2_PROXY_URL after deploying Worker
private const val B2_PROXY_URL = "https://b2-proxy.muhaiminurrashid99.workers.dev"

@Singleton
class FileRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val client = OkHttpClient()

    suspend fun uploadFile(
        userId: String, uri: Uri, contentResolver: ContentResolver,
        folderId: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
            val name = getFileName(uri, contentResolver) ?: "file_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val bytes = inputStream.readBytes()
            inputStream.close()
            // ponytail: 100 MB limit, B2 has no real cap but keeps free tier sane
            if (bytes.size > 100L * 1024 * 1024) throw Exception("File too large (max 100 MB)")

            // Get B2 upload URL from Worker
            val uploadUrlRes = client.newCall(
                Request.Builder().url("$B2_PROXY_URL/api/upload-url").get().build()
            ).execute()
            val workerData = JSONObject(uploadUrlRes.body!!.string())
            val b2UploadUrl = workerData.getString("uploadUrl")
            val b2AuthToken = workerData.getString("authToken")

            // Upload file directly to B2
            val b2FileName = "${userId}_${System.currentTimeMillis()}_$name"
            // ponytail: custom RequestBody to track upload progress (parallel web XHR upload.onprogress)
            val progressBody = object : okhttp3.RequestBody() {
                override fun contentType() = mimeType.toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) {
                    var written = 0
                    while (written < bytes.size) {
                        val end = minOf(written + 8192, bytes.size)
                        sink.write(bytes, written, end - written)
                        written = end
                        onProgress?.invoke(written.toFloat() / bytes.size)
                    }
                }
            }
            val b2Res = client.newCall(
                Request.Builder().url(b2UploadUrl)
                    .addHeader("Authorization", b2AuthToken)
                    .addHeader("X-Bz-File-Name", URLEncoder.encode(b2FileName, "UTF-8"))
                    .addHeader("Content-Type", mimeType)
                    .addHeader("X-Bz-Content-Sha1", "do_not_verify")
                    .post(progressBody)
                    .build()
            ).execute()
            if (!b2Res.isSuccessful) return@withContext null
            val b2Result = JSONObject(b2Res.body!!.string())

            // Save metadata to Firestore
            val doc = mutableMapOf<String, Any?>(
                "name" to name,
                "size" to bytes.size,
                "type" to mimeType,
                "userId" to userId,
                "b2FileId" to b2Result.getString("fileId"),
                "b2FileName" to b2FileName,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (folderId != null) doc["folderId"] = folderId
            val metaRef = firestore.collection("files").add(doc).await()
            metaRef.id
        }

    suspend fun deleteFile(fileId: String, b2FileId: String?, b2FileName: String?) {
        // ponytail: B2 destroy via Worker, skip if null (pre-migration files)
        if (b2FileId != null && b2FileName != null) {
            withContext(Dispatchers.IO) {
                val body = JSONObject().apply {
                    put("fileId", b2FileId)
                    put("fileName", b2FileName)
                }.toString().toRequestBody("application/json".toMediaType())
                client.newCall(
                    Request.Builder().url("$B2_PROXY_URL/api/delete")
                        .delete(body).build()
                ).execute().close()
            }
        }
        firestore.collection("files").document(fileId).delete().await()
    }

    private fun getFileName(uri: Uri, cr: ContentResolver): String? {
        cr.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment
    }
}
