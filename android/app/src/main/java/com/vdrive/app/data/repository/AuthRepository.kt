package com.vdrive.app.data.repository

import com.google.firebase.auth.FirebaseUser
import com.vdrive.app.data.firebase.FirebaseService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val firebaseService: FirebaseService
) {
    fun isLoggedIn(): Boolean = firebaseService.getCurrentUser() != null

    fun getCurrentUser(): FirebaseUser? = firebaseService.getCurrentUser()

    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        return firebaseService.signInWithGoogle(idToken)
    }

    fun logout() {
        firebaseService.signOut()
    }
}
