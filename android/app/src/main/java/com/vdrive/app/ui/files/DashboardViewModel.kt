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
    val fileCount: Int = 0,
    val storagePercent: Float = 0f,
    val totalStorageBytes: Long = 0L,
    val folders: List<Folder> = emptyList(),
    val subFolders: List<Folder> = emptyList(),
    val currentFolderId: String? = null,
    val folderPath: List<Folder> = emptyList(),
    val isLoading: Boolean = false,
    val userEmail: String = "",
    val generatedCode: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.List,
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
            loadFolders()
        }
    }

    // ponytail: loads all user files + folders, filters in-memory for current context
    fun loadContents() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val folderId = _state.value.currentFolderId
                val fileSnap = firestore.collection("files")
                    .whereEqualTo("userId", user.uid).get().await()
                val folderSnap = firestore.collection("folders")
                    .whereEqualTo("userId", user.uid).get().await()

                val folderNames = folderSnap.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    doc.id to (d["name"] as? String ?: "")
                }.toMap()

                var totalBytes = 0L
                fileSnap.documents.forEach { doc ->
                    doc.data?.let { totalBytes += (it["size"] as? Number)?.toLong() ?: 0L }
                }
                val items = fileSnap.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val fId = data["folderId"] as? String
                    if ((fId ?: "") != (folderId ?: "")) return@mapNotNull null
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

                val subFolders = folderSnap.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    val pfId = d["parentId"] as? String
                    if ((pfId ?: "") != (folderId ?: "")) return@mapNotNull null
                    Folder(
                        id = doc.id,
                        name = d["name"] as? String ?: "",
                        parentId = pfId
                    )
                }

                _state.value = _state.value.copy(
                    files = items,
                    fileCount = items.size,
                    storagePercent = (totalBytes / 1_000_000_000f).coerceAtMost(1f),
                    totalStorageBytes = totalBytes,
                    subFolders = subFolders,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
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
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun navigateToFolder(folderId: String) {
        val folder = _state.value.folders.find { it.id == folderId } ?: return
        val path = _state.value.folderPath + folder
        _state.value = _state.value.copy(
            currentFolderId = folderId,
            folderPath = path
        )
        loadContents()
    }

    fun navigateUp() {
        val path = _state.value.folderPath
        if (path.isEmpty()) return
        val newPath = path.dropLast(1)
        _state.value = _state.value.copy(
            currentFolderId = newPath.lastOrNull()?.id,
            folderPath = newPath
        )
        loadContents()
    }

    fun navigateToBreadcrumb(index: Int) {
        val path = _state.value.folderPath
        if (index >= path.size + 1) return
        val newPath = path.take(index)
        _state.value = _state.value.copy(
            currentFolderId = newPath.lastOrNull()?.id,
            folderPath = newPath
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
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                firestore.collection("folders").document(folderId).delete().await()
                navigateUp(); loadFolders()
                // ponytail: silent child cleanup, errors don't block navigation
                runCatching {
                    firestore.collection("files").whereEqualTo("folderId", folderId).get().await()
                        .documents.forEach { runCatching { it.reference.update("folderId", null).await() } }
                    firestore.collection("folders").whereEqualTo("parentId", folderId).get().await()
                        .documents.forEach { runCatching { it.reference.update("parentId", null).await() } }
                }
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun renameFile(fileId: String, newName: String) {
        viewModelScope.launch {
            try {
                firestore.collection("files").document(fileId)
                    .update("name", newName).await()
                loadContents()
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun toggleViewMode() {
        _state.value = _state.value.copy(
            viewMode = if (_state.value.viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List
        )
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            try {
                firestore.collection("folders").document(folderId)
                    .update("name", newName).await()
                loadFolders()
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun moveFile(file: FileUiItem, targetFolderId: String?) {
        viewModelScope.launch {
            try {
                firestore.collection("files").document(file.id)
                    .update("folderId", targetFolderId).await()
                loadContents()
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun uploadFile(uri: Uri, contentResolver: ContentResolver, folderId: String? = null) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                fileRepository.uploadFile(user.uid, uri, contentResolver, folderId)
                loadContents()
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
                loadContents()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                loadContents()
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


