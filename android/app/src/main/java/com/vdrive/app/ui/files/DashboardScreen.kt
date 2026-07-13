package com.vdrive.app.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vdrive.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onSignOut: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showCodeSheet by remember { mutableStateOf(false) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.uploadFile(it, context.contentResolver) }
    }

    LaunchedEffect(state.generatedCode) {
        if (state.generatedCode != null) showCodeSheet = true
    }

    val hasSelection = state.selectedIds.isNotEmpty()

    if (showPasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onChange = { pass ->
                viewModel.changePassword(pass) { error ->
                    if (error == null) showPasswordDialog = false
                }
            }
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Canvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Virtual Pendrive",
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        fontFamily = FontFamily.Serif,
                    )
                },
                actions = {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text(
                                text = state.userEmail.takeWhile { it != '@' },
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Change password") },
                                onClick = {
                                    menuExpanded = false
                                    showPasswordDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.signOut()
                                    onSignOut()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Canvas
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePicker.launch("*/*") },
                containerColor = Primary,
                contentColor = OnPrimary,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = "Upload file")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StorageCard(
                percent = state.storagePercent,
                fileCount = state.fileCount,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.files.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (hasSelection) "${state.selectedIds.size} selected" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    if (hasSelection) {
                        FilledTonalButton(
                            onClick = { viewModel.generateCode() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share selected")
                        }
                    } else {
                        TextButton(onClick = { viewModel.generateCode() }) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Generate code", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.isLoading && state.files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (state.files.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 4.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.files, key = { it.id }) { file ->
                        FileCard(
                            file = file,
                            isSelected = file.id in state.selectedIds,
                            onToggleSelect = { viewModel.toggleSelection(file.id) },
                            onDownload = { viewModel.downloadFile(file, context) },
                            onDelete = { viewModel.deleteFile(file) },
                        )
                        }
                    }
                }
            }
        }

    if (showCodeSheet && state.generatedCode != null) {
        CodeBottomSheet(
            code = state.generatedCode!!,
            context = context,
            onDismiss = { showCodeSheet = false }
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) {
                    Text(error!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New password") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        focusedContainerColor = Canvas,
                        unfocusedContainerColor = Canvas,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.length < 6) {
                        error = "Password must be at least 6 characters"
                    } else {
                        onChange(password)
                    }
                }
            ) { Text("Save", color = Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
    )
}

@Composable
private fun StorageCard(
    percent: Float,
    fileCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceCard,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Primary.copy(alpha = 0.1f)
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Free",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Primary,
                    trackColor = Hairline,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(percent * 100).toInt()}% used",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (percent > 0.9f) ErrorRed else MutedSoft
                    )
                    Text(
                        text = "$fileCount files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSoft
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Primary.copy(alpha = 0.08f)
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.padding(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "No files yet",
                style = MaterialTheme.typography.titleLarge,
                color = Ink
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Upload your first lecture material\nto get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FileCard(
    file: FileUiItem,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (isSelected) Primary.copy(alpha = 0.06f) else SurfaceCard
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDownload)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clickable(onClick = onToggleSelect),
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Primary else Canvas,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = OnPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.typeLabel} · ${file.sizeBytes.formatBytes()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MutedSoft,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeBottomSheet(
    code: String,
    context: Context,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Hairline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Classroom Access Code",
                style = MaterialTheme.typography.titleLarge,
                color = Ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Share this code with your students",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = SurfaceDark,
            ) {
                Text(
                    text = code,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    color = OnDark,
                    letterSpacing = 10.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Expires in 15 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSoft
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Access code", code))
                    Toast.makeText(context, "Code copied!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy code", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
