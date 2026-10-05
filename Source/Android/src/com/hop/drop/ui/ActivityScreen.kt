package com.hop.drop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hop.drop.core.Format
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class Filter(val label: String) { All("All"), Received("Received"), Sent("Sent") }

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
            SimpleDateFormat(if (day.year == today.year) "EEEE, MMM d" else "MMM d, yyyy", Locale.getDefault()).format(date)
        }
    }
}

private fun dayOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(state: HopUiState, actions: HopActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<HistoryUi?>(null) }
    var allFilesOf by remember { mutableStateOf<HistoryUi?>(null) }
    val history = state.history
    val shown = remember(history, query, filter) {
        val q = query.trim().lowercase(Locale.getDefault())
        history.filter { e ->
            (filter == Filter.All || (filter == Filter.Received) == e.incoming) &&
                (q.isEmpty() || e.peer.lowercase(Locale.getDefault()).contains(q) ||
                    e.files.any { it.name.lowercase(Locale.getDefault()).contains(q) })
        }
    }
    val groups = remember(shown) { shown.groupBy { dayOf(it.at) } }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.live.isNotEmpty()) {
            item(key = "now") { SectionHeader("Now", Modifier.padding(top = 0.dp)) }
            items(state.live, key = { "live:" + it.key }) { LiveCard(it, actions::cancel, Modifier.animateItem()) }
        }
        if (history.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.History, "Nothing here yet", "Files you send and receive show up here.")
            }
        } else {
            item(key = "search") { SearchAndFilter(query, { query = it }, filter) { filter = it } }
            if (shown.isEmpty()) {
                item(key = "no-match") { EmptyState(Icons.Rounded.SearchOff, "No matches") }
            }
            groups.forEach { (day, entries) ->
                stickyHeader(key = "day:$day") {
                    Text(
                        dayLabel(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                items(entries, key = { it.key }) { entry ->
                    HistoryCard(
                        entry = entry,
                        expanded = entry.key in expanded,
                        onToggle = { expanded = if (entry.key in expanded) expanded - entry.key else expanded + entry.key },
                        onLongPress = { menuFor = entry },
                        actions = actions,
                        onShowAll = { allFilesOf = entry },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    menuFor?.let { entry -> HistoryMenu(entry, actions) { menuFor = null } }
    allFilesOf?.let { entry -> AllFilesSheet(entry, actions) { allFilesOf = null } }
}

@Composable
private fun SearchAndFilter(query: String, onQuery: (String) -> Unit, filter: Filter, onFilter: (Filter) -> Unit) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search files or devices") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } }) else null,
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in Filter.entries) FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
        }
    }
}

private fun statusLine(e: HistoryUi): String = when {
    e.ok -> Format.files(e.count) + "  ·  " + Format.size(e.bytes)
    e.cancelled -> "Cancelled" + if (e.offered > 0) "  ·  ${e.count} of ${Format.files(e.offered)}" else ""
    else -> e.problem ?: "Stopped"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryUi, expanded: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit, actions: HopActions,
    onShowAll: () -> Unit, modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    HopCard(modifier, onClick = onToggle, onLongClick = onLongPress, padding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HistoryLeading(entry)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text((if (entry.incoming) "From " else "To ") + entry.peer, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortSummary(entry.files.map { it.name }), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(entry), style = MaterialTheme.typography.bodySmall,
                    color = if (entry.ok) scheme.onSurfaceVariant else if (entry.cancelled) scheme.onSurfaceVariant else scheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.at)), style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant)
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).rotate(chevron))
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HistoryDetails(entry, actions, onShowAll)
        }
    }
}

/** A photo from the transfer when there is one, otherwise a direction badge. */
@Composable
private fun HistoryLeading(entry: HistoryUi) {
    val scheme = MaterialTheme.colorScheme
    val photo = entry.files.firstOrNull { it.uri != null && fileKind(it.name).let { k -> k == FileKind.Image || k == FileKind.Video } }
    val (bg, fg) = when {
        !entry.ok && !entry.cancelled -> scheme.errorContainer to scheme.onErrorContainer
        entry.incoming -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val icon = when {
        !entry.ok && !entry.cancelled -> Icons.Rounded.ErrorOutline
        entry.incoming -> Icons.Rounded.Download
        else -> Icons.Rounded.Upload
    }
    Box(Modifier.size(48.dp)) {
        val badge: @Composable () -> Unit = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = if (entry.incoming) "Received" else "Sent", tint = fg, modifier = Modifier.size(24.dp))
            }
        }
        if (photo?.uri != null) {
            Thumbnail(photo.uri, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)), badge)
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(scheme.surfaceContainerLow).padding(2.dp)
                    .clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp)) }
        } else badge()
    }
}

@Composable
private fun HistoryDetails(entry: HistoryUi, actions: HopActions, onShowAll: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        HorizontalDivider(color = scheme.outlineVariant)
        Spacer(Modifier.height(6.dp))
        val limit = 8
        for (file in entry.files.take(limit)) FileRow(file, actions)
        if (entry.files.size > limit || entry.count > entry.files.size) {
            TextButton(onClick = onShowAll) { Text("Show all " + Format.files(entry.count)) }
        }
        val facts = buildList {
            if (entry.ok && entry.millis > 0) add(Format.size(entry.bytes) + " in " + Format.duration(maxOf(1, entry.millis / 1000)))
            if (!entry.folder.isNullOrEmpty()) add("Saved in " + entry.folder + if (entry.incoming) "" else " on " + entry.peer)
        }
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
        if (!entry.ok && entry.problem != null) {
            Text(entry.problem, style = MaterialTheme.typography.bodySmall, color = if (entry.cancelled) scheme.onSurfaceVariant else scheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.files.any { it.uri != null }) {
                OutlinedButton(onClick = { actions.share(entry.files) }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
            TextButton(onClick = { actions.removeFromHistory(entry) }) { Text("Remove from list") }
        }
    }
}

/** One file of a transfer: tap to open it, long-press to share it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: HistoryFile, actions: HopActions) {
    val slash = file.name.lastIndexOf('/')
    val base = if (slash < 0) file.name else file.name.substring(slash + 1)
    val folder = if (slash < 0) null else file.name.substring(0, slash)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = { actions.open(file) }, onLongClick = { actions.share(listOf(file)) })
            .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(base, file.uri, size = 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(base, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (folder != null) Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryMenu(entry: HistoryUi, actions: HopActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text((if (entry.incoming) "From " else "To ") + entry.peer, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp))
            Text(statusLine(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (entry.files.any { it.uri != null }) {
                ListItem(
                    headlineContent = { Text("Share") },
                    leadingContent = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.combinedClickable(onClick = { onDismiss(); actions.share(entry.files) }),
                )
            }
            ListItem(
                headlineContent = { Text("Remove from list") },
                supportingContent = { Text("The files stay where they are") },
                leadingContent = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.combinedClickable(onClick = { onDismiss(); actions.removeFromHistory(entry) }),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllFilesSheet(entry: HistoryUi, actions: HopActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text((if (entry.incoming) "From " else "To ") + entry.peer, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            if (entry.count > entry.files.size) "Showing ${String.format(Locale.getDefault(), "%,d", entry.files.size)} of ${Format.files(entry.count)}"
            else Format.files(entry.count),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            items(entry.files) { FileRow(it, actions) }
        }
    }
}
