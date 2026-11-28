package com.idimus.simmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Path as file managers show it: the internal storage root is dropped. */
fun displayPath(path: String): String = path.removePrefix("/storage/emulated/0/").removePrefix("/storage/emulated/0").removePrefix("/storage/")

@Composable
fun PlaylistDialogs(actions: TrackActions, vm: MusicViewModel) {
    if (actions.addTo.isNotEmpty()) AddToPlaylistDialog(actions, vm)
    actions.newPlaylistFor?.let { NewPlaylistDialog(actions, vm, it) }
    actions.deletePlaylistAsk?.let { pl ->
        SimpleDialog(
            title = stringResource(R.string.delete_playlist_title, pl.name),
            text = stringResource(R.string.delete_playlist_text),
            confirm = stringResource(R.string.delete), onConfirm = { actions.deletePlaylist(pl) },
            onDismiss = { actions.deletePlaylistAsk = null },
        )
    }
    actions.grantNeeded?.let { dir ->
        SimpleDialog(
            title = stringResource(R.string.grant_folder_title),
            text = stringResource(R.string.grant_folder_text, displayPath(dir)),
            confirm = stringResource(R.string.choose_folder), onConfirm = { actions.confirmGrant() },
            onDismiss = { actions.grantNeeded = null },
        )
    }
}

@Composable
private fun SimpleDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = SevenColors.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = SevenColors.text()) } },
        containerColor = SevenColors.card(),
        shape = RoundedCornerShape(26.dp),
    )
}

/** "New playlist", then the recent ones, a separator, and every editable playlist A-Z (recent ones included). */
@Composable
private fun AddToPlaylistDialog(actions: TrackActions, vm: MusicViewModel) {
    val tracks = actions.addTo
    val editable = vm.playlists.filter { it.editable }
    val recent = vm.recentPlaylists.mapNotNull { p -> editable.firstOrNull { it.path == p } }
    AlertDialog(
        onDismissRequest = { actions.addTo = emptyList() },
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickRow(Icons.Rounded.Add, stringResource(R.string.new_playlist), null, accent = true) {
                    actions.addTo = emptyList(); actions.newPlaylistFor = tracks
                }
                if (recent.isNotEmpty()) {
                    SectionLabel(R.string.recent)
                    recent.forEach { pl -> PlaylistPickRow(pl) { actions.addToPlaylist(pl, tracks) } }
                }
                if (editable.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SevenColors.divider())
                    SectionLabel(R.string.all_playlists)
                    editable.forEach { pl -> PlaylistPickRow(pl) { actions.addToPlaylist(pl, tracks) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ actions.addTo = emptyList() }) { Text(stringResource(R.string.cancel), color = SevenColors.text()) } },
        containerColor = SevenColors.card(),
        shape = RoundedCornerShape(26.dp),
    )
}

@Composable
private fun SectionLabel(res: Int) = Text(
    stringResource(res), color = SevenColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
)

@Composable
private fun PlaylistPickRow(pl: Playlist, onClick: () -> Unit) =
    PickRow(Icons.AutoMirrored.Rounded.QueueMusic, pl.name, pluralStringResource(R.plurals.n_tracks, pl.tracks.size, pl.tracks.size), onClick = onClick)

@Composable
private fun PickRow(icon: ImageVector, title: String, subtitle: String?, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(SevenColors.artPlaceholder()), Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = SevenColors.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (accent) SevenColors.accent else SevenColors.text(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = SevenColors.secondaryText(), fontSize = 13.sp)
        }
    }
}

/**
 * Name plus target folder. The default is "Playlists" inside the first granted folder; with no
 * folder granted yet, Create opens the folder picker and saves into the folder chosen there.
 */
@Composable
private fun NewPlaylistDialog(actions: TrackActions, vm: MusicViewModel, tracks: List<Track>) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    val defaultDir = remember(actions.grantsVersion) { Grants.trees(context).firstNotNullOfOrNull(Grants::treePath)?.let { "$it/Playlists" } }
    var dir by remember(defaultDir) { mutableStateOf(defaultDir) }
    val clean = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "")
    val exists = dir != null && vm.playlists.any { it.path.equals("$dir/$clean.m3u8", ignoreCase = true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { actions.newPlaylistFor = null },
        title = { Text(stringResource(R.string.new_playlist)) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    label = { Text(stringResource(R.string.playlist_name)) },
                    isError = exists,
                    supportingText = if (exists) { { Text(stringResource(R.string.playlist_exists)) } } else null,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SevenColors.accent, focusedLabelColor = SevenColors.accent, cursorColor = SevenColors.accent),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.save_in), color = SevenColors.secondaryText(), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                PickRow(Icons.Rounded.Folder, dir?.let(::displayPath) ?: stringResource(R.string.choose_folder), null, accent = dir == null) {
                    actions.requestGrant(dir) { picked -> dir = picked }
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    val d = dir
                    if (d == null) actions.requestGrant(null) { picked -> actions.createPlaylist(picked, clean, tracks) }
                    else actions.createPlaylist(d, clean, tracks)
                },
                enabled = clean.isNotEmpty() && !exists,
            ) { Text(stringResource(R.string.create), color = if (clean.isNotEmpty() && !exists) SevenColors.accent else SevenColors.secondaryText()) }
        },
        dismissButton = { TextButton({ actions.newPlaylistFor = null }) { Text(stringResource(R.string.cancel), color = SevenColors.text()) } },
        containerColor = SevenColors.card(),
        shape = RoundedCornerShape(26.dp),
    )
}
