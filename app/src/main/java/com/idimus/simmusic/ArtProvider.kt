package com.idimus.simmusic

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Serves album art to car hosts, which cannot read MediaStore thumbnails themselves. Read-only,
 * images only, and limited to the hosts below so the music library is not exposed to other apps.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val caller = callingPackage
        if (caller != null && caller != context!!.packageName && caller !in CAR_HOSTS) throw SecurityException("Not allowed: $caller")
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val audio = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = runCatching { context!!.contentResolver.loadThumbnail(audio, Size(320, 320), null) }.getOrNull()
            ?: throw FileNotFoundException("No art for $id")
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "art-$id") {
            FileOutputStream(write.fileDescriptor).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            write.close()
        }
        return read
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.idimus.simmusic.art"
        private val CAR_HOSTS = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.android.car.media", // Android Automotive media center
            "com.google.android.carassistant",
        )
    }
}
