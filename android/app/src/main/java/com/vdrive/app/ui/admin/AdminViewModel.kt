package com.vdrive.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
import java.util.Date
import javax.inject.Inject

// ponytail: set after deploying Worker
private const val B2_PROXY_URL = "https://b2-proxy.muhaiminurrashid99.workers.dev"

enum class SubscriptionFilter(val label: String) { Pending("Pending"), Active("Active"), Rejected("Rejected"), All("All") }

data class SubscriptionItem(
    val id: String,
    val userId: String,
    val email: String,
    val txId: String,
    val txAmount: Long,
    val status: String,
    val createdAt: Long,
)

data class AdminUiState(
    val isAdmin: Boolean = false,
    val isAdminLoading: Boolean = true,
    val subscriptions: List<SubscriptionItem> = emptyList(),
    val filter: SubscriptionFilter = SubscriptionFilter.Pending,
    val error: String? = null,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init { checkAdmin() }

    fun checkAdmin() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                val adminUid = withContext(Dispatchers.IO) {
                    val conn = URL("$B2_PROXY_URL/api/config").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode >= 300) "" else JSONObject(conn.inputStream.bufferedReader().readText()).optString("adminUid")
                }
                if (adminUid.isNotEmpty() && adminUid == user.uid) {
                    _state.value = _state.value.copy(isAdmin = true, isAdminLoading = false)
                    load()
                } else {
                    _state.value = _state.value.copy(isAdmin = false, isAdminLoading = false)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isAdmin = false, isAdminLoading = false, error = "Admin check failed")
            }
        }
    }

    fun setFilter(filter: SubscriptionFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val subsSnap = firestore.collection("subscriptions")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .get().await()
                val usersSnap = firestore.collection("users").get().await()
                val emails = usersSnap.documents.associate { it.id to (it.getString("email") ?: it.id) }
                val subs = subsSnap.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val uid = data["userId"] as? String ?: return@mapNotNull null
                    SubscriptionItem(
                        id = doc.id,
                        userId = uid,
                        email = emails[uid] ?: uid,
                        txId = data["txId"] as? String ?: "-",
                        txAmount = (data["txAmount"] as? Number)?.toLong() ?: 0L,
                        status = data["status"] as? String ?: "pending",
                        createdAt = (data["createdAt"] as? Timestamp)?.toDate()?.time ?: 0L,
                    )
                }
                _state.value = _state.value.copy(subscriptions = subs)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to load subscriptions")
            }
        }
    }

    fun approve(id: String) = setStatus(id, "active", Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))

    fun reject(id: String) = setStatus(id, "rejected", null)

    private fun setStatus(id: String, status: String, expiresAt: Date?) {
        viewModelScope.launch {
            try {
                val sub = _state.value.subscriptions.find { it.id == id } ?: return@launch
                if (sub.status != "pending") return@launch
                val fields = mutableMapOf<String, Any>("status" to status)
                expiresAt?.let { fields["expiresAt"] = Timestamp(it) }
                firestore.collection("subscriptions").document(id).update(fields).await()
                // ponytail: update local state instead of re-fetching (avoids cache staleness)
                _state.value = _state.value.copy(
                    subscriptions = _state.value.subscriptions.map { if (it.id == id) it.copy(status = status) else it }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Update failed")
            }
        }
    }
}
