package com.idimus.simmusic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Plays in the background and exposes the library to Android Auto and other media browsers.
 * Library work runs on [io] because the first scan can take a moment on a large collection.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private var lastSearch: List<MediaItem> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            // Pauses when headphones or a Bluetooth headset disconnect.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val s = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(open).build()
        session = s
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                PlayerWidget.update(this@PlaybackService, player)
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) updateButtons(s, player)
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    Resume.save(this@PlaybackService, player)
                }
            }
        })
        updateButtons(s, player)
    }

    /** Shuffle and repeat as session buttons, so the car screen and the notification show them. */
    private fun updateButtons(s: MediaSession, p: Player) {
        val shuffle = CommandButton.Builder(if (p.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(getString(R.string.shuffle_desc))
            .setSessionCommand(CMD_SHUFFLE)
            .build()
        val repeatIcon = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val repeat = CommandButton.Builder(repeatIcon).setDisplayName(getString(R.string.repeat_desc)).setSessionCommand(CMD_REPEAT).build()
        s.setMediaButtonPreferences(ImmutableList.of(shuffle, repeat))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { Resume.save(this@PlaybackService, player); player.release(); release() }
        session = null
        io.shutdown()
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        private val ctx: Context get() = this@PlaybackService

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_SHUFFLE).add(CMD_REPEAT).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                CMD_SHUFFLE.customAction -> p.shuffleModeEnabled = !p.shuffleModeEnabled
                CMD_REPEAT.customAction -> p.repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                // Platform browser key; it makes Android Auto show its search button.
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.root(ctx), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val all = CarLibrary.children(ctx, LibraryStore.get(ctx), parentId)
            LibraryResult.ofItemList(CarLibrary.page(all, page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = io.submit<LibraryResult<MediaItem>> {
            CarLibrary.item(ctx, LibraryStore.get(ctx), mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = io.submit<LibraryResult<Void>> {
            val found = CarLibrary.trackItems(ctx, CarLibrary.search(LibraryStore.get(ctx), query), CarLibrary.SEARCH + query)
            lastSearch = found
            session.notifySearchResultChanged(browser, query, found.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            LibraryResult.ofItemList(CarLibrary.page(lastSearch, page, pageSize), params)
        }

        /**
         * The app sends ready-to-play items (they carry a file URI) and those pass through. Browser
         * requests carry only an id, which expands to the list it came from, starting at that track.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            }
            return io.submit<MediaSession.MediaItemsWithStartPosition> {
                val lib = LibraryStore.get(ctx)
                val single = mediaItems.singleOrNull()?.let { CarLibrary.resolvePlay(ctx, lib, it) }
                if (single != null) {
                    MediaSession.MediaItemsWithStartPosition(single.first.map { it.toMediaItem() }, single.second, C.TIME_UNSET)
                } else {
                    val items = mediaItems.flatMap { m -> m.takeIf { it.localConfiguration != null }?.let { listOf(it) }
                        ?: CarLibrary.resolvePlay(ctx, lib, m)?.first?.map { it.toMediaItem() }.orEmpty() }
                    MediaSession.MediaItemsWithStartPosition(items, startIndex.coerceIn(0, maxOf(0, items.lastIndex)), startPositionMs)
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
            return io.submit<MutableList<MediaItem>> {
                val lib = LibraryStore.get(ctx)
                mediaItems.flatMap { m -> m.takeIf { it.localConfiguration != null }?.let { listOf(it) }
                    ?: CarLibrary.resolvePlay(ctx, lib, m)?.first?.map { it.toMediaItem() }.orEmpty() }.toMutableList()
            }
        }

        /** Restores the last queue when a car or headset asks to play after the app was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit<MediaSession.MediaItemsWithStartPosition> {
            Resume.load(ctx, LibraryStore.get(ctx))
        }
    }

    companion object {
        private val CMD_SHUFFLE = SessionCommand("com.idimus.simmusic.SHUFFLE", Bundle.EMPTY)
        private val CMD_REPEAT = SessionCommand("com.idimus.simmusic.REPEAT", Bundle.EMPTY)
    }
}

/** Remembers the queue, current track and position so playback can resume later. */
object Resume {
    private fun prefs(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun save(c: Context, p: Player) {
        if (p.mediaItemCount == 0) return
        val ids = (0 until p.mediaItemCount).joinToString(",") { p.getMediaItemAt(it).mediaId }
        prefs(c).edit()
            .putString("ids", ids)
            .putInt("index", p.currentMediaItemIndex)
            .putLong("position", p.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun load(c: Context, lib: Library): MediaSession.MediaItemsWithStartPosition {
        val p = prefs(c)
        val ids = p.getString("ids", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val saved = p.getInt("index", 0)
        val savedId = ids.getOrNull(saved)
        // Files deleted since then are skipped; the current track is found again by id.
        val tracks = ids.mapNotNull { lib.byId[it] }
        val index = tracks.indexOfFirst { it.id == savedId }
        return MediaSession.MediaItemsWithStartPosition(
            tracks.map { it.toMediaItem() },
            index.coerceAtLeast(0),
            if (index >= 0) p.getLong("position", 0) else 0,
        )
    }
}
