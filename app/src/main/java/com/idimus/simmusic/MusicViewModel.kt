package com.idimus.simmusic

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    var library by mutableStateOf<Library?>(null); private set
    var controller by mutableStateOf<MediaController?>(null); private set

    var current by mutableStateOf<MediaItem?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var queueIndex by mutableStateOf(0); private set
    private var queueOrder: List<Int> = emptyList()

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    /** Default: one flat list of every music folder; tree is the alternative. */
    var folderTree by mutableStateOf(prefs.getBoolean("folder_tree", false)); private set
    var folderSortByPath by mutableStateOf(prefs.getBoolean("folder_sort_path", false)); private set
    var includeSubfolders by mutableStateOf(prefs.getBoolean("include_subfolders", true)); private set

    fun updateFolderTree(v: Boolean) { folderTree = v; prefs.edit().putBoolean("folder_tree", v).apply() }
    fun updateFolderSortByPath(v: Boolean) { folderSortByPath = v; prefs.edit().putBoolean("folder_sort_path", v).apply() }
    fun updateIncludeSubfolders(v: Boolean) { includeSubfolders = v; prefs.edit().putBoolean("include_subfolders", v).apply() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(listener)
            controller = c
            sync(c)
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (true) {
                controller?.let { position = it.currentPosition; duration = it.duration.coerceAtLeast(0) }
                delay(500)
            }
        }
    }

    fun loadLibrary() = viewModelScope.launch {
        val lib = withContext(Dispatchers.IO) { LibraryStore.reload(getApplication()) }
        library = lib
        playlists = withContext(Dispatchers.IO) { PlaylistStore.reload(getApplication(), lib) }
    }

    var playlists by mutableStateOf<List<Playlist>>(emptyList()); private set
    /** Bumped on every play or edit so the recent list in the picker recomposes. */
    var recentPlaylists by mutableStateOf(RecentPlaylists.get(app)); private set

    private fun touchPlaylist(path: String) {
        RecentPlaylists.touch(getApplication(), path)
        recentPlaylists = RecentPlaylists.get(getApplication())
    }

    fun playPlaylist(pl: Playlist, start: Int = 0, shuffled: Boolean = false) {
        play(pl.tracks, start, shuffled)
        touchPlaylist(pl.path)
    }

    /** Runs a playlist change off the main thread, then rereads the playlists so every screen updates. */
    fun editPlaylists(touch: String?, op: (Application) -> PlaylistStore.Result, onDone: (PlaylistStore.Result) -> Unit) = viewModelScope.launch {
        val app = getApplication<Application>()
        val r = withContext(Dispatchers.IO) { op(app) }
        if (r == PlaylistStore.Result.Ok) {
            library?.let { lib -> playlists = withContext(Dispatchers.IO) { PlaylistStore.reload(app, lib) } }
            touch?.let(::touchPlaylist)
        }
        onDone(r)
    }

    fun forgetPlaylist(path: String) {
        RecentPlaylists.forget(getApplication(), path)
        recentPlaylists = RecentPlaylists.get(getApplication())
    }

    fun reloadPlaylists() = viewModelScope.launch {
        val lib = library ?: return@launch
        playlists = withContext(Dispatchers.IO) { PlaylistStore.reload(getApplication(), lib) }
    }

    private fun sync(p: Player) {
        current = p.currentMediaItem
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        // Queue in play order, so with shuffle on the queue view and art swipes follow the shuffled order.
        val tl = p.currentTimeline
        val order = ArrayList<Int>(tl.windowCount)
        var w = tl.getFirstWindowIndex(p.shuffleModeEnabled)
        while (w != C.INDEX_UNSET && order.size < tl.windowCount) {
            order += w
            w = tl.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        queueOrder = order
        queue = order.map { p.getMediaItemAt(it) }
        queueIndex = order.indexOf(p.currentMediaItemIndex).coerceAtLeast(0)
        position = p.currentPosition
        duration = p.duration.coerceAtLeast(0)
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        val first = if (shuffled) tracks.indices.random() else start
        c.setMediaItems(tracks.map { it.toMediaItem() }, first, 0)
        c.prepare()
        c.play()
    }

    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    fun next() = controller?.seekToNext()
    fun prev() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms)
    /** Drops deleted files from the queue, highest index first so earlier indices stay valid. */
    fun removeFromQueue(mediaIds: Set<String>) = controller?.run {
        (mediaItemCount - 1 downTo 0).filter { getMediaItemAt(it).mediaId in mediaIds }.forEach { removeMediaItem(it) }
    }

    fun removeQueueAt(pos: Int) = controller?.run { queueOrder.getOrNull(pos)?.let { removeMediaItem(it) } }

    /** `pos` is a position in the play-order [queue]. */
    fun jumpTo(pos: Int) = controller?.run { queueOrder.getOrNull(pos)?.let { seekTo(it, 0); play() } }
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onCleared() {
        controller?.release()
    }
}

fun albumArtUri(albumId: Long): Uri = Uri.parse("content://media/external/audio/albumart/$albumId")

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(albumArtUri(albumId))
            .setExtras(android.os.Bundle().apply { putLong("albumId", albumId) })
            .build(),
    )
    .build()
