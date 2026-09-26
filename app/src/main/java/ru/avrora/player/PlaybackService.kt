package ru.avrora.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Сервис, который играет музыку в фоне. Здесь же живут
 * таймер сна и эквалайзер, чтобы работать при свёрнутом приложении.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        /** Работающий сервис (или null). Экран и виджет обращаются к нему напрямую. */
        var instance: PlaybackService? = null
            private set

        /** Сколько длится плавное затухание перед сном. */
        private const val FADE_MS = 30_000L
    }

    private var session: MediaSession? = null
    lateinit var exo: ExoPlayer
        private set
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(Spectrum)))
                .build()
        }

        exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Таймер «до конца трека» сработал
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfTrack) cancelSleep()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED
                    )
                ) AuroraWidget.update(this@PlaybackService, player)
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_TIMELINE_CHANGED
                    )
                ) Library.saveQueue(this@PlaybackService, player)
            }
        })

        initFx()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(
                        mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
                    )

                // Play с виджета, наушников или экрана блокировки, когда приложение было закрыто
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val q = Library.loadQueue(this@PlaybackService)
                        ?: return Futures.immediateFailedFuture(IllegalStateException("Очередь пуста"))
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(q.first.map { it.toMediaItem() }, q.second, q.third)
                    )
                }
            })
            .build()

        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        session?.run { player.release(); release() }
        session = null
        AuroraWidget.push(this, null, null, false)
        super.onDestroy()
    }

    // ================= Таймер сна =================

    private var sleepAt = 0L
    private var sleepEndOfTrack = false

    private val fade = object : Runnable {
        override fun run() {
            val left = sleepAt - System.currentTimeMillis()
            when {
                left <= 0 -> { exo.pause(); cancelSleep() }
                left <= FADE_MS -> { exo.volume = (left.toFloat() / FADE_MS).coerceIn(0f, 1f); handler.postDelayed(this, 200) }
                else -> handler.postDelayed(this, left - FADE_MS)
            }
        }
    }

    /** minutes > 0: через сколько минут; -1: после текущего трека; 0: выключить. */
    fun setSleep(minutes: Int) {
        cancelSleep()
        if (minutes == -1) {
            sleepEndOfTrack = true
            exo.pauseAtEndOfMediaItems = true
        } else if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L
            handler.post(fade)
        }
    }

    fun cancelSleep() {
        handler.removeCallbacks(fade)
        sleepAt = 0
        sleepEndOfTrack = false
        exo.pauseAtEndOfMediaItems = false
        exo.volume = 1f
    }

    /** Для экрана: -1 = до конца трека, 0 = выключен, иначе миллисекунды до сна. */
    fun sleepLeft(): Long = when {
        sleepEndOfTrack -> -1
        sleepAt > 0 -> max(1, sleepAt - System.currentTimeMillis())
        else -> 0
    }

    // ================= Эквалайзер =================

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    private fun initFx() {
        val sid = exo.audioSessionId
        eq = try { Equalizer(0, sid).also { it.setEnabled(true) } } catch (e: Throwable) { null }
        bass = try { BassBoost(0, sid).also { it.setEnabled(true) } } catch (e: Throwable) { null }
        val p = Library.prefs(this)
        eq?.let { e ->
            try {
                val preset = p.getInt("eqPreset", -1)
                if (preset >= 0 && preset < e.numberOfPresets) e.usePreset(preset.toShort())
                else p.getString("eqBands", null)?.split(",")?.forEachIndexed { i, s ->
                    if (i < e.numberOfBands) e.setBandLevel(i.toShort(), s.toShort())
                }
            } catch (_: Throwable) {}
        }
        bass?.let { b -> try { if (b.strengthSupported) b.setStrength(p.getInt("bass", 0).toShort()) } catch (_: Throwable) {} }
    }

    fun eqJson(): String {
        val o = JSONObject()
        val p = Library.prefs(this)
        val e = eq
        try {
            if (e == null) o.put("ok", false) else {
                val r = e.bandLevelRange
                o.put("ok", true).put("min", r[0].toInt()).put("max", r[1].toInt())
                val bands = JSONArray()
                for (i in 0 until e.numberOfBands) {
                    bands.put(JSONObject().put("hz", e.getCenterFreq(i.toShort()) / 1000).put("level", e.getBandLevel(i.toShort()).toInt()))
                }
                val presets = JSONArray()
                for (i in 0 until e.numberOfPresets) presets.put(e.getPresetName(i.toShort()))
                o.put("bands", bands).put("presets", presets).put("preset", p.getInt("eqPreset", -1))
            }
        } catch (t: Throwable) {
            o.put("ok", false)
        }
        val b = bass
        o.put("bassOk", try { b != null && b.strengthSupported } catch (t: Throwable) { false })
            .put("bass", p.getInt("bass", 0))
        return o.toString()
    }

    fun eqPreset(i: Int) {
        val e = eq ?: return
        try {
            e.usePreset(i.toShort())
            Library.prefs(this).edit().putInt("eqPreset", i).apply()
        } catch (_: Throwable) {}
    }

    fun eqBand(i: Int, level: Int) {
        val e = eq ?: return
        try {
            e.setBandLevel(i.toShort(), level.toShort())
            saveBands(e)
        } catch (_: Throwable) {}
    }

    fun eqReset() {
        eq?.let { e ->
            try {
                for (i in 0 until e.numberOfBands) e.setBandLevel(i.toShort(), 0)
                saveBands(e)
            } catch (_: Throwable) {}
        }
        setBass(0)
    }

    fun setBass(v: Int) {
        try { bass?.setStrength(v.coerceIn(0, 1000).toShort()) } catch (_: Throwable) {}
        Library.prefs(this).edit().putInt("bass", v).apply()
    }

    private fun saveBands(e: Equalizer) {
        val s = (0 until e.numberOfBands).joinToString(",") { e.getBandLevel(it.toShort()).toString() }
        Library.prefs(this).edit().putString("eqBands", s).putInt("eqPreset", -1).apply()
    }
}
