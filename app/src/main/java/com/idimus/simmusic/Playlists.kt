package com.idimus.simmusic

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One line of a playlist; [track] is null when the file it points to is not in the library. */
class PlaylistEntry(val raw: String, val track: Track?)

class Playlist(val path: String, val entries: List<PlaylistEntry>) {
    val name: String = path.substringAfterLast('/').substringBeforeLast('.')
    val dir: String = path.substringBeforeLast('/')
    val ext: String = path.substringAfterLast('.', "").lowercase()
    val tracks: List<Track> get() = entries.mapNotNull { it.track }
    /** PLS is read-only; only M3U and M3U8 are written. */
    val editable: Boolean get() = ext == "m3u" || ext == "m3u8"
}

/**
 * Playlist files anywhere on the phone, found through MediaStore and through the folders the user
 * granted in settings. Edits go through those grants (Storage Access Framework); a playlist outside
 * every granted folder can be played but not changed until its folder is granted too.
 */
object PlaylistStore {
    private val EXTS = listOf("m3u", "m3u8", "pls")
    @Volatile private var cached: List<Playlist> = emptyList()

    fun get(): List<Playlist> = cached

    fun find(path: String): Playlist? = cached.firstOrNull { it.path == path }

    @Synchronized
    fun reload(context: Context, lib: Library): List<Playlist> {
        val paths = LinkedHashSet<String>()
        paths += mediaStorePaths(context)
        for (tree in Grants.trees(context)) walkTree(context, tree, DocumentsContract.getTreeDocumentId(tree), paths, 0)
        cached = paths.mapNotNull { p -> read(context, p)?.let { Playlist(p, parse(it, p, lib)) } }
            .sortedWith(compareBy<Playlist, String>(Collation.labels) { it.name }.thenBy { it.path })
        return cached
    }

    private fun mediaStorePaths(context: Context): List<String> {
        val out = mutableListOf<String>()
        val where = EXTS.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE '%.$it'" }
        runCatching {
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA), where, null, null)
                ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        }
        return out
    }

    private fun walkTree(context: Context, tree: Uri, docId: String, out: MutableSet<String>, depth: Int) {
        if (depth > 8) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) walkTree(context, tree, id, out, depth + 1)
                    else if (id.substringAfterLast('.', "").lowercase() in EXTS) Grants.docIdToPath(id)?.let { out += it }
                }
            }
        }
    }

    private fun read(context: Context, path: String): String? {
        val stream: InputStream? = runCatching { File(path).takeIf { it.canRead() }?.inputStream() }.getOrNull()
            ?: Grants.docUri(context, path)?.let { runCatching { context.contentResolver.openInputStream(it) }.getOrNull() }
            ?: mediaStoreUri(context, path)?.let { runCatching { context.contentResolver.openInputStream(it) }.getOrNull() }
        return stream?.use { decode(it.readBytes()) }
    }

    private fun mediaStoreUri(context: Context, path: String): Uri? {
        val files = MediaStore.Files.getContentUri("external")
        return runCatching {
            context.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID), "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(path), null)
                ?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(files, c.getLong(0)) else null }
        }.getOrNull()
    }

    /** UTF-8 when the bytes are valid UTF-8 (always for .m3u8), otherwise the old Windows code page. */
    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { utf8.decode(ByteBuffer.wrap(bytes)).toString() } catch (e: CharacterCodingException) { String(bytes, charset("windows-1252")) }
        return text.removePrefix("﻿")
    }

    fun parse(text: String, path: String, lib: Library): List<PlaylistEntry> {
        val dir = path.substringBeforeLast('/')
        val lines = text.lines().map { it.trim() }
        val raws = if (path.endsWith(".pls", ignoreCase = true)) {
            // "FileN=..." lines, ordered by N.
            lines.mapNotNull { l -> Regex("""(?i)^file(\d+)=(.*)$""").find(l)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }
                .sortedBy { it.first }.map { it.second }
        } else {
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        return raws.map { PlaylistEntry(it, lib.trackAt(resolve(it, dir))) }
    }

    /** Absolute, relative, file:// and Windows-style entries all become one absolute path. */
    private fun resolve(raw: String, dir: String): String {
        var p = raw.replace('\\', '/')
        if (p.startsWith("file://")) p = Uri.decode(p.removePrefix("file://"))
        if (!p.startsWith("/")) p = "$dir/$p"
        val parts = ArrayDeque<String>()
        for (part in p.split('/')) when (part) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(part)
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * Paths are written relative to the playlist (with "../" when needed) as long as both sit on the
     * same storage, so a copied music folder keeps working on a PC or another phone.
     */
    private fun relative(from: String, to: String): String {
        val a = from.split('/').filter { it.isNotEmpty() }
        val b = to.split('/').filter { it.isNotEmpty() }
        val volume = if (a.take(3) == listOf("storage", "emulated", "0")) 3 else 2
        if (a.take(volume) != b.take(volume)) return to
        var common = 0
        while (common < a.size && common < b.size - 1 && a[common] == b[common]) common++
        return (List(a.size - common) { ".." } + b.drop(common)).joinToString("/")
    }

    fun render(pl: Playlist, entries: List<PlaylistEntry>): String = buildString {
        append("#EXTM3U\n")
        for (e in entries) {
            val t = e.track
            if (t == null) { append(e.raw).append('\n'); continue }
            val line = relative(pl.dir, "${t.dir}/${t.fileName}")
            append("#EXTINF:").append(t.durationMs / 1000).append(',')
            if (t.artist.isNotEmpty()) append(t.artist).append(" - ")
            append(t.title).append('\n').append(line).append('\n')
        }
    }

    sealed interface Result {
        data object Ok : Result
        /** The folder is not granted; ask for it, then retry. */
        data class NeedsGrant(val dir: String) : Result
        data object Failed : Result
    }

    fun write(context: Context, pl: Playlist, entries: List<PlaylistEntry>): Result {
        val uri = Grants.docUri(context, pl.path) ?: return Result.NeedsGrant(pl.dir)
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(render(pl, entries).toByteArray()) }
        }.isSuccess
        if (ok) rescan(context, pl.path)
        return if (ok) Result.Ok else Result.Failed
    }

    /** Creates `<dir>/<name>.m3u8`, making missing folders on the way. */
    fun create(context: Context, dir: String, name: String, tracks: List<Track>): Pair<Result, String?> {
        val path = "$dir/$name.m3u8"
        if (File(path).exists() || cached.any { it.path.equals(path, ignoreCase = true) }) return Result.Failed to null
        val parent = Grants.ensureDir(context, dir) ?: return Result.NeedsGrant(dir) to null
        val doc = runCatching { DocumentsContract.createDocument(context.contentResolver, parent, "audio/x-mpegurl", "$name.m3u8") }.getOrNull()
            ?: return Result.Failed to null
        // Some providers append their own extension; the real path is whatever the document id says.
        val realPath = Grants.docIdToPath(DocumentsContract.getDocumentId(doc)) ?: path
        val pl = Playlist(realPath, emptyList())
        val ok = runCatching {
            context.contentResolver.openOutputStream(doc, "wt")!!.use { it.write(render(pl, tracks.map { PlaylistEntry("", it) }).toByteArray()) }
        }.isSuccess
        rescan(context, realPath)
        return (if (ok) Result.Ok else Result.Failed) to realPath
    }

    fun delete(context: Context, pl: Playlist): Result {
        val uri = Grants.docUri(context, pl.path) ?: return Result.NeedsGrant(pl.dir)
        val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (ok) rescan(context, pl.path)
        return if (ok) Result.Ok else Result.Failed
    }

    private fun rescan(context: Context, path: String) = MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
}

/** The last few playlists played or edited, newest first. */
object RecentPlaylists {
    private const val KEY = "recent_playlists"
    private const val MAX = 3
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(c: Context): List<String> = prefs(c).getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun touch(c: Context, path: String) {
        val list = (listOf(path) + get(c).filter { it != path }).take(MAX)
        prefs(c).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun forget(c: Context, path: String) = prefs(c).edit().putString(KEY, get(c).filter { it != path }.joinToString("\n")).apply()
}

/**
 * Folders the user granted through the system folder picker. A grant covers the folder and
 * everything below it, and lasts until it is removed in settings.
 */
object Grants {
    private const val EXTERNAL = "com.android.externalstorage.documents"

    fun trees(c: Context): List<Uri> = c.contentResolver.persistedUriPermissions
        .filter { it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }

    fun take(c: Context, tree: Uri) = c.contentResolver.takePersistableUriPermission(
        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    fun release(c: Context, tree: Uri) = runCatching {
        c.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun treePath(tree: Uri): String? = docIdToPath(DocumentsContract.getTreeDocumentId(tree))

    /** "primary:Music/a.m3u" is internal storage, "1A2B-3C4D:..." a memory card. */
    fun docIdToPath(docId: String): String? {
        val volume = docId.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val rest = docId.substringAfter(':')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    fun pathToDocId(path: String): String? = when {
        path == "/storage/emulated/0" -> "primary:"
        path.startsWith("/storage/emulated/0/") -> "primary:" + path.removePrefix("/storage/emulated/0/")
        path.startsWith("/storage/") -> path.removePrefix("/storage/").let { "${it.substringBefore('/')}:${it.substringAfter('/', "")}" }
        else -> null
    }

    /** The granted tree containing [path], or null. */
    private fun treeFor(c: Context, path: String): Uri? = trees(c).firstOrNull { t ->
        val root = treePath(t) ?: return@firstOrNull false
        path == root || path.startsWith("$root/")
    }

    fun docUri(c: Context, path: String): Uri? {
        val tree = treeFor(c, path) ?: return null
        val id = pathToDocId(path) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    /** Document uri of [dir], creating the missing folders below the granted one. */
    fun ensureDir(c: Context, dir: String): Uri? {
        val tree = treeFor(c, dir) ?: return null
        val root = treePath(tree) ?: return null
        var current = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var currentPath = root
        for (part in dir.removePrefix(root).split('/').filter { it.isNotEmpty() }) {
            currentPath += "/$part"
            val existing = DocumentsContract.buildDocumentUriUsingTree(tree, pathToDocId(currentPath) ?: return null)
            current = if (runCatching { c.contentResolver.query(existing, null, null, null, null)?.use { it.count > 0 } }.getOrNull() == true) existing
            else runCatching { DocumentsContract.createDocument(c.contentResolver, current, DocumentsContract.Document.MIME_TYPE_DIR, part) }.getOrNull() ?: return null
        }
        return current
    }

    /** Opens the picker at [dir] when the system allows it. */
    fun pickerIntent(dir: String?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        dir?.let(::pathToDocId)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(EXTERNAL, it)) }
    }
}
