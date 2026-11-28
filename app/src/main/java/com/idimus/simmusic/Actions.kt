package com.idimus.simmusic

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Multi-select: a long press on any row starts it, taps then toggle rows, and the
 * bottom bar acts on everything picked. Keys are "t<id>", "f<path>", "al<albumId>", "ar<artist>".
 */
class Selection {
    val keys = mutableStateListOf<String>()
    val active get() = keys.isNotEmpty()
    fun toggle(key: String) { if (!keys.remove(key)) keys += key }
    fun clear() = keys.clear()
}

val LocalSelection = staticCompositionLocalOf { Selection() }
val LocalActions = staticCompositionLocalOf<TrackActions?> { null }

/** Everything the ⋮ menus and the selection bar can do with tracks. */
class TrackActions(
    private val context: Context,
    private val vm: MusicViewModel,
    private val launchDelete: (IntentSenderRequest) -> Unit,
    private val launchTree: (Intent) -> Unit,
    val goAlbum: (Long) -> Unit,
    val goArtist: (String) -> Unit,
) {
    var details by mutableStateOf<Track?>(null)
    var pendingDelete by mutableStateOf<List<Track>>(emptyList())
    private var deleting: List<Track> = emptyList()

    // ------------------------------------------------ playlists

    /** Tracks waiting for the "Add to playlist" picker; empty hides it. */
    var addTo by mutableStateOf<List<Track>>(emptyList())
    /** Tracks for a new playlist; null hides the dialog. */
    var newPlaylistFor by mutableStateOf<List<Track>?>(null)
    var deletePlaylistAsk by mutableStateOf<Playlist?>(null)
    /** Folder that must be granted before the last change can be saved; null hides the dialog. */
    var grantNeeded by mutableStateOf<String?>(null)
    /** Bumped when grants change, so the settings list rereads them. */
    var grantsVersion by mutableStateOf(0)
    private var afterGrant: ((String) -> Unit)? = null

    /** Opens the system folder picker; [then] gets the granted folder's path. */
    fun requestGrant(startAt: String?, then: (String) -> Unit = {}) {
        afterGrant = then
        launchTree(Grants.pickerIntent(startAt))
    }

    fun onTreeResult(tree: Uri?) {
        val then = afterGrant
        afterGrant = null
        if (tree == null) return
        runCatching { Grants.take(context, tree) }
        grantsVersion++
        vm.reloadPlaylists()
        Grants.treePath(tree)?.let { then?.invoke(it) }
    }

    fun releaseGrant(tree: Uri) {
        Grants.release(context, tree)
        grantsVersion++
        vm.reloadPlaylists()
    }

    fun confirmGrant() {
        val dir = grantNeeded ?: return
        grantNeeded = null
        launchTree(Grants.pickerIntent(dir))
    }

    private fun toast(res: Int, vararg args: Any) = Toast.makeText(context, context.getString(res, *args), Toast.LENGTH_SHORT).show()

    private fun handle(r: PlaylistStore.Result, retry: () -> Unit, ok: () -> Unit = {}) = when (r) {
        PlaylistStore.Result.Ok -> ok()
        is PlaylistStore.Result.NeedsGrant -> { afterGrant = { retry() }; grantNeeded = r.dir }
        PlaylistStore.Result.Failed -> toast(R.string.playlist_save_failed)
    }

    /** Re-reads the playlist by path, since a retry after a grant may run on a fresh copy. */
    private fun latest(pl: Playlist) = PlaylistStore.find(pl.path) ?: pl

    fun addToPlaylist(pl: Playlist, tracks: List<Track>) {
        addTo = emptyList()
        if (!pl.editable) { toast(R.string.playlist_read_only); return }
        vm.editPlaylists(pl.path, { app -> latest(pl).let { PlaylistStore.write(app, it, it.entries + tracks.map { t -> PlaylistEntry("", t) }) } }) {
            handle(it, { addToPlaylist(latest(pl), tracks) }) { toast(R.string.added_to_playlist, pl.name) }
        }
    }

    fun removeFromPlaylist(pl: Playlist, index: Int) {
        if (!pl.editable) { toast(R.string.playlist_read_only); return }
        vm.editPlaylists(pl.path, { app -> latest(pl).let { PlaylistStore.write(app, it, it.entries.filterIndexed { i, _ -> i != index }) } }) {
            handle(it, { removeFromPlaylist(latest(pl), index) })
        }
    }

    fun createPlaylist(dir: String, name: String, tracks: List<Track>) {
        newPlaylistFor = null
        var created: String? = null
        vm.editPlaylists("$dir/$name.m3u8", { app -> PlaylistStore.create(app, dir, name, tracks).also { created = it.second }.first }) {
            handle(it, { createPlaylist(dir, name, tracks) }) {
                if (tracks.isNotEmpty()) toast(R.string.added_to_playlist, name)
                created?.let { p -> if (p != "$dir/$name.m3u8") vm.forgetPlaylist("$dir/$name.m3u8") }
            }
        }
    }

    fun deletePlaylist(pl: Playlist) {
        deletePlaylistAsk = null
        vm.editPlaylists(null, { app -> PlaylistStore.delete(app, pl) }) {
            handle(it, { deletePlaylist(pl) }) { vm.forgetPlaylist(pl.path) }
        }
    }

    fun share(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val uris = ArrayList(tracks.map { it.uri })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Our own confirmation first; the system then asks for its delete permission. */
    fun confirmDelete() {
        val tracks = pendingDelete
        pendingDelete = emptyList()
        if (tracks.isEmpty()) return
        deleting = tracks
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(resolver, tracks.map { it.uri })
            launchDelete(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            // Android 10 has no batch request: files we may not touch raise a recoverable exception
            // carrying the consent dialog, shown for the first such file.
            val done = mutableListOf<Track>()
            for (t in tracks) {
                try {
                    if (resolver.delete(t.uri, null, null) > 0) done += t
                } catch (e: RecoverableSecurityException) {
                    deleting = listOf(t)
                    launchDelete(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    break
                }
            }
            if (done.isNotEmpty()) onDeleted(done)
        }
    }

    fun onDeleteResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) deleting.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            onDeleted(deleting)
        }
        deleting = emptyList()
    }

    private fun onDeleted(tracks: List<Track>) {
        vm.removeFromQueue(tracks.map { it.id.toString() }.toSet())
        vm.loadLibrary()
    }

    fun setRingtone(t: Track) {
        if (!Settings.System.canWrite(context)) {
            Toast.makeText(context, R.string.ringtone_permission, Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val ok = runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, t.uri) }.isSuccess
        Toast.makeText(context, if (ok) R.string.ringtone_set else R.string.ringtone_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberTrackActions(vm: MusicViewModel, goAlbum: (Long) -> Unit, goArtist: (String) -> Unit): TrackActions {
    val context = LocalContext.current
    var actions: TrackActions? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        actions?.onDeleteResult(it.resultCode)
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        actions?.onTreeResult(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    actions = remember(vm) { TrackActions(context, vm, { launcher.launch(it) }, { treeLauncher.launch(it) }, goAlbum, goArtist) }
    return actions
}

/** Dialogs owned by [TrackActions]; placed once at the app root. */
@Composable
fun TrackActionDialogs(actions: TrackActions, vm: MusicViewModel) {
    PlaylistDialogs(actions, vm)
    actions.details?.let { TrackDetailsDialog(it) { actions.details = null } }
    val pending = actions.pendingDelete
    if (pending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { actions.pendingDelete = emptyList() },
            title = { Text(pluralStringResource(R.plurals.delete_tracks_title, pending.size, pending.size)) },
            text = { Text(stringResource(R.string.delete_tracks_text)) },
            confirmButton = { TextButton({ actions.confirmDelete() }) { Text(stringResource(R.string.delete), color = SevenColors.accent) } },
            dismissButton = { TextButton({ actions.pendingDelete = emptyList() }) { Text(stringResource(R.string.cancel), color = SevenColors.text()) } },
            containerColor = SevenColors.card(),
            shape = RoundedCornerShape(26.dp),
        )
    }
}

/**
 * The per-track ⋮ menu: details, jump to album or artist, share,
 * delete, set as ringtone. `extra` lets the queue add "Remove from queue" on top.
 */
@Composable
fun TrackMenuButton(track: Track, extra: List<Pair<Int, () -> Unit>> = emptyList(), afterJump: () -> Unit = {}) {
    val actions = LocalActions.current ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options), tint = SevenColors.secondaryText()) }
        DropdownMenu(open, { open = false }, containerColor = SevenColors.card(), shape = RoundedCornerShape(20.dp)) {
            @Composable fun item(res: Int, action: () -> Unit) =
                DropdownMenuItem({ Text(stringResource(res), color = SevenColors.text(), fontSize = 16.sp) }, { open = false; action() })
            extra.forEach { (res, action) -> item(res, action) }
            item(R.string.add_to_playlist) { actions.addTo = listOf(track) }
            item(R.string.track_details) { actions.details = track }
            item(R.string.go_to_album) { afterJump(); actions.goAlbum(track.albumId) }
            item(R.string.go_to_artist) { afterJump(); actions.goArtist(track.artist) }
            item(R.string.share) { actions.share(listOf(track)) }
            item(R.string.set_as_ringtone) { actions.setRingtone(track) }
            item(R.string.delete) { actions.pendingDelete = listOf(track) }
        }
    }
}

/**
 * Leading checkbox while selecting. It grows in from the start edge so the row content slides
 * right instead of jumping.
 */
@Composable
fun RowScope.SelectSlot(active: Boolean, selected: Boolean) {
    AnimatedVisibility(
        active,
        enter = expandHorizontally(tween(SELECT_ANIM_MS), expandFrom = Alignment.Start) + fadeIn(tween(SELECT_ANIM_MS)),
        exit = shrinkHorizontally(tween(SELECT_ANIM_MS), shrinkTowards = Alignment.Start) + fadeOut(tween(SELECT_ANIM_MS)),
    ) { SelectCheck(selected) }
}

/** Row end content (⋮, play button) that fades away while selecting. */
@Composable
fun RowScope.TrailingSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(SELECT_ANIM_MS)) + expandHorizontally(tween(SELECT_ANIM_MS)),
        exit = fadeOut(tween(SELECT_ANIM_MS)) + shrinkHorizontally(tween(SELECT_ANIM_MS)),
    ) { content() }
}

const val SELECT_ANIM_MS = 250

@Composable
private fun SelectCheck(selected: Boolean) {
    val fill by animateColorAsState(if (selected) SevenColors.accent else Color.Transparent, tween(150), label = "checkFill")
    val ring by animateColorAsState(if (selected) SevenColors.accent else SevenColors.secondaryText(), tween(150), label = "checkRing")
    val tick by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "checkTick")
    Box(
        Modifier.padding(end = 12.dp).size(24.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp).scale(tick), tint = SevenColors.onAccent) }
}

/** Replaces the mini player and tabs while selecting: count, close, then Play, Share, Delete. */
@Composable
fun SelectionBar(count: Int, onClose: () -> Unit, onPlay: () -> Unit, onAdd: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SevenColors.canvas()).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = SevenColors.text()) }
            Text(pluralStringResource(R.plurals.n_selected, count, count), color = SevenColors.text(), fontSize = 17.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.Rounded.PlayArrow, R.string.play_all, onPlay)
            BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, R.string.add_to_playlist_short, onAdd)
            BarAction(Icons.Rounded.Share, R.string.share, onShare)
            BarAction(Icons.Rounded.Delete, R.string.delete, onDelete)
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = SevenColors.text())
        Text(stringResource(label), color = SevenColors.text(), fontSize = 12.sp)
    }
}

@Composable
private fun TrackDetailsDialog(t: Track, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember(t.id) { fileInfo(context, t) }
    val rows = listOf(
        R.string.detail_title to t.title,
        R.string.detail_artist to t.artist.ifEmpty { context.getString(R.string.unknown_artist) },
        R.string.detail_album to t.album.ifEmpty { context.getString(R.string.unknown_album) },
        R.string.detail_duration to formatDuration(t.durationMs),
        R.string.detail_format to info.format,
        R.string.detail_bitrate to info.bitrate,
        R.string.detail_size to info.size,
        R.string.detail_path to "${t.dir}/${t.fileName}",
    ).filter { it.second.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_details)) },
        text = {
            Column {
                rows.forEach { (label, value) ->
                    Text(stringResource(label), color = SevenColors.secondaryText(), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(value, color = SevenColors.text(), fontSize = 15.sp)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok), color = SevenColors.accent) } },
        containerColor = SevenColors.card(),
        shape = RoundedCornerShape(26.dp),
    )
}

private class FileInfo(val format: String, val bitrate: String, val size: String)

private fun fileInfo(context: Context, t: Track): FileInfo {
    var mime = ""
    var bytes = 0L
    context.contentResolver.query(t.uri, arrayOf(MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) { mime = it.getString(0).orEmpty(); bytes = it.getLong(1) }
    }
    val kbps = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(context, t.uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.div(1000) }
            finally { release() }
        }
    }.getOrNull()
    val ext = t.fileName.substringAfterLast('.', "").uppercase()
    return FileInfo(
        format = ext.ifEmpty { mime },
        bitrate = kbps?.let { "$it kbps" }.orEmpty(),
        size = if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else "",
    )
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
