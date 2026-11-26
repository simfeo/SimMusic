package com.idimus.simmusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executors

class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = push(context)

    /**
     * Buttons come back here as broadcasts instead of starting the service directly: a foreground
     * service start with nothing to play would crash, while a MediaController just binds.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null || !action.startsWith(PREFIX)) return super.onReceive(context, intent)
        val pending = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching {
                val c = future.get()
                when (action) {
                    ACTION_PREV -> c.seekToPrevious()
                    ACTION_NEXT -> c.seekToNext()
                    ACTION_SHUFFLE -> c.shuffleModeEnabled = !c.shuffleModeEnabled
                    ACTION_REPEAT -> c.repeatMode = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    ACTION_JUMP -> intent.getIntExtra(EXTRA_INDEX, -1).takeIf { it in 0 until c.mediaItemCount }?.let { c.seekTo(it, 0); c.play() }
                    else -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
                }
                c.release()
            }
            pending.finish()
        }, ContextCompat.getMainExecutor(app))
    }

    private class QueueEntry(val windowIndex: Int, val title: String, val artist: String)

    companion object {
        private const val PREFIX = "com.idimus.simmusic.widget."
        private const val ACTION_PREV = PREFIX + "PREV"
        private const val ACTION_TOGGLE = PREFIX + "TOGGLE"
        private const val ACTION_NEXT = PREFIX + "NEXT"
        private const val ACTION_SHUFFLE = PREFIX + "SHUFFLE"
        private const val ACTION_REPEAT = PREFIX + "REPEAT"
        private const val ACTION_JUMP = PREFIX + "JUMP"
        private const val EXTRA_INDEX = "index"
        private const val QUEUE_LIMIT = 50

        private var line: String? = null
        private var playing = false
        private var shuffle = false
        private var repeat = Player.REPEAT_MODE_OFF
        private var upNext: List<QueueEntry> = emptyList()
        private var art: Bitmap? = null
        private var artTrack: Long? = null
        private val io = Executors.newSingleThreadExecutor()

        /** Called by the service on every player change. */
        fun update(context: Context, player: Player) {
            val md = player.mediaMetadata
            line = listOfNotNull(md.title?.toString(), md.artist?.toString()?.takeIf { it.isNotEmpty() })
                .joinToString(" - ").ifEmpty { null }
            playing = player.isPlaying
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
            upNext = upcoming(player)
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != artTrack) {
                artTrack = id
                art = null
                if (id != null) io.execute {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val bmp = runCatching { context.contentResolver.loadThumbnail(uri, Size(192, 192), null) }.getOrNull()?.let(::roundedSquare)
                    ContextCompat.getMainExecutor(context).execute { if (artTrack == id) { art = bmp; push(context) } }
                }
            }
            push(context)
        }

        /** Tracks after the current one in play order (shuffle aware), capped for the widget list. */
        private fun upcoming(p: Player): List<QueueEntry> {
            val tl = p.currentTimeline
            if (tl.isEmpty) return emptyList()
            val out = ArrayList<QueueEntry>()
            var w = tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (w != C.INDEX_UNSET && out.size < QUEUE_LIMIT) {
                val md = p.getMediaItemAt(w).mediaMetadata
                out += QueueEntry(w, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty())
                w = tl.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            }
            return out
        }

        /** Rounded square matching the placeholder: 16dp corners on a 56dp view, so radius is 2/7 of the side. */
        private fun roundedSquare(src: Bitmap): Bitmap {
            val s = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val r = s * 2f / 7f
            Canvas(out).drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), r, r, paint)
            return out
        }

        fun refresh(context: Context) = push(context)

        private fun push(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val small = header(context, RemoteViews(context.packageName, R.layout.widget_player))
            val views = if (Build.VERSION.SDK_INT >= 31) {
                // The launcher picks the largest layout that fits: 1 row gets the pill, 2+ rows add the queue.
                RemoteViews(mapOf(SizeF(220f, 56f) to small, SizeF(220f, 150f) to large(context)))
            } else small
            manager.updateAppWidget(ids, views)
        }

        private fun header(context: Context, v: RemoteViews): RemoteViews {
            v.setTextViewText(R.id.widget_title, line ?: context.getString(R.string.not_playing))
            v.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            v.setImageViewResource(R.id.widget_repeat, if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_w_repeat_one else R.drawable.ic_w_repeat)
            // Transparent filter leaves the icon as drawn; accent marks an active mode, as in the app.
            val accent = Accent.argb(context)
            v.setInt(R.id.widget_shuffle, "setColorFilter", if (shuffle) accent else 0)
            v.setInt(R.id.widget_repeat, "setColorFilter", if (repeat != Player.REPEAT_MODE_OFF) accent else 0)
            v.setImageViewBitmap(R.id.widget_art, art ?: placeholder(context, accent))
            v.setOnClickPendingIntent(R.id.widget_shuffle, button(context, ACTION_SHUFFLE, 1))
            v.setOnClickPendingIntent(R.id.widget_prev, button(context, ACTION_PREV, 2))
            v.setOnClickPendingIntent(R.id.widget_play, button(context, ACTION_TOGGLE, 3))
            v.setOnClickPendingIntent(R.id.widget_next, button(context, ACTION_NEXT, 4))
            v.setOnClickPendingIntent(R.id.widget_repeat, button(context, ACTION_REPEAT, 5))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        @androidx.annotation.RequiresApi(31)
        private fun large(context: Context): RemoteViews {
            val v = header(context, RemoteViews(context.packageName, R.layout.widget_player_large))
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            upNext.forEach { e ->
                val row = RemoteViews(context.packageName, R.layout.widget_queue_item)
                row.setTextViewText(R.id.widget_item_title, e.title)
                row.setTextViewText(R.id.widget_item_artist, e.artist)
                row.setOnClickFillInIntent(R.id.widget_item, Intent().putExtra(EXTRA_INDEX, e.windowIndex))
                items.addItem(e.windowIndex.toLong(), row)
            }
            v.setRemoteAdapter(R.id.widget_queue, items.build())
            v.setEmptyView(R.id.widget_queue, R.id.widget_queue_empty)
            // Fill-in intents need a mutable template; it targets our own receiver explicitly.
            val template = PendingIntent.getBroadcast(
                context, 6, Intent(context, PlayerWidget::class.java).setAction(ACTION_JUMP),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setPendingIntentTemplate(R.id.widget_queue, template)
            return v
        }

        /** Accent rounded square with a note, drawn here because a XML drawable cannot follow the accent setting. */
        private fun placeholder(context: Context, accent: Int): Bitmap {
            val s = (56 * context.resources.displayMetrics.density).toInt()
            val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val r = s * 2f / 7f
            c.drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            val note = ContextCompat.getDrawable(context, R.drawable.ic_w_note_white)!!.mutate()
            // On Mono the square is white or black, so the note takes the opposite luminance.
            val light = android.graphics.Color.luminance(accent) > 0.6f
            note.setTint(if (light) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            val n = s / 2
            note.setBounds((s - n) / 2, (s - n) / 2, (s + n) / 2, (s + n) / 2)
            note.draw(c)
            return out
        }

        private fun button(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, PlayerWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
