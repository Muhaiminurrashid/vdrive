package com.vdrive.app.ui.auth

import com.google.firebase.auth.FirebaseAuth
import com.vdrive.app.data.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var authRepository: AuthRepository
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        auth = mockk()
        every { auth.currentUser } returns null
        viewModel = AuthViewModel(authRepository, auth)
    }

    @Test
    fun `login sets isLoading then isSuccess on success`() = runTest(testDispatcher) {
        coEvery { authRepository.login("a@b.com", "pass") } returns mockk()
        viewModel.login("a@b.com", "pass")
        advanceUntilIdle()
        assertEquals(AuthUiState(isSuccess = true), viewModel.state.value)
    }

    @Test
    fun `login sets error on failure`() = runTest(testDispatcher) {
        coEvery { authRepository.login("a@b.com", "pass") } throws Exception("wrong password")
        viewModel.login("a@b.com", "pass")
        advanceUntilIdle()
        assertEquals("wrong password", viewModel.state.value.error)
    }

    @Test
    fun `register sets isLoading then isSuccess`() = runTest(testDispatcher) {
        coEvery { authRepository.register("a@b.com", "pass") } returns mockk()
        viewModel.register("a@b.com", "pass")
        advanceUntilIdle()
        assertEquals(AuthUiState(isSuccess = true), viewModel.state.value)
    }

    @Test
    fun `register sets error on failure`() = runTest(testDispatcher) {
        coEvery { authRepository.register("a@b.com", "pass") } throws Exception("email exists")
        viewModel.register("a@b.com", "pass")
        advanceUntilIdle()
        assertEquals("email exists", viewModel.state.value.error)
    }

    @Test
    fun `init does not succeed without current user`() {
        assertEquals(false, viewModel.state.value.isSuccess)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `init succeeds when user already logged in`() {
        val loggedInAuth = mockk<FirebaseAuth>()
        every { loggedInAuth.currentUser } returns mockk()
        val vm = AuthViewModel(mockk(), loggedInAuth)
        assertEquals(true, vm.state.value.isSuccess)
    }
}
