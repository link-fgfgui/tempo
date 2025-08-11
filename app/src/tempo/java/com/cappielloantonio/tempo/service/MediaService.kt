package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import cn.lyric.getter.api.API
import cn.lyric.getter.api.data.ExtraData
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.subsonic.models.Line
import com.cappielloantonio.tempo.subsonic.models.StructuredLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext


@UnstableApi
class MediaService : MediaLibraryService(), SessionAvailabilityListener, CoroutineScope {
    private lateinit var automotiveRepository: AutomotiveRepository
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var librarySessionCallback: MediaLibrarySessionCallback

    private var lga = API()

    private val job = SupervisorJob()
    private var currentLyricsList: List<StructuredLyrics>? = null

    private val lyricsHandler = Handler(Looper.getMainLooper())
    private lateinit var lyricsProgressRunnable: Runnable
    private var lastSentLyricLine: String = ""

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCreate() {
        super.onCreate()

        initializeRepository()
        initializePlayer()
        initializeCastPlayer()
        initializeMediaLibrarySession()
        initializePlayerListener()
        initializeSyncedLyric()

        setPlayer(
                null,
                if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable) castPlayer else player
        )
    }

    private fun isLyricOK(): Boolean {
        return player.isPlaying && player.currentMediaItem != null && currentLyricsList != null
    }

    private fun initializeSyncedLyric() {
        lyricsProgressRunnable = Runnable {
            var toSleep: Long = 500
            if (isLyricOK()) {
                if (currentLyricsList!!.isNotEmpty() && currentLyricsList!!.first().synced) {
                    var lines = currentLyricsList!!.first().line!!
                    for ((index, line) in lines.withIndex()) {
                        val curPos = player.currentPosition
                        if (curPos < line.start!!) {
                            var readySendLine = lines[if (index > 0) index - 1 else 0].value
                            toSleep = line.start!! - curPos + 10
                            if (lga.hasEnable) {
                                lga.sendLyric(readySendLine)
                            }else {
                                Log.w("MusicService","lga: "+lga.hasEnable.toString())
                            }
                            lastSentLyricLine = readySendLine
                            break
                        }
                    }
                }
            }
            lyricsHandler.postDelayed(lyricsProgressRunnable, toSleep)
        }
        lyricsProgressRunnable.run()
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
        job.cancel()
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
                .setRenderersFactory(getRenderersFactory())
                .setMediaSourceFactory(getMediaSourceFactory())
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(initializeLoadControl())
                .build()

        player.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
        player.repeatMode = Preferences.getRepeatMode()
    }

    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            castPlayer = CastPlayer(CastContext.getSharedInstance(this))
            castPlayer.setSessionAvailabilityListener(this)
        }
    }

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent =
                TaskStackBuilder.create(this).run {
                    addNextIntent(Intent(this@MediaService, MainActivity::class.java))
                    getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
                }

        librarySessionCallback = createLibrarySessionCallback()
        mediaLibrarySession =
                MediaLibrarySession.Builder(this, player, librarySessionCallback)
                        .setSessionActivity(sessionActivityPendingIntent)
                        .build()
    }

    private fun createLibrarySessionCallback(): MediaLibrarySessionCallback {
        return MediaLibrarySessionCallback(this, automotiveRepository)
    }

    private fun initializePlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                currentLyricsList = null;
                lastSentLyricLine = "";
                launch(Dispatchers.IO) {
                    val response: List<StructuredLyrics>? =
                        App.getSubsonicClientInstance(false).openClient.getLyricsBySongId(mediaItem.mediaId)
                            .execute().body()?.subsonicResponse?.lyricsList?.structuredLyrics
                    withContext(Dispatchers.Main) {
                        currentLyricsList = response
                    }
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)
                MediaManager.scrobble(player.currentMediaItem, false)

                if (player.currentMediaItemIndex + 1 == player.mediaItemCount)
                    MediaManager.continuousPlay(player.currentMediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                            player.currentMediaItem,
                            player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (!player.hasNextMediaItem() &&
                        playbackState == Player.STATE_ENDED &&
                        player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
                }
            }

            override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                lyricsHandler.removeCallbacks(lyricsProgressRunnable)
                lyricsProgressRunnable.run()
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled)
                mediaLibrarySession.setCustomLayout(
                        librarySessionCallback.buildCustomLayout(player)
                )
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
                mediaLibrarySession.setCustomLayout(
                        librarySessionCallback.buildCustomLayout(player)
                )
            }
        })
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        (DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        oldPlayer?.stop()
        mediaLibrarySession.player = newPlayer
    }

    private fun releasePlayer() {
        if (this::castPlayer.isInitialized) castPlayer.setSessionAvailabilityListener(null)
        if (this::castPlayer.isInitialized) castPlayer.release()
        player.release()
        mediaLibrarySession.release()
        automotiveRepository.deleteMetadata()
        clearListener()
    }

    private fun getRenderersFactory() = DownloadUtil.buildRenderersFactory(this, false)

    private fun getMediaSourceFactory() =
            DefaultMediaSourceFactory(this).setDataSourceFactory(DownloadUtil.getDataSourceFactory(this))

    override fun onCastSessionAvailable() {
        setPlayer(player, castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setPlayer(castPlayer, player)
    }
}