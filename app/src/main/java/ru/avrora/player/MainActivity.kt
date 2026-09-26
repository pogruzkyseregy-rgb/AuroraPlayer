package ru.avrora.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Главный экран. Интерфейс нарисован в assets/www/index.html,
 * а этот класс связывает его с плеером:
 *   страница -> Android: через объект Android (класс Bridge ниже)
 *   Android -> страница: через функции window.fromAndroid.*
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val HOST = "https://appassets.androidplatform.net"
    }

    private lateinit var web: WebView
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val tracks = mutableListOf<Track>()
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importTracks(uris)
        }

    // Раз в полсекунды сообщаем странице позицию в треке
    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            main.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracks.addAll(TrackStore.load(this))

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/covers/", WebViewAssetLoader.InternalStoragePathHandler(this, TrackStore.coverDir(this)))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(0xFF1A1433.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient() // нужен для окна подтверждения confirm()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("$HOST/assets/www/index.html")
        }
        setContentView(web)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = pushState()
            })
            if (c.mediaItemCount == 0 && tracks.isNotEmpty()) {
                c.setMediaItems(tracks.map { it.toMediaItem(this) })
                c.prepare()
            }
            pushTracks()
            pushState()
            main.post(ticker)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---------- плейлист ----------

    private fun importTracks(uris: List<Uri>) {
        Thread {
            val added = uris.map { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {}
                TrackStore.read(this, uri)
            }
            main.post {
                val wasEmpty = (controller?.mediaItemCount ?: 0) == 0
                tracks.addAll(added)
                TrackStore.save(this, tracks)
                controller?.let { c ->
                    c.addMediaItems(added.map { it.toMediaItem(this) })
                    if (wasEmpty) c.prepare()
                }
                pushTracks()
                js("fromAndroid.added(${added.size})")
                pushState()
            }
        }.start()
    }

    private fun removeTrack(i: Int) {
        if (i !in tracks.indices) return
        val t = tracks.removeAt(i)
        forgetFiles(t)
        TrackStore.save(this, tracks)
        controller?.removeMediaItem(i)
        pushTracks()
        pushState()
    }

    private fun clearTracks() {
        controller?.clearMediaItems()
        tracks.forEach { forgetFiles(it) }
        tracks.clear()
        TrackStore.save(this, tracks)
        pushTracks()
        pushState()
    }

    private fun forgetFiles(t: Track) {
        t.cover?.let { File(TrackStore.coverDir(this), it).delete() }
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(t.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    // ---------- Android -> страница ----------

    private fun js(code: String) = web.evaluateJavascript("window.fromAndroid && $code", null)

    private fun pushTracks() {
        if (!pageReady) return
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject().put("title", t.title).put("artist", t.artist)
                    .put("cover", t.cover?.let { "$HOST/covers/$it" } ?: JSONObject.NULL)
            )
        }
        js("fromAndroid.tracks($arr)")
    }

    private fun pushState() {
        if (!pageReady) return
        val c = controller
        val o = JSONObject()
        val repeat = when (c?.repeatMode) {
            Player.REPEAT_MODE_ALL -> "all"
            Player.REPEAT_MODE_ONE -> "one"
            else -> "off"
        }
        o.put("shuffle", c?.shuffleModeEnabled ?: false).put("repeat", repeat)
        if (c == null || c.mediaItemCount == 0) {
            o.put("playing", false).put("moving", false).put("ended", false)
                .put("index", -1).put("pos", 0).put("dur", 0)
        } else {
            val ended = c.playbackState == Player.STATE_ENDED
            o.put("playing", c.playWhenReady && !ended)
                .put("moving", c.isPlaying)
                .put("ended", ended)
                .put("index", c.currentMediaItemIndex)
                .put("pos", c.currentPosition)
                .put("dur", if (c.duration == C.TIME_UNSET) 0 else c.duration)
        }
        js("fromAndroid.state($o)")
    }

    // ---------- страница -> Android ----------
    // Эти методы вызываются из JavaScript как Android.toggle(), Android.next() и т.д.

    inner class Bridge {
        private fun ui(block: MediaController.() -> Unit) {
            main.post { controller?.block() }
        }

        private fun MediaController.start() {
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }

        @JavascriptInterface fun ready() {
            main.post { pageReady = true; pushTracks(); pushState() }
        }

        @JavascriptInterface fun pick() {
            main.post { picker.launch(arrayOf("audio/*")) }
        }

        @JavascriptInterface fun toggle() = ui {
            if (playWhenReady && playbackState != Player.STATE_ENDED) pause()
            else {
                if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(0)
                start()
            }
        }

        @JavascriptInterface fun playAt(i: Int) = ui {
            if (i in 0 until mediaItemCount) { seekToDefaultPosition(i); start() }
        }

        @JavascriptInterface fun next() = ui {
            if (hasNextMediaItem()) seekToNextMediaItem() else if (mediaItemCount > 0) seekToDefaultPosition(0)
            start()
        }

        @JavascriptInterface fun prev() = ui { seekToPrevious(); start() }

        @JavascriptInterface fun seek(ms: Double) = ui { seekTo(ms.toLong()) }

        @JavascriptInterface fun setShuffle(on: Boolean) = ui { shuffleModeEnabled = on }

        @JavascriptInterface fun setRepeat(mode: String) = ui {
            repeatMode = when (mode) {
                "all" -> Player.REPEAT_MODE_ALL
                "one" -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }

        @JavascriptInterface fun remove(i: Int) { main.post { removeTrack(i) } }

        @JavascriptInterface fun clear() { main.post { clearTracks() } }

        @JavascriptInterface fun levels(): String = Spectrum.levels()
    }
}
