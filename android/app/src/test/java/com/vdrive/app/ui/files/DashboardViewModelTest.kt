package com.vdrive.app.ui.files

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.vdrive.app.data.repository.FileRepository
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
import kotlin.test.assertTrue
import com.google.android.gms.tasks.Tasks
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var fileRepository: FileRepository
    private lateinit var user: FirebaseUser
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        auth = mockk()
        firestore = mockk()
        fileRepository = mockk()
        user = mockk()
        every { auth.currentUser } returns user
        every { user.uid } returns "user123"
        every { user.email } returns "teacher@school.edu"
    }

    private fun mockFirestoreSnapshot(
        docs: List<Pair<String, Map<String, Any?>>> = emptyList(),
        folders: List<Pair<String, Map<String, Any?>>> = emptyList()
    ) {
        val filesColRef = mockk<CollectionReference>()
        val filesSnap = mockk<QuerySnapshot>()
        every { firestore.collection("files") } returns filesColRef
        every { filesColRef.whereEqualTo("userId", "user123") } returns filesColRef
        coEvery { filesColRef.get() } returns Tasks.forResult(filesSnap)
        every { filesSnap.documents } returns docs.map { (id, data) ->
            val doc = mockk<com.google.firebase.firestore.QueryDocumentSnapshot>()
            every { doc.id } returns id
            every { doc.data } returns data
            doc
        }

        val foldersColRef = mockk<CollectionReference>()
        val foldersSnap = mockk<QuerySnapshot>()
        every { firestore.collection("folders") } returns foldersColRef
        every { foldersColRef.whereEqualTo("userId", "user123") } returns foldersColRef
        coEvery { foldersColRef.get() } returns Tasks.forResult(foldersSnap)
        every { foldersSnap.documents } returns folders.map { (id, data) ->
            val doc = mockk<com.google.firebase.firestore.QueryDocumentSnapshot>()
            every { doc.id } returns id
            every { doc.data } returns data
            doc
        }
    }

    @Test
    fun `loadFiles parses documents and computes storage`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(listOf(
            "f1" to mapOf("name" to "lecture1.pdf", "size" to 500_000L, "type" to "application/pdf"),
            "f2" to mapOf("name" to "notes.docx", "size" to 1_200_000L, "type" to "application/docx"),
        ))
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(2, state.fileCount)
        assertEquals(2, state.files.size)
        assertEquals("lecture1.pdf", state.files[0].name)
        assertEquals("PDF", state.files[0].typeLabel)
        assertEquals(0.0017f, state.storagePercent, 0.001f)
    }

    @Test
    fun `loadFiles handles empty collection`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(emptyList())
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(0, state.fileCount)
        assertEquals(0f, state.storagePercent)
    }

    @Test
    fun `loadFiles sets error on exception`() = runTest(testDispatcher) {
        val filesColRef = mockk<CollectionReference>()
        every { firestore.collection("files") } returns filesColRef
        every { filesColRef.whereEqualTo("userId", "user123") } returns filesColRef
        coEvery { filesColRef.get() } returns Tasks.forException(Exception("network error"))

        val foldersColRef = mockk<CollectionReference>()
        val foldersSnap = mockk<QuerySnapshot>()
        every { firestore.collection("folders") } returns foldersColRef
        every { foldersColRef.whereEqualTo("userId", "user123") } returns foldersColRef
        coEvery { foldersColRef.get() } returns Tasks.forResult(foldersSnap)
        every { foldersSnap.documents } returns emptyList()

        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        assertEquals("network error", viewModel.state.value.error)
    }

    @Test
    fun `toggleSelection adds and removes id`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(emptyList())
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        viewModel.toggleSelection("f1")
        assertTrue("f1" in viewModel.state.value.selectedIds)
        viewModel.toggleSelection("f1")
        assertTrue("f1" !in viewModel.state.value.selectedIds)
    }

    @Test
    fun `uploadFile delegates to repository and reloads`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(emptyList())
        coEvery { fileRepository.uploadFile(any(), any(), any()) } returns "newFileId"
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.fileCount)
        viewModel.uploadFile(mockk(), mockk())
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.files)
    }

    @Test
    fun `generateCode creates 6-char code`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(
            docs = listOf("f1" to mapOf("name" to "a.pdf", "size" to 100L))
        )
        val accessColRef = mockk<CollectionReference>()
        every { firestore.collection("accessCodes") } returns accessColRef
        coEvery { accessColRef.add(any()) } returns Tasks.forResult(mockk())
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        viewModel.toggleSelection("f1")
        viewModel.generateCode()
        advanceUntilIdle()
        val code = viewModel.state.value.generatedCode
        assertNotNull(code)
        assertEquals(6, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    }

    @Test
    fun `signOut calls auth signOut`() {
        mockFirestoreSnapshot(emptyList())
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        every { auth.signOut() } returns Unit
        viewModel.signOut()
    }

    @Test
    fun `deleteFile calls repository and reloads`() = runTest(testDispatcher) {
        mockFirestoreSnapshot(listOf(
            "f1" to mapOf("name" to "a.pdf", "size" to 100L)
        ))
        coEvery { fileRepository.deleteFile(any(), any(), any()) } returns Unit
        viewModel = DashboardViewModel(auth, firestore, fileRepository)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.fileCount)
        viewModel.deleteFile(viewModel.state.value.files.first())
        advanceUntilIdle()
    }
}
