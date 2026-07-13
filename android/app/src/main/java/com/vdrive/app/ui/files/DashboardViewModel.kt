package com.vdrive.app.ui.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.vdrive.app.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject

data class FileUiItem(
    val id: String,
    val name: String,
    val typeLabel: String,
    val sizeBytes: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val b2FileId: String? = null,
    val b2FileName: String? = null
)

data class DashboardUiState(
    val files: List<FileUiItem> = emptyList(),
    val fileCount: Int = 0,
    val storagePercent: Float = 0f,
    val isLoading: Boolean = false,
    val userEmail: String = "",
    val generatedCode: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val error: String? = null,
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

    init {
        auth.currentUser?.let { user ->
            _state.value = _state.value.copy(userEmail = user.email ?: "")
            loadFiles()
        }
    }

    fun loadFiles() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val snap = firestore.collection("files")
                    .whereEqualTo("userId", user.uid)
                    .get()
                    .await()

                var totalBytes = 0L
                val items = snap.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    totalBytes += (data["size"] as? Long) ?: 0L
                    FileUiItem(
                        id = doc.id,
                        name = data["name"] as? String ?: "",
                        typeLabel = getFileType(data["name"] as? String ?: ""),
                        sizeBytes = (data["size"] as? Long) ?: 0L,
                        mimeType = data["type"] as? String ?: "application/octet-stream",
                        b2FileId = data["b2FileId"] as? String,
                        b2FileName = data["b2FileName"] as? String
                    )
                }

                _state.value = _state.value.copy(
                    files = items,
                    fileCount = items.size,
                    storagePercent = (totalBytes / 1_000_000_000f).coerceAtMost(1f),
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun uploadFile(uri: Uri, contentResolver: ContentResolver) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                fileRepository.uploadFile(user.uid, uri, contentResolver)
                loadFiles()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun downloadFile(file: FileUiItem, context: Context) {
        viewModelScope.launch {
            try {
                val b2FileName = file.b2FileName ?: return@launch
                val signedUrlRes = withContext(Dispatchers.IO) {
                    URL("$B2_PROXY_URL/api/download-url?fileName=${java.net.URLEncoder.encode(b2FileName, "UTF-8")}")
                        .readText()
                }
                val url = JSONObject(signedUrlRes).getString("url")
                val bytes = withContext(Dispatchers.IO) { URL(url).openStream().readBytes() }
                val cacheFile = java.io.File(context.cacheDir, file.name)
                cacheFile.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun deleteFile(file: FileUiItem) {
        viewModelScope.launch {
            try {
                fileRepository.deleteFile(file.id, file.b2FileId, file.b2FileName)
                loadFiles()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                loadFiles()
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
                val expiresAt = System.currentTimeMillis() + 15 * 60 * 1000
                val fileIds = _state.value.selectedIds.toList()

                firestore.collection("accessCodes").add(mapOf(
                    "code" to code,
                    "userId" to user.uid,
                    "fileIds" to fileIds,
                    "expiresAt" to expiresAt,
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()

                _state.value = _state.value.copy(generatedCode = code, selectedIds = emptySet())
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun changePassword(newPassword: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                auth.currentUser?.updatePassword(newPassword)?.await()
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun signOut() {
        auth.signOut()
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

internal fun Long.formatBytes(): String = when {
    this < 1024 -> "$this B"
    this < 1048576 -> "%.1f KB".format(this / 1024f)
    else -> "%.1f MB".format(this / 1048576f)
}
