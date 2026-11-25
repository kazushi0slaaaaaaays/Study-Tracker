package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LinkedFileEntry
import com.example.data.LocalFileStatus
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.parseHexColor
import com.example.ui.theme.subjectTint
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyViewModel

@Composable
fun FilesScreen(
    state: StudyAppState,
    viewModel: StudyViewModel,
) {
    val colors = LocalStudyColors.current
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val expandedSubjects = remember { mutableStateMapOf<String, Boolean>() }

    // Pending SAF picker states
    var pendingPickedFile by remember { mutableStateOf<Triple<String, String, String>?>(null) } // uri, name, mime
    var relinkingFile by remember { mutableStateOf<LinkedFileEntry?>(null) }
    var renamingFile by remember { mutableStateOf<LinkedFileEntry?>(null) }
    var movingFile by remember { mutableStateOf<LinkedFileEntry?>(null) }
    var linkPromptFile by remember { mutableStateOf<LinkedFileEntry?>(null) }
    var openErrorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.files.size) {
        viewModel.refreshFileStatuses()
    }

    // Android Storage Access Framework (ACTION_OPEN_DOCUMENT) with persistable URI permission
    val addFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            val (name, mime) = resolveDocumentMeta(context, uri)
            pendingPickedFile = Triple(uri.toString(), name, mime)
        }
    }

    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = relinkingFile
        relinkingFile = null
        if (uri != null && target != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            viewModel.relinkLocalFile(target.id, uri.toString())
        }
    }

    val filteredFiles = remember(state.files, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) state.files
        else state.files.filter { it.displayName.lowercase().contains(q) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top search field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search linked study files...", color = colors.textDim) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("files_search_input"),
                )
            }

            if (openErrorMsg != null) {
                Text(
                    text = openErrorMsg!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.activeSubjects.isEmpty()) {
                    item {
                        FriendlyEmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            title = "Create a subject first",
                            subtitle = "Files are organized under subjects. Add a subject in Timer or Plan, then link local PDFs, notes, or slides.",
                        )
                    }
                } else if (filteredFiles.isEmpty()) {
                    item {
                        FriendlyEmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            title = if (searchQuery.isBlank()) "No linked files yet" else "No matching files",
                            subtitle = "Files stay on your device and are never uploaded. Tap Add file to link a PDF, textbook, or notes sheet.",
                            actionLabel = if (searchQuery.isBlank()) "Link a file" else null,
                            onAction = if (searchQuery.isBlank()) {
                                { addFileLauncher.launch(arrayOf("*/*")) }
                            } else null,
                            testTag = "empty_files_link_btn",
                        )
                    }
                } else {
                    items(state.activeSubjects, key = { it.id }) { subject ->
                        val subFiles = filteredFiles.filter { it.subjectId == subject.id }
                        if (subFiles.isEmpty()) return@items
                        val subColor = parseHexColor(subject.colorHex)
                        val expanded = expandedSubjects[subject.id] ?: true

                        StudyCard(contentPadding = 0.dp) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(subColor.subjectTint())
                                    .clickable { expandedSubjects[subject.id] = !expanded }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = subject.emoji.ifBlank { "📘" }, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = subject.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.text,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${subFiles.size} file${if (subFiles.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textDim,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = colors.textDim,
                                )
                            }

                            AnimatedVisibility(visible = expanded) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    subFiles.forEach { file ->
                                        val status = state.fileStatuses[file.id] ?: LocalFileStatus.NOT_LINKED
                                        FileRow(
                                            entry = file,
                                            status = status,
                                            onClick = {
                                                openErrorMsg = null
                                                if (status == LocalFileStatus.LINKED) {
                                                    val uriStr = viewModel.getLocalFileUri(file.id)
                                                    if (uriStr != null) {
                                                        val opened = openExternalUri(context, uriStr, file.mime)
                                                        if (!opened) {
                                                            openErrorMsg = "Could not open ${file.displayName}. It may have moved or no viewer app is installed."
                                                            viewModel.refreshFileStatuses()
                                                        }
                                                    } else {
                                                        linkPromptFile = file
                                                    }
                                                } else {
                                                    linkPromptFile = file
                                                }
                                            },
                                            onRename = { renamingFile = file },
                                            onMove = { movingFile = file },
                                            onRemove = { viewModel.removeFileFromList(file.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Add file button
        if (state.activeSubjects.isNotEmpty()) {
            FloatingActionButton(
                onClick = { addFileLauncher.launch(arrayOf("*/*")) },
                containerColor = colors.accent,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .testTag("files_fab_add"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Link a study file")
            }
        }
    }

    // After picking a file via SAF, ask which subject to attach it to
    pendingPickedFile?.let { (uriStr, defaultName, mime) ->
        var chosenSubId by remember(uriStr) { mutableStateOf(state.effectiveSelectedSubjectId) }
        var displayName by remember(uriStr) { mutableStateOf(defaultName) }
        AlertDialog(
            onDismissRequest = { pendingPickedFile = null },
            containerColor = colors.surface,
            title = { Text("Link File to Subject", color = colors.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(200) },
                        label = { Text("Display name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Subject", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.activeSubjects, key = { it.id }) { sub ->
                            val sel = sub.id == chosenSubId
                            val c = parseHexColor(sub.colorHex)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) c.subjectTint() else colors.surface2)
                                    .border(1.dp, if (sel) c else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { chosenSubId = sub.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text(
                        text = "The file is linked locally via Android SAF and never uploaded to the cloud.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textDim,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addLinkedFile(chosenSubId, displayName.trim(), mime, uriStr)
                        pendingPickedFile = null
                    },
                    enabled = chosenSubId.isNotBlank() && displayName.trim().isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Link File") }
            },
            dismissButton = { TextButton(onClick = { pendingPickedFile = null }) { Text("Cancel") } },
        )
    }

    // Link / Re-link dialog for Not linked or Missing entries
    linkPromptFile?.let { file ->
        val status = state.fileStatuses[file.id] ?: LocalFileStatus.NOT_LINKED
        AlertDialog(
            onDismissRequest = { linkPromptFile = null },
            containerColor = colors.surface,
            title = {
                Text(
                    text = if (status == LocalFileStatus.MISSING) "File missing on this device" else "Not linked on this device",
                    color = colors.text,
                )
            },
            text = {
                Text(
                    text = if (status == LocalFileStatus.MISSING) {
                        "\"${file.displayName}\" was moved, renamed, or deleted. Pick the file again to re-link it."
                    } else {
                        "\"${file.displayName}\" was added on another device. Pick the local copy on this phone to link it."
                    },
                    color = colors.textDim,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        relinkingFile = file
                        linkPromptFile = null
                        relinkLauncher.launch(arrayOf("*/*"))
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (status == LocalFileStatus.MISSING) "Re-link file" else "Link file")
                }
            },
            dismissButton = { TextButton(onClick = { linkPromptFile = null }) { Text("Cancel") } },
        )
    }

    // Rename dialog
    renamingFile?.let { file ->
        var newName by remember(file.id) { mutableStateOf(file.displayName) }
        AlertDialog(
            onDismissRequest = { renamingFile = null },
            containerColor = colors.surface,
            title = { Text("Rename File Entry", color = colors.text) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(200) },
                    label = { Text("Display name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameFile(file, newName)
                        renamingFile = null
                    },
                    enabled = newName.trim().isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingFile = null }) { Text("Cancel") } },
        )
    }

    // Move subject dialog
    movingFile?.let { file ->
        AlertDialog(
            onDismissRequest = { movingFile = null },
            containerColor = colors.surface,
            title = { Text("Move to Subject", color = colors.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.activeSubjects.forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface2)
                                .clickable {
                                    viewModel.moveFileToSubject(file, sub.id)
                                    movingFile = null
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${sub.emoji} ${sub.name}", color = colors.text)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { movingFile = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(
    entry: LinkedFileEntry,
    status: LocalFileStatus,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalStudyColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val fileIcon = when {
        entry.mime.contains("pdf", ignoreCase = true) || entry.displayName.endsWith(".pdf", true) -> Icons.Outlined.PictureAsPdf
        entry.mime.startsWith("image/", ignoreCase = true) -> Icons.Outlined.Image
        entry.mime.contains("word", ignoreCase = true) || entry.mime.contains("text", ignoreCase = true) -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }

    val (chipLabel, chipColor) = when (status) {
        LocalFileStatus.LINKED -> "Linked" to colors.success
        LocalFileStatus.NOT_LINKED -> "Not linked here" to colors.textDim
        LocalFileStatus.MISSING -> "Missing" to colors.danger
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface2.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag("file_row_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = fileIcon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chipColor.copy(alpha = 0.14f))
                    .border(1.dp, chipColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = chipLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = chipColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "File options", tint = colors.textDim)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Move to subject") },
                    onClick = {
                        menuExpanded = false
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove from list", color = colors.danger) },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

private fun resolveDocumentMeta(context: android.content.Context, uri: Uri): Pair<String, String> {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Linked File"
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val colVal = cursor.getString(idx)
                    if (!colVal.isNullOrBlank()) displayName = colVal
                }
            }
        }
    } catch (_: Exception) {
    }
    return displayName to mime
}

private fun openExternalUri(context: android.content.Context, uriString: String, mime: String): Boolean {
    return try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
