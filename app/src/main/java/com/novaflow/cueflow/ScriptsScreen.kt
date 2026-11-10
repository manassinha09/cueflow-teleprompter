package com.novaflow.cueflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptsScreen(
    library: ScriptLibrary,
    onChange: (ScriptLibrary) -> Unit,
    onBack: () -> Unit,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var markdown by rememberSaveable { mutableStateOf("") }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val dark = isSystemInDarkTheme()
    val deleteColor = if (dark) Color(0xFFFF8580) else Color(0xFFB71C1C)
    val deleteButtonColors = ButtonDefaults.buttonColors(
        containerColor = deleteColor,
        contentColor = if (dark) Color.Black else Color.White,
    )

    fun edit(script: SavedScript?) {
        editingId = script?.id
        title = script?.title.orEmpty()
        markdown = script?.markdown.orEmpty()
        editorOpen = true
    }

    val closeScreen = { if (editorOpen) editorOpen = false else onBack() }
    BackHandler(onBack = closeScreen)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (!editorOpen) "Scripts" else if (editingId == null) "New script" else "Edit script",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = closeScreen) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                actions = {
                    if (editorOpen) {
                        Button(
                            enabled = title.isNotBlank() && markdown.isNotBlank(),
                            onClick = {
                                val script = SavedScript(editingId ?: UUID.randomUUID().toString(), title.trim(), markdown)
                                val scripts = if (editingId == null) library.scripts + script
                                else library.scripts.map { if (it.id == script.id) script else it }
                                onChange(ScriptLibrary(scripts, library.selectedId ?: script.id))
                                editorOpen = false
                            },
                            modifier = Modifier.padding(end = 16.dp),
                        ) { Text("Save script") }
                    } else {
                        Button(onClick = { edit(null) }, modifier = Modifier.padding(end = 16.dp)) { Text("Add script") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val page = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            when {
                editorOpen -> Column(
                    page.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TextField(
                        value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") }, singleLine = true,
                    )
                    TextField(
                        value = markdown, onValueChange = { markdown = it },
                        modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Script") },
                        placeholder = { Text("Paste or write your script. Markdown is supported.") },
                    )
                    if (editingId != null) {
                        Button(
                            onClick = { pendingDeleteId = editingId },
                            colors = deleteButtonColors,
                        ) {
                            Text("Delete script")
                        }
                    }
                }
                library.scripts.isEmpty() -> Column(
                    page.align(Alignment.Center).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScriptIcon()
                    Text("Your script library is empty", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "Add a script to display it in the prompter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    modifier = page,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(library.scripts, key = { it.id }) { script ->
                        ScriptCard(
                            script,
                            selected = script.id == library.selectedId,
                            deleteColor = deleteColor,
                            onDelete = { pendingDeleteId = script.id },
                            onEdit = { edit(script) },
                            onUse = {
                                onChange(library.copy(selectedId = script.id))
                                onBack()
                            },
                        )
                    }
                }
            }
        }
    }

    library.scripts.firstOrNull { it.id == pendingDeleteId }?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete script?") },
            text = { Text("“${script.title}” will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        val remaining = library.scripts.filterNot { it.id == script.id }
                        val selection = if (library.selectedId == script.id) remaining.firstOrNull()?.id else library.selectedId
                        pendingDeleteId = null
                        editorOpen = false
                        onChange(ScriptLibrary(remaining, selection))
                    },
                    colors = deleteButtonColors,
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScriptCard(
    script: SavedScript,
    selected: Boolean,
    deleteColor: Color,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onUse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val preview = remember(script.markdown) { renderPrompterMarkdown(script.markdown).spoken.trim() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (selected) colors.surfaceContainerHigh else colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScriptIcon()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(script.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (selected) {
                        Text("On prompter", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (preview.isNotEmpty()) {
                Text(
                    preview, Modifier.padding(top = 12.dp, end = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        painterResource(R.drawable.ic_delete_script),
                        contentDescription = "Delete ${script.title}",
                        tint = deleteColor,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit") }
                FilledTonalButton(
                    onClick = onUse,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF2B7FFF),
                        contentColor = Color.White,
                    ),
                ) { Text("Use script") }
            }
        }
    }
}

@Composable
private fun ScriptIcon() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_scripts), contentDescription = null)
        }
    }
}
