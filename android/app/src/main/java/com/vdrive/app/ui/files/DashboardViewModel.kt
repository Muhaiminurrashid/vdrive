package com.vdrive.app.ui.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.vdrive.app.data.repository.FileRepository
import com.vdrive.app.domain.model.Folder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import javax.inject.Inject

enum class ViewMode { List, Grid }

data class FileUiItem(
    val id: String,
    val name: String,
    val typeLabel: String,
    val sizeBytes: Long = 0L,
    val folderId: String? = null,
    val folderName: String? = null,
    val mimeType: String = "application/octet-stream",
    val b2FileId: String? = null,
    val b2FileName: String? = null,
    val createdAt: Long = 0L
)

data class DashboardUiState(
    val files: List<FileUiItem> = emptyList(),
    val fileMap: Map<String, FileUiItem> = emptyMap(),
    val fileCount: Int = 0,
    val storagePercent: Float = 0f,
    val totalStorageBytes: Long = 0L,
    val folders: List<Folder> = emptyList(),
    val subFolders: List<Folder> = emptyList(),
    val currentFolderId: String? = null,
    val folderPath: List<Folder> = emptyList(),
    val isLoading: Boolean = false,
    val hasMoreFiles: Boolean = false,
    val userEmail: String = "",
    val generatedCode: String? = null,
    val codeExpiryLabel: String = "1 hour",
    val selectedIds: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.List,
    val error: String? = null,
    val uploadProgress: Float? = null,
    val actionLabel: String? = null,
    val isPremium: Boolean = false,
)

// ponytail: set after deploying Worker
private const val B2_PROXY_URL = "https://b2-proxy.muhaiminurrashid99.workers.dev"

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private val pageSize = 50
    private var fileCursor: DocumentSnapshot? = null

    init {
        auth.currentUser?.let { user ->
            _state.value = _state.value.copy(userEmail = user.email ?: "")
            loadFolders()
        }
        loadStorageBar()
    }

    // ponytail: paginated file query per folder, folders from cache
    fun loadContents(isLoadMore: Boolean = false) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            if (!isLoadMore) {
                _state.value = _state.value.copy(isLoading = true)
                fileCursor = null
            }
            try {
                val folderId = _state.value.currentFolderId
                var query = firestore.collection("files")
                    .whereEqualTo("userId", user.uid)
                    .whereEqualTo("folderId", folderId)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(pageSize.toLong())
                if (fileCursor != null) query = query.startAfter(fileCursor!!)

                val snap = query.get().await()
                fileCursor = snap.documents.lastOrNull()
                val hasMore = snap.documents.size == pageSize

                val folderNames = _state.value.folders.associate { it.id to it.name }
                val items = snap.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val fId = data["folderId"] as? String
                    FileUiItem(
                        id = doc.id,
                        name = data["name"] as? String ?: "",
                        typeLabel = getFileType(data["name"] as? String ?: ""),
                        sizeBytes = (data["size"] as? Number)?.toLong() ?: 0L,
                        folderId = fId,
                        folderName = fId?.let { folderNames[it] },
                        mimeType = data["type"] as? String ?: "application/octet-stream",
                        b2FileId = data["b2FileId"] as? String,
                        b2FileName = data["b2FileName"] as? String,
                        createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.seconds?.times(1000) ?: 0L
                    )
                }

                val subFolders = _state.value.folders
                    .filter { (it.parentId ?: "") == (folderId ?: "") }
                    .map { it }

                val allItems = if (isLoadMore) _state.value.files + items else items
                // ponytail: full map survives refresh/page-shrink, mirrors web panelFiles
                val allMap = if (isLoadMore) _state.value.fileMap + items.associateBy { it.id } else items.associateBy { it.id }

                _state.value = _state.value.copy(
                    files = allItems,
                    fileMap = allMap,
                    fileCount = allItems.size,
                    subFolders = subFolders,
                    hasMoreFiles = hasMore,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = userMessage(e))
            }
        }
    }

    fun loadMoreFiles() = loadContents(isLoadMore = true)

    // ponytail: separate query sums all file sizes for storage bar
    fun loadStorageBar() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                val snap = firestore.collection("files")
                    .whereEqualTo("userId", user.uid).get().await()
                var totalBytes = 0L
                snap.documents.forEach { doc ->
                    doc.data?.let { totalBytes += (it["size"] as? Number)?.toLong() ?: 0L }
                }
                val subDoc = firestore.collection("subscriptions").document(user.uid).get().await()
                val expiresAt = subDoc.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
                val isPremium = subDoc.exists() && subDoc.getString("status") == "active" && expiresAt > System.currentTimeMillis()
                val cap = if (isPremium) 10_000_000_000f else 1_000_000_000f
                _state.value = _state.value.copy(
                    storagePercent = (totalBytes / cap).coerceAtMost(1f),
                    totalStorageBytes = totalBytes,
                    isPremium = isPremium
                )
            } catch (_: Exception) { }
        }
    }

    fun loadFolders() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                val snap = firestore.collection("folders")
                    .whereEqualTo("userId", user.uid)
                    .get().await()
                val folders = snap.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    Folder(
                        id = doc.id,
                        name = d["name"] as? String ?: "",
                        parentId = d["parentId"] as? String
                    )
                }
                _state.value = _state.value.copy(folders = folders)
                loadContents()
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun navigateToFolder(folderId: String) {
        val folder = _state.value.folders.find { it.id == folderId } ?: return
        if (_state.value.folderPath.lastOrNull()?.id == folderId) return
        val path = _state.value.folderPath + folder
        _state.value = _state.value.copy(
            currentFolderId = folderId,
            folderPath = path,
            selectedIds = emptySet()
        )
        loadContents()
    }

    fun navigateUp() {
        val path = _state.value.folderPath
        if (path.isEmpty()) return
        val newPath = path.dropLast(1)
        _state.value = _state.value.copy(
            currentFolderId = newPath.lastOrNull()?.id,
            folderPath = newPath,
            selectedIds = emptySet()
        )
        loadContents()
    }

    fun navigateToBreadcrumb(index: Int) {
        val path = _state.value.folderPath
        if (index >= path.size + 1) return
        val newPath = path.take(index)
        _state.value = _state.value.copy(
            currentFolderId = newPath.lastOrNull()?.id,
            folderPath = newPath,
            selectedIds = emptySet()
        )
        loadContents()
    }

    fun createFolder(name: String) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("folders").add(mapOf(
                    "name" to name,
                    "userId" to user.uid,
                    "parentId" to _state.value.currentFolderId,
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()
                loadFolders()
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                firestore.collection("folders").document(folderId).delete().await()
                navigateUp(); loadFolders(); loadStorageBar()
                // ponytail: silent child cleanup, errors don't block navigation
                runCatching {
                    firestore.collection("files").whereEqualTo("folderId", folderId).get().await()
                        .documents.forEach { runCatching { it.reference.update("folderId", null).await() } }
                    firestore.collection("folders").whereEqualTo("parentId", folderId).get().await()
                        .documents.forEach { runCatching { it.reference.update("parentId", null).await() } }
                }
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun renameFile(fileId: String, newName: String) {
        viewModelScope.launch {
            try {
                firestore.collection("files").document(fileId)
                    .update("name", newName).await()
                loadContents()
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun toggleViewMode() {
        _state.value = _state.value.copy(
            viewMode = if (_state.value.viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List,
            selectedIds = emptySet()
        )
    }

    fun downloadSelected(context: Context) {
        val user = auth.currentUser ?: return
        val files = _state.value.selectedIds.mapNotNull { _state.value.fileMap[it] }
        if (files.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(actionLabel = "Downloading ${files.size} files...", uploadProgress = 0f)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("files", JSONArray(files.map {
                            JSONObject().apply {
                                put("id", it.id)
                                put("b2FileId", it.b2FileId ?: "")
                                put("b2FileName", it.b2FileName ?: "")
                                put("name", it.name)
                            }
                        }))
                        put("userId", user.uid)
                    }
                    val conn = URL("$B2_PROXY_URL/api/zip").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(body.toString().toByteArray())
                    if (conn.responseCode >= 300) {
                        val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "{}"
                        val errMsg = JSONObject(errBody).optString("error", "Download failed")
                        throw Exception(errMsg)
                    }
                    conn.inputStream.readBytes()
                }
                val fileName = "files.zip"
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            context.contentResolver.update(it, values, null, null)
                        }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        java.io.File(dir, fileName).writeBytes(bytes)
                    }
                }
                _state.value = _state.value.copy(selectedIds = emptySet())
                withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded to Downloads", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
            } finally {
                _state.value = _state.value.copy(uploadProgress = null, actionLabel = null)
            }
        }
    }

    fun deleteSelected() {
        val user = auth.currentUser ?: return
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(actionLabel = "Deleting...")
            try {
                for (id in ids) {
                    val file = _state.value.fileMap[id] ?: continue
                    try {
                        fileRepository.deleteFile(file.id, file.b2FileId, file.b2FileName)
                    } catch (e: Exception) {
                        // ponytail: per-file continue, one failure shouldn't abort the batch
                        _state.value = _state.value.copy(error = userMessage(e))
                    }
                }
                loadContents(); loadStorageBar()
                _state.value = _state.value.copy(selectedIds = emptySet())
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
                loadContents(); loadStorageBar()
            } finally {
                _state.value = _state.value.copy(actionLabel = null)
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            try {
                firestore.collection("folders").document(folderId)
                    .update("name", newName).await()
                loadFolders()
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun moveFile(file: FileUiItem, targetFolderId: String?) {
        viewModelScope.launch {
            try {
                firestore.collection("files").document(file.id)
                    .update("folderId", targetFolderId).await()
                loadContents()
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun uploadFile(uri: Uri, contentResolver: ContentResolver, folderId: String? = null) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, uploadProgress = 0f, actionLabel = "Uploading...")
            try {
                val subDoc = firestore.collection("subscriptions").document(user.uid).get().await()
                val expiresAt = subDoc.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
                val isPremium = subDoc.exists() && subDoc.getString("status") == "active" && expiresAt > System.currentTimeMillis()
                fileRepository.uploadFile(user.uid, uri, contentResolver, isPremium, folderId) { progress ->
                    _state.value = _state.value.copy(uploadProgress = progress)
                }
                loadContents(); loadStorageBar()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
            } finally {
                _state.value = _state.value.copy(isLoading = false, uploadProgress = null, actionLabel = null)
            }
        }
    }

    fun uploadFiles(uris: List<Uri>, contentResolver: ContentResolver, folderId: String? = null) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val subDoc = firestore.collection("subscriptions").document(user.uid).get().await()
                val expiresAt = subDoc.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
                val isPremium = subDoc.exists() && subDoc.getString("status") == "active" && expiresAt > System.currentTimeMillis()
                // ponytail: fetch B2 credentials once, reuse for all files
                val firstBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uris.first())?.use { it.readBytes() }
                } ?: return@launch
                val (b2UploadUrl, b2AuthToken) = fileRepository.getUploadUrl(user.uid, firstBytes.size)
                for ((i, uri) in uris.withIndex()) {
                    _state.value = _state.value.copy(uploadProgress = 0f, actionLabel = "Uploading file ${i + 1} of ${uris.size}...")
                    fileRepository.uploadFile(user.uid, uri, contentResolver, isPremium, folderId,
                        b2UploadUrl = b2UploadUrl, b2AuthToken = b2AuthToken) { progress ->
                        _state.value = _state.value.copy(uploadProgress = progress)
                    }
                }
                loadContents(); loadStorageBar()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
            } finally {
                _state.value = _state.value.copy(isLoading = false, uploadProgress = null, actionLabel = null)
            }
        }
    }

    fun downloadFile(file: FileUiItem, context: Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(actionLabel = "Downloading ${file.name}...", uploadProgress = 0f)
            try {
                val b2FileName = file.b2FileName ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("fileName", b2FileName)
                        put("userId", auth.currentUser?.uid)
                    }.toString()
                    val conn = URL("$B2_PROXY_URL/api/download").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(body.toByteArray())
                    if (conn.responseCode >= 300) {
                        val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "{}"
                        val errMsg = JSONObject(errBody).optString("error", "Download failed")
                        throw Exception(errMsg)
                    }
                    val total = conn.contentLength
                    val input = conn.inputStream
                    val buffer = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    var read: Int
                    var totalRead = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        totalRead += read
                        if (total > 0) {
                            val pct = (totalRead * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = _state.value.copy(uploadProgress = pct / 100f)
                            }
                        }
                    }
                    input.close()
                    out.toByteArray()
                }
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                            put(MediaStore.Downloads.MIME_TYPE, file.mimeType)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            context.contentResolver.update(it, values, null, null)
                        }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        java.io.File(dir, file.name).writeBytes(bytes)
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
            } finally {
                _state.value = _state.value.copy(uploadProgress = null, actionLabel = null)
            }
        }
    }

    fun previewFile(file: FileUiItem, context: Context) {
        viewModelScope.launch {
            try {
                val b2FileName = file.b2FileName ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("fileName", b2FileName)
                        put("userId", auth.currentUser?.uid)
                    }.toString()
                    val conn = URL("$B2_PROXY_URL/api/download").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(body.toByteArray())
                    if (conn.responseCode >= 300) {
                        val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "{}"
                        val errMsg = JSONObject(errBody).optString("error", "Download failed")
                        throw Exception(errMsg)
                    }
                    conn.inputStream.readBytes()
                }
                val cacheFile = withContext(Dispatchers.IO) {
                    java.io.File(context.cacheDir, file.name).also { it.writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun deleteFile(file: FileUiItem) {
        viewModelScope.launch {
            _state.value = _state.value.copy(actionLabel = "Deleting...")
            try {
                fileRepository.deleteFile(file.id, file.b2FileId, file.b2FileName)
                loadContents(); loadStorageBar()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = userMessage(e))
                loadContents(); loadStorageBar()
            } finally {
                _state.value = _state.value.copy(actionLabel = null)
            }
        }
    }

    fun toggleSelection(id: String) {
        val current = _state.value.selectedIds
        _state.value = _state.value.copy(
            selectedIds = if (id in current) current - id else current + id
        )
    }

    fun generateCode() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val code = (1..6).map { chars.random() }.joinToString("")
                val expiresAt = System.currentTimeMillis() + 60 * 60 * 1000
                val fileIds = _state.value.selectedIds.toList()

                firestore.collection("accessCodes").add(mapOf(
                    "code" to code,
                    "userId" to user.uid,
                    "fileIds" to fileIds,
                    "expiresAt" to expiresAt,
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()

                _state.value = _state.value.copy(generatedCode = code, selectedIds = emptySet(), codeExpiryLabel = "1 hour")
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun generateFolderCode(folderId: String?) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                if (folderId != null) {
                    val existing = firestore.collection("accessCodes")
                        .whereEqualTo("folderId", folderId)
                        .get().await()
                    val active = existing.documents.firstOrNull { doc ->
                        val data = doc.data ?: return@firstOrNull false
                        data["userId"] == user.uid && (data["expiresAt"] as? Long ?: 0L) >= now
                    }
                    if (active != null) {
                        _state.value = _state.value.copy(generatedCode = active.getString("code") ?: "", codeExpiryLabel = "1 hour")
                        return@launch
                    }
                }

                val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val code = (1..6).map { chars.random() }.joinToString("")
                val expiresAt = now + 60 * 60 * 1000

                val data = mutableMapOf<String, Any>(
                    "code" to code, "userId" to user.uid,
                    "expiresAt" to expiresAt, "createdAt" to FieldValue.serverTimestamp()
                )
                if (folderId != null) data["folderId"] = folderId
                firestore.collection("accessCodes").add(data).await()

                _state.value = _state.value.copy(generatedCode = code, codeExpiryLabel = "1 hour")
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun submitSubscription(txId: String) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("subscriptions").document(user.uid).set(mapOf(
                    "plan" to "premium",
                    "status" to "pending",
                    "txId" to txId,
                    "txAmount" to 99,
                    "userId" to user.uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()
                _state.value = _state.value.copy(error = null)
            } catch (e: Exception) { _state.value = _state.value.copy(error = userMessage(e)) }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearGeneratedCode() {
        _state.value = _state.value.copy(generatedCode = null)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    fun signOut() {
        auth.signOut()
    }

    // ponytail: maps exception to user-friendly message
    private fun userMessage(e: Exception): String {
        val msg = e.message ?: return "Something went wrong"
        if (msg.contains("File too large")) return msg
        if (e is FirebaseFirestoreException) {
            return when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Permission denied"
                FirebaseFirestoreException.Code.UNAVAILABLE -> "Service unavailable. Try again."
                FirebaseFirestoreException.Code.NOT_FOUND -> "Not found"
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Please sign in again"
                else -> "Something went wrong"
            }
        }
        if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException)
            return "Network error. Check your connection."
        return "Something went wrong"
    }

    private fun getFileType(name: String): String {
        val map = mapOf(
            "pdf" to "PDF", "pptx" to "PPT", "ppt" to "PPT",
            "docx" to "DOC", "doc" to "DOC", "mp4" to "Video",
            "xlsx" to "XLS", "zip" to "ZIP"
        )
        val ext = name.substringAfterLast('.', "").lowercase()
        return map[ext] ?: ext.uppercase()
    }
}


