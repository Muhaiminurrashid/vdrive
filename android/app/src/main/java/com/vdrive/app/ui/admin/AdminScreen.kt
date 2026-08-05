package com.vdrive.app.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vdrive.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = if (isDarkTheme) DarkCanvas else Canvas,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (isDarkTheme) DarkInk else Ink)
                    }
                },
                title = {
                    Text("Admin Panel", color = if (isDarkTheme) DarkInk else Ink)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDarkTheme) DarkSurface else Canvas
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isAdminLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Primary) }

                !state.isAdmin -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "You don't have admin access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) DarkMuted else Muted
                    )
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SubscriptionFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Primary,
                                    selectedLabelColor = OnPrimary,
                                    containerColor = if (isDarkTheme) DarkSurface else SurfaceCard,
                                    labelColor = if (isDarkTheme) DarkInk else Ink,
                                ),
                                border = null,
                            )
                        }
                    }
                    val filtered = if (state.filter == SubscriptionFilter.All)
                        state.subscriptions
                    else
                        state.subscriptions.filter { it.status == state.filter.label.lowercase() }

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No ${state.filter.label.lowercase()} subscriptions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDarkTheme) DarkMuted else Muted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered, key = { it.id }) { sub ->
                                SubscriptionCard(
                                    sub = sub,
                                    isDarkTheme = isDarkTheme,
                                    onApprove = { viewModel.approve(sub.id) },
                                    onReject = { viewModel.reject(sub.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: SubscriptionItem,
    isDarkTheme: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val bg = if (isDarkTheme) DarkSurface else SurfaceCard
    val badgeColor = when (sub.status) {
        "pending" -> Warning
        "active" -> Success
        else -> ErrorRed
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sub.email,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isDarkTheme) DarkInk else Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sub.status,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "TX: ${sub.txId} · BDT ${sub.txAmount} · ${formatDate(sub.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) DarkMuted else Muted
            )
            if (sub.status == "pending") {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Approve", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onReject,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reject", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    if (millis == 0L) "-" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
