package dev.junjo.eztranscribe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.junjo.eztranscribe.history.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the history screen can change. */
data class HistoryActions(
    val delete: (id: String) -> Unit,
    val clear: () -> Unit,
)

/**
 * Every transcript kept on the phone, newest first. Notes leave the main screen when the app is
 * closed; their transcripts stay here until deleted, one at a time or all at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    actions: HistoryActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all transcripts?") },
            text = {
                val what = if (entries.size == 1) "The only transcript" else "All ${entries.size} transcripts"
                Text("$what will be deleted from this phone. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.clear()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this transcript?") },
            text = { Text(friendlyTitle(entry.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    actions.delete(entry.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete all")
                        }
                    }
                },
            )
        },
    ) { insets ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(insets).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Transcripts land here and stay on this phone until you delete them.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                HistoryCard(entry, onDelete = { pendingDelete = entry })
            }
        }
    }
}

/** One transcript: four lines until tapped, then all of it. */
@Composable
private fun HistoryCard(entry: HistoryEntry, onDelete: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(friendlyTitle(entry.fileName), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    WHEN_FORMAT.format(Instant.ofEpochMilli(entry.createdAt)),
                    entry.durationMs?.let(::formatClock),
                    entry.engineLabel,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            val text = entry.text.ifBlank { "No speech recognised in this note." }
            if (expanded) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = true },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { copyToClipboard(context, entry.text) }, enabled = entry.text.isNotBlank()) {
                    Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Copy")
                }
                FilledTonalButton(onClick = { shareText(context, entry.text) }, enabled = entry.text.isNotBlank()) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(AppIcons.Delete, contentDescription = "Delete") }
            }
        }
    }
}

private val WHEN_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
