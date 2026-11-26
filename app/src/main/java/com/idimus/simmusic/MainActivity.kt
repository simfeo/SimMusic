package com.idimus.simmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Accent.load(this)
        setContent { SevenTheme { App() } }
    }
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private sealed interface Screen {
    data class FolderView(val path: String, val flat: Boolean) : Screen
    data object Settings : Screen
    data class AlbumView(val id: Long) : Screen
    data class ArtistView(val name: String) : Screen
    data object Search : Screen
}

@Composable
private fun App(vm: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[audioPermission] == true
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            val perms = buildList {
                add(audioPermission)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(perms.toTypedArray())
        }
    }
    LaunchedEffect(granted) { if (granted) vm.loadLibrary() }

    val stack = remember { mutableStateListOf<Screen>() }
    var tab by rememberSaveable { mutableIntStateOf(3) }
    var playerOpen by remember { mutableStateOf(false) }

    val selection = remember { Selection() }
    val actions = rememberTrackActions(
        vm,
        goAlbum = { id -> selection.clear(); playerOpen = false; stack += Screen.AlbumView(id) },
        goArtist = { name -> selection.clear(); playerOpen = false; stack += Screen.ArtistView(name) },
    )
    // Leaving a screen or switching tabs ends a selection.
    LaunchedEffect(stack.size, tab) { selection.clear() }

    BackHandler(playerOpen || stack.isNotEmpty() || selection.active) {
        when {
            playerOpen -> playerOpen = false
            selection.active -> selection.clear()
            else -> stack.removeAt(stack.lastIndex)
        }
    }

    CompositionLocalProvider(LocalSelection provides selection, LocalActions provides actions) {
    TrackActionDialogs(actions)
    Box(Modifier.fillMaxSize().background(SevenColors.canvas())) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
          Box(Modifier.weight(1f)) {
            val lib = vm.library
            when {
                !granted -> PermissionPrompt { launcher.launch(arrayOf(audioPermission)) }
                lib == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> when (val s = stack.lastOrNull()) {
                    null -> Home(lib, vm, tab, { tab = it }, { stack += it })
                    Screen.Settings -> SettingsScreen(vm) { stack.removeAt(stack.lastIndex) }
                    is Screen.FolderView -> FolderScreen(lib, s.path, s.flat, vm, { stack.removeAt(stack.lastIndex) }) { stack += it }
                    is Screen.AlbumView -> lib.albums.firstOrNull { it.id == s.id }?.let { a ->
                        TrackListScreen(a.name, a.artist, a.tracks, false, vm) { stack.removeAt(stack.lastIndex) }
                    } ?: PopWhenGone(stack)
                    is Screen.ArtistView -> lib.artists.firstOrNull { it.name == s.name }?.let { a ->
                        TrackListScreen(a.name.ifEmpty { stringResource(R.string.unknown_artist) }, "", a.tracks, true, vm) {
                            stack.removeAt(stack.lastIndex)
                        }
                    } ?: PopWhenGone(stack)
                    Screen.Search -> SearchScreen(lib, vm, { stack.removeAt(stack.lastIndex) }) { stack += it }
                }
            }
          }
            val lib = vm.library
            // Keeps the last count while the bar slides away, so it never flashes "0 selected".
            var shownCount by remember { mutableIntStateOf(1) }
            if (selection.keys.isNotEmpty()) shownCount = selection.keys.size
            AnimatedContent(
                selection.active && lib != null,
                transitionSpec = {
                    (slideInVertically(tween(SELECT_ANIM_MS)) { it } + fadeIn(tween(SELECT_ANIM_MS))) togetherWith
                        (slideOutVertically(tween(SELECT_ANIM_MS)) { it } + fadeOut(tween(SELECT_ANIM_MS)))
                },
                label = "bottomBar",
            ) { selecting ->
                if (selecting && lib != null) {
                    val picked = { resolveSelection(selection.keys, lib, vm) }
                    SelectionBar(
                        shownCount, onClose = { selection.clear() },
                        onPlay = { vm.play(picked()); selection.clear() },
                        onShare = { actions.share(picked()) },
                        onDelete = { actions.pendingDelete = picked() },
                    )
                } else {
                    Column {
                        // Mini player sits directly on top of the bottom tab bar, both in thumb reach.
                        if (vm.current != null) MiniPlayer(vm, Modifier) { playerOpen = true }
                        BottomTabs(tab) { stack.clear(); tab = it }
                    }
                }
            }
        }
        AnimatedVisibility(playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            FullPlayer(vm) { playerOpen = false }
        }
    }
    }
}

/** An album, artist or folder screen whose content was deleted closes itself instead of staying blank. */
@Composable
private fun PopWhenGone(stack: MutableList<Screen>) {
    LaunchedEffect(Unit) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

/** Tracks behind the selected rows, in selection order, without duplicates. */
private fun resolveSelection(keys: List<String>, lib: Library, vm: MusicViewModel): List<Track> = keys.flatMap { k ->
    when {
        k.startsWith("t") -> listOfNotNull(k.drop(1).toLongOrNull()?.let { lib.byId[it] })
        k.startsWith("al") -> lib.albums.firstOrNull { it.id.toString() == k.drop(2) }?.tracks.orEmpty()
        k.startsWith("ar") -> lib.artists.firstOrNull { it.name == k.drop(2) }?.tracks.orEmpty()
        // Flat-view folders hold only their own tracks; tree folders follow the subfolder setting.
        k.startsWith("f") -> lib.folder(k.drop(1))?.let { if (vm.folderTree && vm.includeSubfolders) it.allTracks() else it.tracks }.orEmpty()
        else -> emptyList()
    }
}.distinctBy { it.id }

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.permission_needed), color = SevenColors.text(), fontSize = 17.sp)
        Spacer(Modifier.height(16.dp))
        Button(onGrant) { Text(stringResource(R.string.grant)) }
    }
}

// ---------------------------------------------------------------- Home

private val tabs = listOf(
    R.string.tab_tracks to Icons.Rounded.MusicNote,
    R.string.tab_albums to Icons.Rounded.Album,
    R.string.tab_artists to Icons.Rounded.Person,
    R.string.tab_folders to Icons.Rounded.Folder,
    R.string.tab_playlists to Icons.AutoMirrored.Rounded.QueueMusic,
)

@Composable
private fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(SevenColors.canvas()).navigationBarsPadding().heightIn(min = 60.dp).padding(horizontal = 10.dp)) {
        tabs.forEachIndexed { i, (res, icon) ->
            val color = if (tab == i) SevenColors.text() else SevenColors.secondaryText()
            Column(
                Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).clickable { onTab(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, Modifier.size(24.dp), tint = color)
                Text(stringResource(res), color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun Home(lib: Library, vm: MusicViewModel, tab: Int, onTab: (Int) -> Unit, open: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(tabs[tab].first), fontSize = 34.sp, fontWeight = FontWeight.Medium, color = SevenColors.text(), modifier = Modifier.weight(1f))
            IconButton({ open(Screen.Search) }) { Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = SevenColors.text()) }
            IconButton({ open(Screen.Settings) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = SevenColors.text()) }
        }
        when (tab) {
            0 -> CardList(remember(lib) { lib.tracks.map { it.title } }, 1) { tracksSection(lib.tracks, vm, withHeader = true) }
            1 -> CardList(remember(lib) { lib.albums.map { it.name } }) {
                items(lib.albums, key = { it.id }) { a ->
                    Row2(a.tracks.first().id, a.name.ifEmpty { stringResource(R.string.unknown_album) }, a.artist, selectKey = "al${a.id}") { open(Screen.AlbumView(a.id)) }
                }
            }
            2 -> CardList(remember(lib) { lib.artists.map { it.name } }) {
                items(lib.artists, key = { it.name }) { a ->
                    Row2(a.tracks.first().id, a.name.ifEmpty { stringResource(R.string.unknown_artist) },
                        pluralStringResource(R.plurals.n_tracks, a.tracks.size, a.tracks.size), round = true, selectKey = "ar${a.name}") { open(Screen.ArtistView(a.name)) }
                }
            }
            3 -> if (vm.folderTree) {
                CardList(remember(lib) { folderLabels(lib.browseRoot) }) { folderContents(lib.browseRoot, vm) { open(Screen.FolderView(it.path, flat = false)) } }
            } else {
                val folders = remember(lib, vm.folderSortByPath) { lib.musicFoldersSorted(vm.folderSortByPath) }
                CardList(remember(folders) { folders.map { if (vm.folderSortByPath) displayPath(it.path) else it.name } }) {
                    items(folders, key = { "ff" + it.path }) { f -> FlatFolderRow(f, vm) { open(Screen.FolderView(f.path, flat = true)) } }
                }
            }
            else -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.TopCenter) {
                Text(stringResource(R.string.playlists_soon), color = SevenColors.secondaryText())
            }
        }
    }
}

/** `indexLabels[i]` is the sort label of lazy item `indexOffset + i`; empty means no index bar. */
@Composable
private fun CardList(indexLabels: List<String> = emptyList(), indexOffset: Int = 0, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val alphabet = stringResource(R.string.index_chars)
    val index = remember(indexLabels, alphabet) { sections(indexLabels, alphabet, indexOffset) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            // 30dp kept clear at the end so rows never run under the letter bar.
            Modifier.fillMaxSize().padding(start = 12.dp, end = if (index.isNotEmpty()) 30.dp else 12.dp),
            state = state,
            contentPadding = PaddingValues(bottom = 16.dp),
            content = content,
        )
        IndexBar(index, state, Modifier.align(Alignment.TopEnd))
    }
}

private fun folderLabels(f: Folder): List<String> = f.subfolders.values.map { it.name } + f.tracks.map { it.fileName }

private fun LazyListScope.tracksSection(tracks: List<Track>, vm: MusicViewModel, withHeader: Boolean) {
    if (withHeader) item { PlayShuffleBar(tracks.size, { vm.play(tracks) }, { vm.play(tracks, shuffled = true) }) }
    itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t -> TrackRow(t, vm) { vm.play(tracks, i) } }
}

private fun LazyListScope.folderContents(f: Folder, vm: MusicViewModel, openFolder: (Folder) -> Unit) {
    items(f.subfolders.values.toList(), key = { "f" + it.path }) { sub -> FolderRow(sub, vm) { openFolder(sub) } }
    tracksSection(f.tracks, vm, withHeader = false)
}

// ---------------------------------------------------------------- Rows

@Composable
private fun PlayShuffleBar(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.n_tracks, count, count), color = SevenColors.secondaryText(), fontSize = 14.sp, modifier = Modifier.weight(1f))
        PillButton(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), onShuffle)
        Spacer(Modifier.width(8.dp))
        PillButton(Icons.Rounded.PlayArrow, stringResource(R.string.play_all), onPlay, filled = true)
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit, filled: Boolean = false) {
    val bg = if (filled) SevenColors.accent else SevenColors.card()
    val fg = if (filled) SevenColors.onAccent else SevenColors.text()
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TrackRow(t: Track, vm: MusicViewModel, onClick: () -> Unit) {
    val playing = vm.current?.mediaId == t.id.toString()
    Row2(t.id, t.title, t.artist.ifEmpty { stringResource(R.string.unknown_artist) }, highlight = playing, selectKey = "t${t.id}",
        trailing = { TrackMenuButton(t) }, onClick = onClick)
}

/**
 * Row click behavior shared by all list rows: normal tap acts, long press starts a selection,
 * and while selecting a tap toggles the row instead. Rows without a key are not selectable.
 */
@Composable
private fun Modifier.selectableRow(selectKey: String?, onClick: () -> Unit): Modifier {
    val sel = LocalSelection.current
    return this.clip(RoundedCornerShape(16.dp)).combinedClickable(
        onClick = { if (selectKey != null && sel.active) sel.toggle(selectKey) else onClick() },
        onLongClick = selectKey?.let { { sel.toggle(it) } },
    )
}

@Composable
private fun Row2(
    artId: Long?, title: String, subtitle: String, round: Boolean = false, highlight: Boolean = false,
    selectKey: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val sel = LocalSelection.current
    Row(
        Modifier.fillMaxWidth().selectableRow(selectKey, onClick).padding(start = 12.dp, end = if (trailing != null) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectKey != null) SelectSlot(sel.active, selectKey in sel.keys)
        AlbumArt(artId, if (round) CircleShape else RoundedCornerShape(10.dp), Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (highlight) SevenColors.accent else SevenColors.text(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = SevenColors.secondaryText(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) TrailingSlot(!sel.active) { trailing() }
    }
}

@Composable
private fun FolderRow(f: Folder, vm: MusicViewModel, onClick: () -> Unit) {
    val n = f.totalCount()
    val sel = LocalSelection.current
    val key = "f${f.path}"
    Row(
        Modifier.fillMaxWidth().selectableRow(key, onClick).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectSlot(sel.active, key in sel.keys)
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(SevenColors.artPlaceholder()), Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = SevenColors.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = SevenColors.text(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.n_tracks, n, n), color = SevenColors.secondaryText(), fontSize = 13.sp)
        }
        TrailingSlot(!sel.active) { FolderPlayButton { vm.play(if (vm.includeSubfolders) f.allTracks() else f.tracks) } }
    }
}

/** "All folders" row: art from the folder's first track, folder name, path below. */
@Composable
private fun FlatFolderRow(f: Folder, vm: MusicViewModel, onClick: () -> Unit) {
    val sel = LocalSelection.current
    val key = "f${f.path}"
    Row(
        Modifier.fillMaxWidth().selectableRow(key, onClick).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectSlot(sel.active, key in sel.keys)
        AlbumArt(f.tracks.firstOrNull()?.id, RoundedCornerShape(10.dp), Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = SevenColors.text(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(displayPath(f.path), color = SevenColors.secondaryText(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TrailingSlot(!sel.active) { FolderPlayButton { vm.play(f.tracks) } }
    }
}

@Composable
private fun FolderPlayButton(onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Icon(Icons.Rounded.PlayCircle, stringResource(R.string.play_all), Modifier.size(34.dp), tint = SevenColors.secondaryText())
    }
}

/** Path as users know it from file managers: internal storage root is dropped. */
private fun displayPath(path: String): String = path.removePrefix("/storage/emulated/0/").removePrefix("/storage/")

// ---------------------------------------------------------------- Detail screens

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = SevenColors.text()) }
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Medium, color = SevenColors.text(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 14.sp, color = SevenColors.secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderScreen(lib: Library, path: String, flat: Boolean, vm: MusicViewModel, onBack: () -> Unit, open: (Screen) -> Unit) {
    val f = lib.folder(path) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    // Flat view lists every folder separately, so a folder there plays only its own tracks.
    val all = remember(f, flat, vm.includeSubfolders) { if (flat || !vm.includeSubfolders) f.tracks else f.allTracks() }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(f.name, displayPath(f.path), onBack)
        if (flat) {
            CardList(remember(f) { f.tracks.map { it.fileName } }, 1) {
                item { PlayShuffleBar(all.size, { vm.play(all) }, { vm.play(all, shuffled = true) }) }
                tracksSection(f.tracks, vm, withHeader = false)
            }
        } else {
            CardList(remember(f) { folderLabels(f) }, 1) {
                item { PlayShuffleBar(all.size, { vm.play(all) }, { vm.play(all, shuffled = true) }) }
                folderContents(f, vm) { open(Screen.FolderView(it.path, flat = false)) }
            }
        }
    }
}

@Composable
private fun TrackListScreen(title: String, subtitle: String, tracks: List<Track>, indexed: Boolean, vm: MusicViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title, subtitle, onBack)
        // Album tracks are in track-number order, so only alphabetical lists get the index.
        CardList(if (indexed) remember(tracks) { tracks.map { it.title } } else emptyList(), 1) {
            tracksSection(tracks, vm, withHeader = true)
        }
    }
}

@Composable
private fun SearchScreen(lib: Library, vm: MusicViewModel, onBack: () -> Unit, open: (Screen) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = SevenColors.text()) }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(SevenColors.card()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (q.isEmpty()) Text(stringResource(R.string.search), color = SevenColors.secondaryText(), fontSize = 16.sp)
                BasicTextField(value = q, onValueChange = { v: String -> q = v }, modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = SevenColors.text(), fontSize = 16.sp), cursorBrush = SolidColor(SevenColors.accent))
            }
        }
        val needle = q.trim().lowercase()
        if (needle.isNotEmpty()) {
            val tracks = lib.tracks.filter { needle in it.title.lowercase() || needle in it.artist.lowercase() || needle in it.album.lowercase() }
            val albums = lib.albums.filter { needle in it.name.lowercase() }
            val artists = lib.artists.filter { needle in it.name.lowercase() }
            CardList {
                items(artists, key = { "ar" + it.name }) { a -> Row2(a.tracks.first().id, a.name, stringResource(R.string.tab_artists), round = true, selectKey = "ar${a.name}") { open(Screen.ArtistView(a.name)) } }
                items(albums, key = { "al" + it.id }) { a -> Row2(a.tracks.first().id, a.name, a.artist, selectKey = "al${a.id}") { open(Screen.AlbumView(a.id)) } }
                tracksSection(tracks, vm, withHeader = false)
            }
        }
    }
}

// ---------------------------------------------------------------- Players

private fun MediaItem.trackId(): Long? = mediaId.toLongOrNull()

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MiniPlayer(vm: MusicViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val item = vm.current ?: return
    val frac = if (vm.duration > 0) (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f) else 0f
    val accent = SevenColors.accent
    Row(
        // 60dp pill, round 38dp art 11dp from the edge, 42dp buttons with 24dp icons.
        modifier.padding(horizontal = 10.dp, vertical = 5.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(SevenColors.card())
            .drawBehind { drawRect(accent.copy(alpha = 0.10f), size = size.copy(width = size.width * frac)) }
            .clickable(onClick = onOpen)
            .padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(item.trackId(), label = "miniArt") { id -> AlbumArt(id, CircleShape, Modifier.size(38.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.mediaMetadata.title?.toString().orEmpty(), color = SevenColors.text(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.mediaMetadata.artist?.toString().orEmpty(), color = SevenColors.secondaryText(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniButton(Icons.Rounded.SkipPrevious) { vm.prev() }
        MiniButton(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) { vm.toggle() }
        MiniButton(Icons.Rounded.SkipNext) { vm.next() }
    }
}

@Composable
private fun MiniButton(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = SevenColors.text())
    }
}

@Composable
private fun FullPlayer(vm: MusicViewModel, onClose: () -> Unit) {
    val item = vm.current
    var showQueue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 160.dp.toPx() }
    Column(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(SevenColors.canvas()).clickable(enabled = false) {}
            // Swipe down anywhere outside the queue list to dismiss; a short or slow drag springs back.
            .draggable(
                rememberDraggableState { d -> scope.launch { dragY.snapTo((dragY.value + d).coerceAtLeast(0f)) } },
                Orientation.Vertical,
                onDragStopped = { v -> if (dragY.value > closeAt || v > 2500f) onClose() else dragY.animateTo(0f) },
            )
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose, Modifier.offset(x = (-12).dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = SevenColors.text(), modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton({ showQueue = !showQueue }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.queue), tint = if (showQueue) SevenColors.accent else SevenColors.text())
            }
            item?.trackId()?.let { id -> vm.library?.byId?.get(id) }?.let { TrackMenuButton(it, afterJump = onClose) }
        }
        if (showQueue) {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = vm.queueIndex)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                itemsIndexed(vm.queue) { i, it ->
                    val track = it.trackId()?.let { id -> vm.library?.byId?.get(id) }
                    Row2(
                        it.trackId(), it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(),
                        highlight = i == vm.queueIndex,
                        trailing = track?.let { t -> { TrackMenuButton(t, listOf(R.string.remove_from_queue to { vm.removeQueueAt(i) }), afterJump = onClose) } },
                    ) { vm.jumpTo(i) }
                }
            }
        } else {
            // The art is a pager over the queue: swipe sideways to change track.
            val pager = rememberPagerState(vm.queueIndex) { vm.queue.size }
            LaunchedEffect(vm.queueIndex) {
                if (pager.currentPage != vm.queueIndex && vm.queueIndex < pager.pageCount) {
                    if (kotlin.math.abs(pager.currentPage - vm.queueIndex) == 1) pager.animateScrollToPage(vm.queueIndex)
                    else pager.scrollToPage(vm.queueIndex)
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { if (it != vm.queueIndex) vm.jumpTo(it) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 24.dp, key = { vm.queue.getOrNull(it)?.mediaId ?: it }) { page ->
                // The square art takes the smaller side, so on short screens it shrinks instead of
                // overflowing onto the header and the title.
                BoxWithConstraints(Modifier.fillMaxSize(), Alignment.Center) {
                    AlbumArt(vm.queue.getOrNull(page)?.trackId(), RoundedCornerShape(28.dp), Modifier.size(minOf(maxWidth, maxHeight)), px = 1024)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(item?.mediaMetadata?.title?.toString() ?: stringResource(R.string.not_playing), color = SevenColors.text(), fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item?.mediaMetadata?.artist?.toString().orEmpty(), color = SevenColors.secondaryText(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        var dragging by remember { mutableStateOf<Float?>(null) }
        val dur = vm.duration.coerceAtLeast(1)
        Slider(
            value = dragging ?: (vm.position.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { vm.seek((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = SevenColors.accent, activeTrackColor = SevenColors.accent, inactiveTrackColor = SevenColors.divider()),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(dragging?.let { (it * dur).toLong() } ?: vm.position), color = SevenColors.secondaryText(), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(fmt(vm.duration), color = SevenColors.secondaryText(), fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, null, tint = if (vm.shuffle) SevenColors.accent else SevenColors.secondaryText()) }
            IconButton({ vm.prev() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, null, tint = SevenColors.text(), modifier = Modifier.size(40.dp)) }
            Box(Modifier.size(72.dp).clip(CircleShape).background(SevenColors.accent).clickable { vm.toggle() }, Alignment.Center) {
                Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = SevenColors.onAccent, modifier = Modifier.size(40.dp))
            }
            IconButton({ vm.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, null, tint = SevenColors.text(), modifier = Modifier.size(40.dp)) }
            IconButton({ vm.cycleRepeat() }) {
                Icon(if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
                    tint = if (vm.repeatMode != Player.REPEAT_MODE_OFF) SevenColors.accent else SevenColors.secondaryText())
            }
        }
    }
}
