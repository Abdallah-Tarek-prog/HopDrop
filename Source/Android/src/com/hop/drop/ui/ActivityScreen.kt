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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.res.stringResource
import com.hop.drop.R
import com.hop.drop.core.Format
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class Filter(val label: Int) { All(R.string.ui_all), Received(R.string.ui_received), Sent(R.string.ui_sent) }

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.ui_today)
        today.minusDays(1) -> stringResource(R.string.ui_yesterday)
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
    val history = state.history
    val shown = remember(history, query, filter) {
        val q = query.trim().lowercase(Locale.getDefault())
        history.filter { e ->
            (filter == Filter.All || (filter == Filter.Received) == e.incoming) &&
                (q.isEmpty() || e.peer.lowercase(Locale.getDefault()).contains(q) ||
                    e.text?.lowercase(Locale.getDefault())?.contains(q) == true ||
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
            item(key = "now") { SectionHeader(stringResource(R.string.ui_now), Modifier.padding(top = 0.dp)) }
            items(state.live, key = { "live:" + it.key }) { LiveCard(it, actions::cancel, Modifier.animateItem()) }
        }
        if (history.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.History, stringResource(R.string.ui_nothing_here_yet), stringResource(R.string.ui_files_you_send_and_receive_show_up_here))
            }
        } else {
            item(key = "search") { SearchAndFilter(query, { query = it }, filter) { filter = it } }
            if (shown.isEmpty()) {
                item(key = "no-match") { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.ui_no_matches)) }
            }
            groups.forEach { (day, entries) ->
                stickyHeader(key = "day:$day") {
                    Text(
                        dayLabel(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                items(entries, key = { it.key }) { entry ->
                    if (entry.text != null) TextCard(
                        entry, expanded = entry.key in expanded,
                        onToggle = { expanded = if (entry.key in expanded) expanded - entry.key else expanded + entry.key },
                        onLongPress = { menuFor = entry }, menuOpen = menuFor?.key == entry.key,
                        onMenuDismiss = { menuFor = null }, actions = actions, modifier = Modifier.animateItem(),
                    ) else HistoryCard(
                        entry = entry,
                        expanded = entry.key in expanded,
                        onToggle = { expanded = if (entry.key in expanded) expanded - entry.key else expanded + entry.key },
                        onLongPress = { menuFor = entry },
                        menuOpen = menuFor?.key == entry.key, onMenuDismiss = { menuFor = null },
                        actions = actions,
                        onShowAll = { state.allFilesOf = entry },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilter(query: String, onQuery: (String) -> Unit, filter: Filter, onFilter: (Filter) -> Unit) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.ui_search_files_or_devices)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_clear_search)) } }) else null,
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
            for (f in Filter.entries) FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(stringResource(f.label)) })
        }
    }
}

@Composable
private fun statusLine(e: HistoryUi): String = when {
    e.ok -> filesText(e.count) + "  ·  " + sizeText(e.bytes)
    e.cancelled -> stringResource(R.string.ui_cancelled) + if (e.offered > 0)
        "  ·  " + stringResource(R.string.count_of_files, e.count, e.offered.toString()) else ""
    else -> e.problem ?: stringResource(R.string.ui_stopped)
}

@Composable
private fun transportLabel(transport: String): String = stringResource(when (transport) {
    "local_network" -> R.string.transport_local_network
    "direct_link" -> R.string.transport_direct_link
    "bluetooth" -> R.string.transport_bluetooth
    "mixed" -> R.string.transport_mixed
    else -> R.string.transport_unknown
})

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryUi, expanded: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit, actions: HopActions,
    onShowAll: () -> Unit, menuOpen: Boolean, onMenuDismiss: () -> Unit, modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    HopCard(modifier, onClick = onToggle, onLongClick = onLongPress, padding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HistoryLeading(entry)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(if (entry.incoming) R.string.history_from else R.string.history_to, ltr(entry.peer)), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortSummary(entry.files.map { it.name }), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(entry), style = MaterialTheme.typography.bodySmall,
                    color = if (entry.ok) scheme.onSurfaceVariant else if (entry.cancelled) scheme.onSurfaceVariant else scheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(transportLabel(entry.transport), style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.at)), style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant)
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (expanded) stringResource(R.string.ui_collapse) else stringResource(R.string.ui_expand),
                    tint = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).rotate(chevron))
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HistoryDetails(entry, actions, onShowAll)
        }
        HistoryMenu(entry, actions, menuOpen, onMenuDismiss)
    }
}

/** The whole text when it is one web link, else null. */
private fun singleLink(text: String): String? = text.trim().takeIf { Regex("(?i)^https?://\\S+$").matches(it) }

/** A text message: who, the text itself (three lines, all of it when opened), and Copy / Open link / Share. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextCard(entry: HistoryUi, expanded: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit,
    menuOpen: Boolean, onMenuDismiss: () -> Unit, actions: HopActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val text = entry.text ?: return
    val copyLabel = stringResource(R.string.ui_text)
    HopCard(modifier, onClick = onToggle, onLongClick = onLongPress, padding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (entry.incoming) scheme.primaryContainer else scheme.secondaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null,
                    tint = if (entry.incoming) scheme.onPrimaryContainer else scheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(if (entry.incoming) R.string.history_text_from else R.string.history_text_to, ltr(entry.peer)), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                } else Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(transportLabel(entry.transport), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.at)), style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant)
        }
        Row(Modifier.padding(start = 56.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.copy(copyLabel, text) }) { Text(stringResource(R.string.ui_copy)) }
            singleLink(text)?.let { link -> TextButton(onClick = { actions.openLink(link) }) { Text(stringResource(R.string.ui_open_link)) } }
            TextButton(onClick = { actions.shareText(text) }) { Text(stringResource(R.string.ui_share)) }
        }
        HistoryMenu(entry, actions, menuOpen, onMenuDismiss)
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
                Icon(icon, contentDescription = if (entry.incoming) stringResource(R.string.ui_received) else stringResource(R.string.ui_sent), tint = fg, modifier = Modifier.size(24.dp))
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
            TextButton(onClick = onShowAll) { Text(stringResource(R.string.show_all_count, filesText(entry.count))) }
        }
        val facts = mutableListOf<String>()
        if (entry.ok && entry.millis > 0) facts.add(stringResource(R.string.history_size_in,
            sizeText(entry.bytes), durationText(maxOf(1, entry.millis / 1000))))
        if (!entry.folder.isNullOrEmpty()) facts.add(stringResource(if (entry.incoming) R.string.history_saved_in else R.string.history_saved_in_on,
            ltr(entry.folder), ltr(entry.peer)))
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
                    Text(stringResource(R.string.ui_share))
                }
            }
            TextButton(onClick = { actions.removeFromHistory(entry) }) { Text(stringResource(R.string.ui_remove_from_list)) }
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

@Composable
private fun HistoryMenu(entry: HistoryUi, actions: HopActions, expanded: Boolean, onDismiss: () -> Unit) {
    val message = entry.text
    val copyLabel = stringResource(R.string.ui_text)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (message != null) DropdownMenuItem(text = { Text(stringResource(R.string.ui_copy)) }, onClick = {
            onDismiss(); actions.copy(copyLabel, message)
        })
        if (entry.files.any { it.uri != null } || message != null) DropdownMenuItem(text = { Text(stringResource(R.string.ui_share)) }, onClick = {
            onDismiss()
            if (message != null) actions.shareText(message) else actions.share(entry.files)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_remove_from_list)) }, onClick = { onDismiss(); actions.removeFromHistory(entry) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllFilesScreen(entry: HistoryUi, actions: HopActions, onDismiss: () -> Unit) {
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(if (entry.incoming) R.string.history_from else R.string.history_to, ltr(entry.peer))) },
        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_back)) } },
    ) }) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 32.dp)) {
            item { Text(
                if (entry.count > entry.files.size) stringResource(R.string.showing_count, entry.files.size, entry.count.toString())
                else filesText(entry.count),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            ) }
            items(entry.files) { FileRow(it, actions) }
        }
    }
}
