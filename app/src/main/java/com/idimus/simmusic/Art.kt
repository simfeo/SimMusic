package com.idimus.simmusic

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ArtCache {
    val cache = LruCache<String, Bitmap>(200)
    /** Tracks with no embedded art, so they are not re-probed on every scroll. */
    val missing = HashSet<String>()
}

private suspend fun loadArt(context: Context, trackId: Long, px: Int): Bitmap? {
    val key = "$trackId@$px"
    ArtCache.cache.get(key)?.let { return it }
    if (key in ArtCache.missing) return null
    return withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }.also { if (it != null) ArtCache.cache.put(key, it) else ArtCache.missing += key }
}

@Composable
fun AlbumArt(trackId: Long?, shape: Shape, modifier: Modifier = Modifier, px: Int = 192) {
    val context = LocalContext.current
    var bmp by remember(trackId, px) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(trackId, px) { bmp = trackId?.let { loadArt(context, it, px) } }
    Box(modifier.clip(shape).background(SevenColors.artPlaceholder()), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxWidth(0.45f), tint = SevenColors.secondaryText())
        }
    }
}
