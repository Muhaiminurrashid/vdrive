package com.vdrive.app.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseService @Inject constructor(
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore
) {
    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    suspend fun signIn(email: String, password: String): FirebaseUser {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user ?: throw Exception("Sign in failed")
    }

    suspend fun signUp(email: String, password: String): FirebaseUser {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: throw Exception("Sign up failed")
        ensureUserDoc(user)
        return user
    }

    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        val user = result.user ?: throw Exception("Google sign in failed")
        ensureUserDoc(user)
        return user
    }

    private suspend fun ensureUserDoc(user: FirebaseUser) {
        val ref = firestore.collection("users").document(user.uid)
        if (!ref.get().await().exists()) {
            ref.set(mapOf(
                "email" to (user.email ?: ""),
                "displayName" to (user.displayName ?: ""),
                "createdAt" to FieldValue.serverTimestamp()
            )).await()
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
