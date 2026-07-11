package com.vdrive.app.ui.auth

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.vdrive.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        if (auth.currentUser != null) {
            _state.value = AuthUiState(isSuccess = true)
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                authRepository.login(email, password)
                _state.value = AuthUiState(isSuccess = true)
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message)
            }
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                authRepository.register(email, password)
                _state.value = AuthUiState(isSuccess = true)
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message)
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                _state.value = AuthUiState(error = "Password reset email sent")
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message)
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            try {
                // ponytail: serverClientId is Firebase web client ID, needed for Credential Manager
                val webClientId = "541773803308-4qjtfmvd9cs7rvn468gtslf6hgaqss87.apps.googleusercontent.com"
                val credentialManager = CredentialManager.create(activity)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(
                        GetGoogleIdOption.Builder()
                            .setServerClientId(webClientId)
                            .setFilterByAuthorizedAccounts(false)
                            .setAutoSelectEnabled(false)
                            .build()
                    )
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val idToken = GoogleIdTokenCredential
                    .createFrom(result.credential.data)
                    .idToken
                authRepository.signInWithGoogle(idToken)
                _state.value = AuthUiState(isSuccess = true)
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.message)
            }
        }
    }
}
