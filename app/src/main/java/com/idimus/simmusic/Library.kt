package com.idimus.simmusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * One library for the app and the playback service (car browsing, resumption), so a scan done by
 * either is reused by the other. Loads block; call them off the main thread.
 */
object LibraryStore {
    @Volatile private var cached: Library? = null

    fun get(context: Context): Library = cached ?: synchronized(this) { cached ?: reload(context) }

    @Synchronized
    fun reload(context: Context): Library = Library.load(context.applicationContext).also { cached = it }
}

object Collation {
    /** Locale aware, case and accent blind, numbers by value ("Track 2" before "Track 10"). */
    val primary: android.icu.text.Collator = android.icu.text.Collator.getInstance().apply {
        strength = android.icu.text.Collator.PRIMARY
        (this as? android.icu.text.RuleBasedCollator)?.numericCollation = true
    }.freeze() // frozen ICU collators are thread safe; the library sorts off the main thread

    /** Titles starting with a letter first, the rest after them under "#", like the index bar. */
    val labels: Comparator<String> = compareBy<String> { s -> s.trim().firstOrNull()?.isLetter() != true }
        .thenComparator { a, b -> primary.compare(a.trim(), b.trim()) }
}

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int,
    /** Parent directory as an absolute path without a trailing slash. */
    val dir: String,
    val fileName: String,
)

class Folder(val path: String, val name: String) {
    // Exact tiebreak so "Rock" and "rock" stay two folders instead of colliding as one key.
    val subfolders = sortedMapOf<String, Folder>(Collation.labels.thenComparator { a, b -> a.compareTo(b) })
    val tracks = mutableListOf<Track>()

    fun allTracks(): List<Track> = tracks + subfolders.values.flatMap { it.allTracks() }
    fun totalCount(): Int = tracks.size + subfolders.values.sumOf { it.totalCount() }
}

data class Album(val id: Long, val name: String, val artist: String, val tracks: List<Track>)
data class Artist(val name: String, val tracks: List<Track>)

class Library(val tracks: List<Track>) {
    val byId: Map<Long, Track> = tracks.associateBy { it.id }

    val albums: List<Album> = tracks.groupBy { it.albumId }.map { (id, t) ->
        Album(id, t.first().album, t.first().artist, t.sortedWith(compareBy({ it.trackNo }, { it.title })))
    }.sortedWith(compareBy(Collation.labels) { it.name })

    val artists: List<Artist> = tracks.groupBy { it.artist }.map { (n, t) -> Artist(n, t.sortedWith(compareBy(Collation.labels) { it.title })) }
        .sortedWith(compareBy(Collation.labels) { it.name })

    val root: Folder = buildTree()

    private fun buildTree(): Folder {
        val root = Folder("/", "/")
        for (t in tracks) {
            var node = root
            var acc = ""
            for (part in t.dir.split('/').filter { it.isNotEmpty() }) {
                acc += "/$part"
                node = node.subfolders.getOrPut(part) { Folder(acc, part) }
            }
            node.tracks += t
        }
        fun sortAll(f: Folder) { f.tracks.sortWith(compareBy(Collation.labels) { it.fileName }); f.subfolders.values.forEach(::sortAll) }
        sortAll(root)
        return root
    }

    /** Skips the trackless single-child chain (/storage/emulated/0) so browsing starts at "Music", "Download"... */
    val browseRoot: Folder = run {
        var f = root
        while (f.tracks.isEmpty() && f.subfolders.size == 1) f = f.subfolders.values.first()
        f
    }

    /** Every folder holding tracks directly, for the flat "all folders" view. */
    val musicFolders: List<Folder> = buildList {
        fun walk(f: Folder) { if (f.tracks.isNotEmpty()) add(f); f.subfolders.values.forEach(::walk) }
        walk(root)
    }

    fun musicFoldersSorted(byPath: Boolean): List<Folder> =
        if (byPath) musicFolders.sortedWith(compareBy(Collation.labels) { it.path })
        else musicFolders.sortedWith(compareBy<Folder, String>(Collation.labels) { it.name }.thenBy(Collation.labels) { it.path })

    fun folder(path: String): Folder? {
        var node = root
        for (part in path.split('/').filter { it.isNotEmpty() }) node = node.subfolders[part] ?: return null
        return node
    }

    companion object {
        fun load(context: Context): Library {
            val list = mutableListOf<Track>()
            val proj = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME,
            )
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val data = c.getString(7) ?: continue
                    val unknown = "<unknown>"
                    list += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        title = c.getString(1) ?: c.getString(8) ?: "",
                        artist = c.getString(2)?.takeIf { it != unknown } ?: "",
                        album = c.getString(3) ?: "",
                        albumId = c.getLong(4),
                        durationMs = c.getLong(5),
                        // MediaStore encodes disc*1000 + track.
                        trackNo = c.getInt(6) % 1000,
                        dir = data.substringBeforeLast('/'),
                        fileName = c.getString(8) ?: data.substringAfterLast('/'),
                    )
                }
            }
            return Library(list.sortedWith(compareBy(Collation.labels) { it.title }))
        }
    }
}
