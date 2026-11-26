package com.idimus.simmusic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants

/**
 * Browse tree for Android Auto and other media browsers.
 *
 *   root -> folders | albums | artists | tracks
 *   folder:<path>, album:<id>, artist:<name>   opened on tap; voice requests can still play them whole
 *   t:<trackId>@<parentId>                     a track that plays its parent list from that track
 *
 * Queue items keep the plain track id the app uses, so phone and car share one queue format.
 */
object CarLibrary {
    const val ROOT = "root"
    private const val FOLDERS = "folders"
    private const val ALBUMS = "albums"
    private const val ARTISTS = "artists"
    private const val TRACKS = "tracks"
    const val SEARCH = "search:"

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun folderTree(c: Context) = prefs(c).getBoolean("folder_tree", false)
    private fun includeSub(c: Context) = prefs(c).getBoolean("include_subfolders", true)

    fun root(c: Context): MediaItem = browsable(ROOT, c.getString(R.string.app_name), null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false)

    fun children(c: Context, lib: Library, parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browsable(FOLDERS, c.getString(R.string.tab_folders), null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false),
            browsable(ALBUMS, c.getString(R.string.tab_albums), null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, playable = false, grid = true),
            browsable(ARTISTS, c.getString(R.string.tab_artists), null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, playable = false),
            browsable(TRACKS, c.getString(R.string.tab_tracks), null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false),
        )
        parentId == FOLDERS -> if (folderTree(c)) folderChildren(c, lib.browseRoot) else
            lib.musicFoldersSorted(prefs(c).getBoolean("folder_sort_path", false)).map { folderItem(c, it) }
        parentId == ALBUMS -> lib.albums.map {
            browsable("album:${it.id}", it.name.ifEmpty { c.getString(R.string.unknown_album) }, it.artist, MediaMetadata.MEDIA_TYPE_ALBUM,
                art = artUri(it.tracks.first().id))
        }
        parentId == ARTISTS -> lib.artists.map {
            browsable("artist:${it.name}", it.name.ifEmpty { c.getString(R.string.unknown_artist) },
                c.resources.getQuantityString(R.plurals.n_tracks, it.tracks.size, it.tracks.size), MediaMetadata.MEDIA_TYPE_ARTIST,
                art = artUri(it.tracks.first().id))
        }
        parentId == TRACKS -> trackItems(c, lib.tracks, TRACKS)
        parentId.startsWith("folder:") -> lib.folder(parentId.removePrefix("folder:"))?.let {
            if (folderTree(c)) folderChildren(c, it) else trackItems(c, it.tracks, parentId)
        }.orEmpty()
        else -> playList(c, lib, parentId)?.let { trackItems(c, it, parentId) }.orEmpty()
    }

    fun item(c: Context, lib: Library, id: String): MediaItem? = when {
        id == ROOT -> root(c)
        id.startsWith("t:") -> id.removePrefix("t:").substringBefore('@').toLongOrNull()?.let { lib.byId[it] }?.toMediaItem()
        else -> id.toLongOrNull()?.let { lib.byId[it] }?.toMediaItem()
            ?: children(c, lib, ROOT).firstOrNull { it.mediaId == id }
    }

    /** The tracks a browsable id stands for when the whole thing is played. */
    fun playList(c: Context, lib: Library, id: String): List<Track>? = when {
        id == TRACKS -> lib.tracks
        id.startsWith("folder:") -> lib.folder(id.removePrefix("folder:"))?.let {
            if (folderTree(c) && includeSub(c)) it.allTracks() else it.tracks
        }
        id.startsWith("album:") -> lib.albums.firstOrNull { it.id.toString() == id.removePrefix("album:") }?.tracks
        id.startsWith("artist:") -> lib.artists.firstOrNull { it.name == id.removePrefix("artist:") }?.tracks
        id.startsWith(SEARCH) -> search(lib, id.removePrefix(SEARCH))
        else -> null
    }

    /** Resolves what a browser asked to play into a queue and the index to start at. */
    fun resolvePlay(c: Context, lib: Library, item: MediaItem): Pair<List<Track>, Int>? {
        val id = item.mediaId
        if (id.startsWith("t:")) {
            val trackId = id.removePrefix("t:").substringBefore('@').toLongOrNull() ?: return null
            val list = playList(c, lib, id.substringAfter('@', "")) ?: listOfNotNull(lib.byId[trackId])
            return list to list.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        }
        id.toLongOrNull()?.let { tid -> lib.byId[tid]?.let { return listOf(it) to 0 } }
        playList(c, lib, id)?.let { return it to 0 }
        // Voice "play X on Sim Music" arrives as an empty id with a search query.
        val query = item.requestMetadata.searchQuery ?: return null
        return voiceQueue(lib, query) to 0
    }

    private fun voiceQueue(lib: Library, query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return lib.tracks.shuffled()
        lib.artists.firstOrNull { it.name.lowercase() == q }?.let { return it.tracks }
        lib.albums.firstOrNull { it.name.lowercase() == q }?.let { return it.tracks }
        lib.musicFolders.firstOrNull { it.name.lowercase() == q }?.let { return it.tracks }
        return search(lib, query).ifEmpty { lib.tracks.shuffled() }
    }

    fun search(lib: Library, query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return lib.tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() || q in it.dir.substringAfterLast('/').lowercase() }
    }

    fun trackItems(c: Context, tracks: List<Track>, parentId: String): List<MediaItem> = tracks.map { t ->
        MediaItem.Builder().setMediaId("t:${t.id}@$parentId").setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist.ifEmpty { c.getString(R.string.unknown_artist) })
                .setAlbumTitle(t.album)
                .setArtworkUri(artUri(t.id))
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        ).build()
    }

    private fun folderChildren(c: Context, f: Folder): List<MediaItem> =
        f.subfolders.values.map { folderItem(c, it) } + trackItems(c, f.tracks, "folder:${f.path}")

    private fun folderItem(c: Context, f: Folder): MediaItem {
        val n = f.totalCount()
        return browsable("folder:${f.path}", f.name, c.resources.getQuantityString(R.plurals.n_tracks, n, n),
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, art = f.tracks.firstOrNull()?.id?.let(::artUri))
    }

    // Not playable by default: Android Auto plays a playable folder on tap instead of opening it.
    private fun browsable(
        id: String, title: String, subtitle: String?, type: Int, playable: Boolean = false, art: Uri? = null, grid: Boolean = false,
    ): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(art)
            .setIsBrowsable(true).setIsPlayable(playable)
            .setMediaType(type)
            .setExtras(Bundle().apply {
                if (grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            })
            .build(),
    ).build()

    /** Car hosts cannot read MediaStore art directly, so art goes through our own provider. */
    fun artUri(trackId: Long): Uri = Uri.parse("content://${ArtProvider.AUTHORITY}/track/$trackId")

    fun page(list: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) list else list.drop(page * pageSize).take(pageSize)
}
