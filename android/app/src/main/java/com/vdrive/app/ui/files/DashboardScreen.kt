package com.vdrive.app.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vdrive.app.domain.model.Folder
import com.vdrive.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showMoveFileDialog by remember { mutableStateOf<FileUiItem?>(null) }
    var showRenameFolderDialog by remember { mutableStateOf<Folder?>(null) }
    var showDeleteFolderDialog by remember { mutableStateOf<Folder?>(null) }
    var showRenameFileDialog by remember { mutableStateOf<FileUiItem?>(null) }
    var showSubscribeDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var isRefreshing by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { isRefreshing = true; viewModel.loadContents() }
    )
    val scope = rememberCoroutineScope()
    if (!state.isLoading) {
        LaunchedEffect(isRefreshing) { isRefreshing = false }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.uploadFiles(uris, context.contentResolver, state.currentFolderId)
    }

    BackHandler(enabled = state.folderPath.isNotEmpty()) {
        viewModel.navigateUp()
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }


    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    showRenameFolderDialog?.let { folder ->
        RenameFolderDialog(
            currentName = folder.name,
            onDismiss = { showRenameFolderDialog = null },
            onRename = { newName ->
                viewModel.renameFolder(folder.id, newName)
                showRenameFolderDialog = null
            }
        )
    }

    showDeleteFolderDialog?.let { folder ->
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = null },
            title = { Text("Delete folder?") },
            text = { Text("Files inside \"${folder.name}\" and its sub-folders will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    showDeleteFolderDialog = null
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { showDeleteFolderDialog = null }) { Text("Cancel") } }
        )
    }

    showRenameFileDialog?.let { file ->
        RenameFileDialog(
            currentName = file.name,
            onDismiss = { showRenameFileDialog = null },
            onRename = { newName ->
                viewModel.renameFile(file.id, newName)
                showRenameFileDialog = null
            }
        )
    }

    showMoveFileDialog?.let { file ->
        MoveFileDialog(
            folders = state.folders,
            currentFolderId = file.folderId,
            onDismiss = { showMoveFileDialog = null },
            onMove = { targetFolderId ->
                viewModel.moveFile(file, targetFolderId)
                showMoveFileDialog = null
            }
        )
    }

    if (showSubscribeDialog) {
        SubscribeDialog(
            onDismiss = { showSubscribeDialog = false },
            onSubmit = { txId ->
                viewModel.submitSubscription(txId)
                showSubscribeDialog = false
            }
        )
    }

                    state.generatedCode?.let { code ->
                        CodeBottomSheet(
                            code = code,
                            expiryLabel = state.codeExpiryLabel,
                            context = context,
                            onDismiss = { viewModel.clearGeneratedCode() }
                        )
                    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(Modifier.height(NavigationDrawerItemDefaults.ItemPadding.calculateTopPadding()))
                Text(
                    text = "Virtual Pendrive",
                    style = MaterialTheme.typography.titleLarge,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch { drawerState.close() }
                            filePicker.launch("*/*")
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Upload", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            showCreateFolderDialog = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Folder", style = MaterialTheme.typography.labelMedium)
                    }
                }
                DrawerStorageIndicator(
                    percent = state.storagePercent,
                    totalBytes = state.totalStorageBytes,
                    isPremium = state.isPremium,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.isPremium) "Premium" else "Free",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDarkTheme) DarkMuted else Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!state.isPremium) {
                        TextButton(onClick = { showSubscribeDialog = true }) {
                            Text("Upgrade", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    icon = { Icon(if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode, contentDescription = null) },
                    label = { Text(if (isDarkTheme) "Dark mode" else "Light mode") },
                    selected = false,
                    onClick = onToggleTheme
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Logout, contentDescription = null) },
                    label = { Text("Sign out") },
                    selected = false,
                    onClick = {
                        viewModel.signOut()
                        onSignOut()
                    }
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = if (isDarkTheme) DarkCanvas else Canvas,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state.folderPath.isNotEmpty()) {
                                viewModel.navigateUp()
                            } else {
                                scope.launch { drawerState.open() }
                            }
                        }) {
                            Icon(
                                if (state.folderPath.isNotEmpty()) Icons.Default.ArrowBack else Icons.Default.Menu,
                                contentDescription = if (state.folderPath.isNotEmpty()) "Back" else "Menu",
                                tint = if (isDarkTheme) DarkInk else Ink
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "My Files",
                            color = if (isDarkTheme) DarkInk else Ink
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                if (state.viewMode == ViewMode.List) Icons.Default.GridView else Icons.Default.ViewList,
                                contentDescription = "Toggle view",
                                tint = if (isDarkTheme) DarkInk else Ink
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = "Account",
                                    tint = if (isDarkTheme) DarkInk else Primary
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                Text(
                                    text = state.userEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider()
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isDarkTheme) DarkSurface else Canvas
                    )
                )
            },
            floatingActionButton = {
                Box {
                    FloatingActionButton(
                        onClick = { fabExpanded = true },
                        containerColor = Primary,
                        contentColor = OnPrimary,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New")
                    }
                    DropdownMenu(
                        expanded = fabExpanded,
                        onDismissRequest = { fabExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Upload file") },
                            onClick = {
                                fabExpanded = false
                                filePicker.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            onClick = {
                                fabExpanded = false
                                showCreateFolderDialog = true
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                BreadcrumbBar(
                    folderPath = state.folderPath,
                    onNavigate = { index ->
                        viewModel.navigateToBreadcrumb(index)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    isDarkTheme = isDarkTheme
                )

                if (state.selectedIds.isNotEmpty()) {
                    ActionBar(
                        count = state.selectedIds.size,
                        onDownload = { viewModel.downloadSelected(context) },
                        onDelete = { viewModel.deleteSelected() },
                        onCancel = { viewModel.clearSelection() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val total = state.files.size + state.subFolders.size
                    Text(
                        text = "$total item${if (total != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isDarkTheme) DarkMuted else Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.generateFolderCode(state.currentFolderId) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Generate code", style = MaterialTheme.typography.labelSmall)
                    }
                }

                state.actionLabel?.let { label ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            label, style = MaterialTheme.typography.labelSmall,
                            color = if (isDarkTheme) DarkMuted else Muted,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        state.uploadProgress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Primary,
                                trackColor = if (isDarkTheme) DarkHairline else Hairline,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .pullRefresh(pullRefreshState)
                ) {
                    if (state.isLoading && state.files.isEmpty() && state.subFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Primary)
                        }
                    } else if (state.files.isEmpty() && state.subFolders.isEmpty()) {
                        EmptyState(modifier = Modifier.fillMaxSize(), isDarkTheme = isDarkTheme)
                    } else if (state.viewMode == ViewMode.Grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp,
                                top = 4.dp, bottom = 88.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.subFolders, key = { "folder_${it.id}" }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    onClick = { viewModel.navigateToFolder(folder.id) },
                                    onDelete = { showDeleteFolderDialog = folder },
                                    onRename = { showRenameFolderDialog = folder },
                                    onShare = { viewModel.generateFolderCode(folder.id) },
                                    isDarkTheme = isDarkTheme
                                )
                            }
                            items(state.files, key = { it.id }) { file ->
                                FileGridCard(
                                    file = file,
                                    isSelected = file.id in state.selectedIds,
                                    onToggleSelect = { viewModel.toggleSelection(file.id) },
                                    onClick = { viewModel.previewFile(file, context) },
                                    onDownload = { viewModel.downloadFile(file, context) },
                                    onDelete = { viewModel.deleteFile(file) },
                                    onRename = { showRenameFileDialog = file },
                                    isDarkTheme = isDarkTheme
                                )
                            }
                            if (state.hasMoreFiles) {
                                item(span = { GridItemSpan(2) }) {
                                    LoadMoreButton(onClick = { viewModel.loadMoreFiles() })
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp,
                                top = 4.dp, bottom = 88.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.subFolders, key = { "folder_${it.id}" }) { folder ->
                                FolderCard(
                                    folder = folder,
                                    onClick = { viewModel.navigateToFolder(folder.id) },
                                    onDelete = { showDeleteFolderDialog = folder },
                                    onRename = { showRenameFolderDialog = folder },
                                    onShare = { viewModel.generateFolderCode(folder.id) },
                                    isDarkTheme = isDarkTheme
                                )
                            }
                            items(state.files, key = { it.id }) { file ->
                                FileCard(
                                    file = file,
                                    isSelected = file.id in state.selectedIds,
                                    onToggleSelect = { viewModel.toggleSelection(file.id) },
                                    onClick = { viewModel.previewFile(file, context) },
                                    onDownload = { viewModel.downloadFile(file, context) },
                                    onDelete = { viewModel.deleteFile(file) },
                                    onMove = { showMoveFileDialog = file },
                                    onRename = { showRenameFileDialog = file },
                                    isDarkTheme = isDarkTheme
                                )
                            }
                            if (state.hasMoreFiles) {
                                item {
                                    LoadMoreButton(onClick = { viewModel.loadMoreFiles() })
                                }
                            }
                        }
                    }
                    PullRefreshIndicator(
                        refreshing = isRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        backgroundColor = if (isDarkTheme) DarkSurface else SurfaceCard,
                        contentColor = Primary,
                    )
                }
            }
        }
    }

}

@Composable
private fun BreadcrumbBar(
    folderPath: List<Folder>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        val parts = listOf(null as String?, *folderPath.map { it.id }.toTypedArray())
        for ((i, _) in parts.withIndex()) {
            val name = when (i) {
                0 -> "My Files"
                else -> folderPath[i - 1].name
            }
            val last = i == parts.lastIndex
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = if (last) (if (isDarkTheme) DarkInk else Ink) else Primary,
                modifier = if (last) Modifier else Modifier.clickable { onNavigate(i) }
            )
            if (!last) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (isDarkTheme) DarkMutedSoft else MutedSoft,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit = {},
    isDarkTheme: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val bg = if (isDarkTheme) DarkSurface else SurfaceCard

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Primary.copy(alpha = 0.1f)
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) DarkInk else Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = if (isDarkTheme) DarkMutedSoft else MutedSoft,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = { showMenu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = ErrorRed) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderGridCard(
    folder: Folder,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit = {},
    isDarkTheme: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val bg = if (isDarkTheme) DarkSurface else SurfaceCard

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = if (isDarkTheme) DarkMutedSoft else MutedSoft, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text("Share") }, onClick = { showMenu = false; onShare() })
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text("Rename") }, onClick = { showMenu = false; onRename() })
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorRed) },
                        text = { Text("Delete", color = ErrorRed) }, onClick = { showMenu = false; onDelete() })
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Primary.copy(alpha = 0.1f)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Primary, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) DarkInk else Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCard(
    file: FileUiItem,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRename: () -> Unit = {},
    isDarkTheme: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val bg = if (isDarkTheme) DarkSurface else SurfaceCard
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Primary.copy(alpha = 0.06f) else bg,
        tonalElevation = 0.dp,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onToggleSelect)
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            FileTypeBadge(typeLabel = file.typeLabel)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) DarkInk else Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = file.sizeBytes.formatBytes(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDarkTheme) DarkMutedSoft else MutedSoft
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = if (isDarkTheme) DarkMutedSoft else MutedSoft, modifier = Modifier.size(16.dp))
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = { Text("Download") }, onClick = { showMenu = false; onDownload() })
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = { Text("Rename") }, onClick = { showMenu = false; onRename() })
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorRed) },
                    text = { Text("Delete", color = ErrorRed) }, onClick = { showMenu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New folder", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    focusedContainerColor = Canvas,
                    unfocusedContainerColor = Canvas,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) {
                Text("Create", color = if (name.isBlank()) Muted else Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
    )
}

@Composable
private fun RenameFolderDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename folder", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    focusedContainerColor = Canvas,
                    unfocusedContainerColor = Canvas,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onRename(name) }) {
                Text("Rename", color = if (name.isBlank()) Muted else Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
    )
}

@Composable
private fun RenameFileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename file", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    focusedContainerColor = Canvas,
                    unfocusedContainerColor = Canvas,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onRename(name) }) {
                Text("Rename", color = if (name.isBlank()) Muted else Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
    )
}

@Composable
private fun MoveFileDialog(
    folders: List<Folder>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Move to", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                DropdownMenuItem(
                    text = { Text("My Files (root)") },
                    onClick = { onMove(null) }
                )
                folders.filter { it.id != currentFolderId }.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder.name) },
                        onClick = { onMove(folder.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
    )
}

@Composable
private fun StorageCard(
    percent: Float,
    totalBytes: Long,
    fileCount: Int,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
) {
    val bg = if (isDarkTheme) DarkSurfaceElevated else SurfaceCard
    val textColor = if (isDarkTheme) DarkInk else Ink
    val mutedColor = if (isDarkTheme) DarkMutedSoft else MutedSoft
    val trackColor = if (isDarkTheme) DarkHairline else Hairline

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = Primary.copy(alpha = 0.1f)) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = Primary, modifier = Modifier.padding(10.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Storage", style = MaterialTheme.typography.titleLarge, color = textColor)
                    Text(text = "${totalBytes.formatBytes()} / 1 GB", style = MaterialTheme.typography.labelSmall, color = mutedColor)
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Primary,
                    trackColor = trackColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(percent * 100).toInt()}% used · $fileCount file${if (fileCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (percent > 0.9f) ErrorRed else mutedColor
                )
            }
        }
    }
}

// ponytail: Google Drive-style compact storage bar for drawer
@Composable
private fun DrawerStorageIndicator(
    percent: Float,
    totalBytes: Long,
    isPremium: Boolean = false,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor = if (isDarkTheme) DarkMuted else Muted
    val trackColor = if (isDarkTheme) DarkHairline else Hairline
    val cap = if (isPremium) "10 GB" else "1 GB"
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "${totalBytes.formatBytes()} of $cap used",
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { percent },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Primary,
            trackColor = trackColor,
        )
    }
}

@Composable
private fun LoadMoreButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Show more", style = MaterialTheme.typography.labelLarge, color = Primary)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, isDarkTheme: Boolean = false) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(20.dp), color = Primary.copy(alpha = 0.08f)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Primary, modifier = Modifier.padding(20.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "No files yet", style = MaterialTheme.typography.titleLarge, color = if (isDarkTheme) DarkInk else Ink)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Upload your first lecture material\nto get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) DarkMuted else Muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: FileUiItem,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    isDarkTheme: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val bg = if (isSelected) Primary.copy(alpha = 0.06f) else if (isDarkTheme) DarkSurface else SurfaceCard

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onToggleSelect)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileTypeBadge(typeLabel = file.typeLabel)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) DarkInk else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = buildString {
                    append(file.sizeBytes.formatBytes())
                    file.folderName?.let { append(" · in $it") }
                }
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = if (isDarkTheme) DarkMuted else Muted)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = if (isDarkTheme) DarkMutedSoft else MutedSoft)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text("Download") }, onClick = { showMenu = false; onDownload() })
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text("Rename") }, onClick = { showMenu = false; onRename() })
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text("Move to") }, onClick = { showMenu = false; onMove() })
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorRed) },
                        text = { Text("Delete", color = ErrorRed) }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeBottomSheet(
    code: String,
    expiryLabel: String = "1 hour",
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
            modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Classroom Access Code", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Share this code with your students", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(modifier = Modifier.height(20.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = SurfaceDark) {
                Text(text = code, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), fontFamily = FontFamily.Monospace, fontSize = 32.sp, color = OnDark, letterSpacing = 10.sp, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Expires in $expiryLabel", style = MaterialTheme.typography.bodySmall, color = MutedSoft)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Open: vdrive-64deb.web.app/access.html",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Access code", code))
                        Toast.makeText(context, "Code copied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy code", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Access link", "https://vdrive-64deb.web.app/access.html"))
                        Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy link", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun SubscribeDialog(
    onDismiss: () -> Unit,
    onSubmit: (txId: String) -> Unit,
) {
    var txId by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Upgrade to Premium") },
        text = {
            Column {
                Text("Send 99 BDT to bKash, then enter TX ID below:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("01605091313", fontFamily = FontFamily.Monospace, color = Primary, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        val c = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        c.setPrimaryClip(ClipData.newPlainText("bKash", "01605091313"))
                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copy", tint = Primary) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = txId, onValueChange = { txId = it }, label = { Text("bKash TX ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(txId) }, enabled = txId.isNotBlank()) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ponytail: file type badge color map
private fun fileTypeBadgeColor(typeLabel: String) = when (typeLabel) {
    "PDF" -> FilePdf
    "DOC", "DOCX" -> FileDoc
    "PPT", "PPTX" -> FilePpt
    "XLS", "XLSX" -> FilePpt
    "ZIP" -> FileZip
    "Video" -> FileVideo
    else -> FileDefault
}

@Composable
private fun FileTypeBadge(typeLabel: String) {
    val color = fileTypeBadgeColor(typeLabel)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = typeLabel.uppercase(),
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

internal fun Long.formatBytes(): String = when {
    this < 1024 -> "$this B"
    this < 1048576 -> "%.1f KB".format(this / 1024f)
    else -> "%.1f MB".format(this / 1048576f)
}

@Composable
private fun ActionBar(
    count: Int,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Primary.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.labelMedium,
                color = Primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("ZIP", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

