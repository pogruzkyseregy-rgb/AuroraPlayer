package ru.avrora.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Главный экран. Интерфейс нарисован в assets/www/index.html,
 * а этот класс связывает его с плеером:
 *   страница -> Android: объект Android (класс Bridge ниже)
 *   Android -> страница: функции window.fromAndroid.*
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val HOST = "https://appassets.androidplatform.net"
        val NO_ART = ByteArray(0)
    }

    private lateinit var web: WebView
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false

    private val raw = mutableListOf<Track>()              // как в медиатеке
    private val lib = LinkedHashMap<String, Track>()      // с твоими правками
    private val customArt = ConcurrentHashMap<String, String>()
    private var pickTarget: Pair<String, String>? = null  // ("t", id) или ("a", albumId)
    private val hidden = mutableSetOf<String>()           // скрытые из медиатеки
    private var pendingDelete: String? = null             // трек, который удаляем
    private val playlists = mutableListOf<Playlist>()
    private var queueSource = ""
    private var scanning = false
    private val artCache = LruCache<String, ByteArray>(80)

    private val audioPerm =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scan() else pushLibrary()
        askNotificationsOnce()
    }

    /** Один раз спрашиваем разрешение на уведомление с кнопками плеера (Android 13+). */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val p = Library.prefs(this)
        if (p.getBoolean("notifAsked", false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        p.edit().putBoolean("notifAsked", true).apply()
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pickTarget
        pickTarget = null
        if (uri != null && target != null) saveCover(target.first, target.second, uri)
    }

    // Системное окно «Разрешить удаление?» (Android 10+)
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val id = pendingDelete ?: return@registerForActivityResult
            pendingDelete = null
            if (r.resultCode != Activity.RESULT_OK) { js("fromAndroid.deleted(false)"); return@registerForActivityResult }
            if (Build.VERSION.SDK_INT == 29) {
                // на Android 10 после разрешения удаляем сами
                val t = raw.firstOrNull { it.id == id }
                val ok = t != null && try { contentResolver.delete(Uri.parse(t.uri), null, null) > 0 } catch (e: Exception) { false }
                if (!ok) { js("fromAndroid.deleted(false)"); return@registerForActivityResult }
            }
            afterDeleted(id)
        }

    // Разрешение на запись для Android 8–9
    private val writePermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val id = pendingDelete
        if (ok && id != null) deleteDirect(id) else { pendingDelete = null; js("fromAndroid.deleted(false)") }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ticker = object : Runnable {
        override fun run() { pushState(); main.postDelayed(this, 500) }
    }

    private fun hasPerm() =
        ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlists.addAll(Library.loadPlaylists(this))
        queueSource = Library.loadSource(this)
        hidden.addAll(Library.loadHidden(this))

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/art/") { path -> artResponse(path) }
            .addPathHandler("/custom/", WebViewAssetLoader.InternalStoragePathHandler(this, Custom.dir(this)))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(0xFF1A1433.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("$HOST/assets/www/index.html")
        }
        setContentView(web)

        // «Назад» сначала закрывает окна на странице, потом сворачивает приложение
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.fromAndroid ? fromAndroid.back() : false") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })

        if (hasPerm()) { scan(); askNotificationsOnce() }
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
            if (c.mediaItemCount == 0) {
                Library.loadQueue(this)?.let { (tracks, index, pos) ->
                    c.setMediaItems(tracks.map { it.toMediaItem() }, index, pos)
                    c.prepare()
                }
            }
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

    // ---------- медиатека ----------

    private fun scan() {
        if (scanning) return
        scanning = true
        pushLibrary()
        Thread {
            val found = Library.scan(this)
            main.post {
                scanning = false
                raw.clear()
                raw.addAll(found)
                rebuildLib()
                // удалённые с телефона треки убираем из плейлистов
                if (found.isNotEmpty()) {
                    val all = found.map { it.id }.toHashSet()
                    playlists.forEach { p -> p.ids.retainAll(all) }
                    Library.savePlaylists(this, playlists)
                }
                pushLibrary()
                pushPlaylists()
            }
        }.start()
    }

    /** Обложка трека для страницы: https://appassets.androidplatform.net/art/<id> */
    private fun artResponse(path: String): WebResourceResponse {
        val id = path.substringBefore('?')
        var bytes = artCache.get(id)
        if (bytes == null) {
            bytes = loadArt(id) ?: NO_ART
            artCache.put(id, bytes)
        }
        if (bytes.isEmpty()) {
            return WebResourceResponse("image/jpeg", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(bytes))
        }
        return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
    }

    private fun loadArt(id: String): ByteArray? {
        customArt[id]?.let { path -> return try { File(path).readBytes() } catch (e: Exception) { null } }
        if (Build.VERSION.SDK_INT < 29) return null
        val n = id.toLongOrNull() ?: return null
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, n)
            val bmp = contentResolver.loadThumbnail(uri, Size(256, 256), null)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    // ---------- свои названия и обложки ----------

    private fun rebuildLib() {
        lib.clear()
        customArt.clear()
        raw.forEach { r ->
            if (r.id in hidden) return@forEach
            val t = Custom.apply(this, r)
            lib[t.id] = t
            t.art?.let { customArt[t.id] = it }
        }
        artCache.evictAll()
    }

    /** После правки: обновить список на экране и треки в очереди (уведомление, экран блокировки). */
    private fun afterEdit(ids: Collection<String>) {
        rebuildLib()
        pushLibrary()
        val exo = PlaybackService.instance?.exo ?: return
        val set = ids.toHashSet()
        for (i in 0 until exo.mediaItemCount) {
            val id = exo.getMediaItemAt(i).mediaId
            if (id in set) lib[id]?.let { exo.replaceMediaItem(i, it.toMediaItem()) }
        }
    }

    private fun albumTracks(albumId: String) = raw.filter { it.albumId.toString() == albumId }.map { it.id }

    /** Картинку из галереи обрезаем до квадрата 512×512 и сохраняем в приложении. */
    private fun saveCover(kind: String, key: String, uri: Uri) {
        Thread {
            val name = try {
                val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                        val k = maxOf(1, minOf(info.size.width, info.size.height) / 1024)
                        d.setTargetSize(info.size.width / k, info.size.height / k)
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    val k = maxOf(1, minOf(o.outWidth, o.outHeight) / 1024)
                    contentResolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = k })!!
                    }
                }
                if (kind == "bg") {
                    // фон для эквалайзера: без обрезки, длинная сторона до 1600 пикселей
                    val k = maxOf(1f, maxOf(src.width, src.height) / 1600f)
                    val out = Bitmap.createScaledBitmap(src, (src.width / k).toInt(), (src.height / k).toInt(), true)
                    val n = "bg_${System.currentTimeMillis()}.jpg"
                    Custom.dir(this).listFiles()?.filter { it.name.startsWith("bg_") }?.forEach { it.delete() }
                    File(Custom.dir(this), n).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    n
                } else {
                    val side = minOf(src.width, src.height)
                    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val out = Bitmap.createScaledBitmap(sq, 512, 512, true)
                    val n = "${kind}_${key}_${System.currentTimeMillis()}.jpg"
                    File(Custom.dir(this), n).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    n
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (name == null) { js("fromAndroid.saved('fail')"); return@post }
                if (kind == "bg") { js("fromAndroid.bgSaved('$name')"); return@post }
                Custom.setCover(this, kind, key, name)
                afterEdit(if (kind == "t") listOf(key) else albumTracks(key))
                js("fromAndroid.saved('cover')")
            }
        }.start()
    }

    // ---------- скрыть и удалить ----------

    private fun hideTrack(id: String) {
        hidden.add(id)
        Library.saveHidden(this, hidden)
        rebuildLib()
        pushLibrary()
        pushPlaylists()
    }

    private fun unhideAll() {
        hidden.clear()
        Library.saveHidden(this, hidden)
        rebuildLib()
        pushLibrary()
        pushPlaylists()
    }

    /** Удаление файла с телефона. На Android 10+ система сама спросит подтверждение. */
    private fun deleteTrack(id: String) {
        val t = raw.firstOrNull { it.id == id } ?: return
        val uri = Uri.parse(t.uri)
        pendingDelete = id
        try {
            when {
                Build.VERSION.SDK_INT >= 30 -> {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                Build.VERSION.SDK_INT == 29 -> deleteQ(id, uri)
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED -> deleteDirect(id)
                else -> writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } catch (e: Exception) {
            pendingDelete = null
            js("fromAndroid.deleted(false)")
        }
    }

    @RequiresApi(29)
    private fun deleteQ(id: String, uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            pendingDelete = null
            afterDeleted(id)
        } catch (e: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        }
    }

    private fun deleteDirect(id: String) {
        pendingDelete = null
        val t = raw.firstOrNull { it.id == id } ?: return
        val ok = try { contentResolver.delete(Uri.parse(t.uri), null, null) > 0 } catch (e: Exception) { false }
        if (ok) afterDeleted(id) else js("fromAndroid.deleted(false)")
    }

    /** Файл удалён: убираем трек из очереди, плейлистов, статистики и правок. */
    private fun afterDeleted(id: String) {
        PlaybackService.instance?.exo?.let { exo ->
            for (i in exo.mediaItemCount - 1 downTo 0) if (exo.getMediaItemAt(i).mediaId == id) exo.removeMediaItem(i)
        }
        raw.removeAll { it.id == id }
        playlists.forEach { it.ids.remove(id) }
        Library.savePlaylists(this, playlists)
        Custom.reset(this, "t", id)
        Stats.forget(this, id)
        if (hidden.remove(id)) Library.saveHidden(this, hidden)
        rebuildLib()
        pushLibrary()
        pushPlaylists()
        pushState()
        js("fromAndroid.deleted(true)")
    }

    // ---------- очередь ----------

    private fun playQueue(idsJson: String, index: Int, source: String) {
        val c = controller ?: return
        val arr = JSONArray(idsJson)
        val ids = List(arr.length()) { arr.getString(it) }.filter { lib.containsKey(it) }
        if (ids.isEmpty()) return
        val i = index.coerceIn(0, ids.size - 1)
        val same = source == queueSource && c.mediaItemCount == ids.size &&
            ids.indices.all { c.getMediaItemAt(it).mediaId == ids[it] }
        if (same) c.seekToDefaultPosition(i)
        else {
            c.setMediaItems(ids.map { lib[it]!!.toMediaItem() }, i, 0)
            setSource(source)
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun setSource(s: String) {
        queueSource = s
        Library.saveSource(this, s)
    }

    /** Играет ли сейчас очередь из этого плейлиста, и совпадает ли она с ним. */
    private fun queueIs(p: Playlist): Boolean {
        val c = controller ?: return false
        return queueSource == "pl:${p.id}" && c.mediaItemCount == p.ids.size
    }

    // ---------- Android -> страница ----------

    private fun js(code: String) = web.evaluateJavascript("window.fromAndroid && $code", null)

    private fun pushLibrary() {
        if (!pageReady) return
        val a = JSONArray()
        lib.values.forEach { t ->
            a.put(
                JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
                    .put("dur", t.dur).put("albumId", t.albumId.toString())
                    .put("av", t.art?.substringAfterLast('_')?.substringBefore('.') ?: "0")
                    .put("tc", Custom.has(this, "t", t.id)).put("ac", Custom.has(this, "a", t.albumId.toString()))
            )
        }
        js("fromAndroid.library($a, ${hasPerm()}, $scanning, ${hidden.size})")
    }

    private fun pushPlaylists() {
        if (!pageReady) return
        js("fromAndroid.playlists(${Library.playlistsJson(playlists)})")
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
            .put("source", queueSource).put("sleep", PlaybackService.instance?.sleepLeft() ?: 0)
        if (c == null || c.mediaItemCount == 0) {
            o.put("playing", false).put("moving", false).put("ended", false)
                .put("currentId", "").put("title", "").put("artist", "").put("pos", 0).put("dur", 0)
        } else {
            val ended = c.playbackState == Player.STATE_ENDED
            val m = c.mediaMetadata
            o.put("playing", c.playWhenReady && !ended)
                .put("moving", c.isPlaying)
                .put("ended", ended)
                .put("currentId", c.currentMediaItem?.mediaId ?: "")
                .put("title", m.title?.toString() ?: "")
                .put("artist", m.artist?.toString() ?: "")
                .put("pos", c.currentPosition)
                .put("dur", if (c.duration == C.TIME_UNSET) 0 else c.duration)
        }
        js("fromAndroid.state($o)")
    }

    // ---------- страница -> Android ----------

    inner class Bridge {
        private fun ui(block: MediaController.() -> Unit) {
            main.post { controller?.block() }
        }

        private fun MediaController.start() {
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }

        private fun pl(id: String) = playlists.firstOrNull { it.id == id }

        @JavascriptInterface fun ready() {
            main.post { pageReady = true; pushLibrary(); pushPlaylists(); pushState() }
        }

        // медиатека
        @JavascriptInterface fun askPermission() {
            main.post { if (hasPerm()) scan() else permLauncher.launch(audioPerm) }
        }

        @JavascriptInterface fun rescan() {
            main.post { if (hasPerm()) scan() else permLauncher.launch(audioPerm) }
        }

        @JavascriptInterface fun playList(idsJson: String, index: Int, source: String) {
            main.post { playQueue(idsJson, index, source) }
        }

        // управление
        @JavascriptInterface fun toggle() = ui {
            if (playWhenReady && playbackState != Player.STATE_ENDED) pause()
            else {
                if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(0)
                start()
            }
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

        // плейлисты
        @JavascriptInterface fun newPlaylist(name: String): String {
            val id = UUID.randomUUID().toString()
            main.post {
                playlists.add(Playlist(id, name.trim().ifEmpty { "Новый плейлист" }, mutableListOf()))
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
            }
            return id
        }

        @JavascriptInterface fun renamePlaylist(id: String, name: String) {
            main.post {
                pl(id)?.let { it.name = name.trim().ifEmpty { it.name } }
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
            }
        }

        @JavascriptInterface fun deletePlaylist(id: String) {
            main.post {
                playlists.removeAll { it.id == id }
                if (queueSource == "pl:$id") setSource("")
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
            }
        }

        @JavascriptInterface fun addToPlaylist(id: String, trackId: String) {
            main.post {
                val p = pl(id) ?: return@post
                val t = lib[trackId] ?: return@post
                if (trackId in p.ids) return@post
                val synced = queueIs(p)
                p.ids.add(trackId)
                if (synced) controller?.addMediaItem(t.toMediaItem())
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
            }
        }

        @JavascriptInterface fun removeFromPlaylist(id: String, index: Int) {
            main.post {
                val p = pl(id) ?: return@post
                if (index !in p.ids.indices) return@post
                val synced = queueIs(p)
                p.ids.removeAt(index)
                if (synced) controller?.removeMediaItem(index)
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
                pushState()
            }
        }

        @JavascriptInterface fun movePlaylist(id: String, from: Int, to: Int) {
            main.post {
                val p = pl(id) ?: return@post
                if (from !in p.ids.indices || to !in p.ids.indices || from == to) return@post
                val synced = queueIs(p)
                p.ids.add(to, p.ids.removeAt(from))
                if (synced) controller?.moveMediaItem(from, to)
                Library.savePlaylists(this@MainActivity, playlists)
                pushPlaylists()
            }
        }

        // таймер сна: минуты, -1 = до конца трека, 0 = выключить
        @JavascriptInterface fun sleep(minutes: Int) {
            main.post { PlaybackService.instance?.setSleep(minutes); pushState() }
        }

        // эквалайзер
        @JavascriptInterface fun eqState(): String = PlaybackService.instance?.eqJson() ?: "{\"ok\":false}"
        @JavascriptInterface fun eqPreset(i: Int) { main.post { PlaybackService.instance?.eqPreset(i) } }
        @JavascriptInterface fun eqBand(i: Int, level: Int) { main.post { PlaybackService.instance?.eqBand(i, level) } }
        @JavascriptInterface fun eqBass(v: Int) { main.post { PlaybackService.instance?.setBass(v) } }
        @JavascriptInterface fun eqReset() { main.post { PlaybackService.instance?.eqReset() } }

        // свои названия и обложки
        @JavascriptInterface fun editTrack(id: String, title: String, artist: String) {
            main.post { Custom.editTrack(this@MainActivity, id, title, artist); afterEdit(listOf(id)) }
        }

        @JavascriptInterface fun editAlbum(albumId: String, name: String) {
            main.post { Custom.editAlbum(this@MainActivity, albumId, name); afterEdit(albumTracks(albumId)) }
        }

        @JavascriptInterface fun pickBackground() {
            main.post { pickTarget = "bg" to ""; imagePicker.launch("image/*") }
        }

        @JavascriptInterface fun pickCover(kind: String, key: String) {
            main.post { pickTarget = kind to key; imagePicker.launch("image/*") }
        }

        @JavascriptInterface fun resetCustom(kind: String, key: String) {
            main.post {
                Custom.reset(this@MainActivity, kind, key)
                afterEdit(if (kind == "t") listOf(key) else albumTracks(key))
            }
        }

        // скрыть и удалить
        @JavascriptInterface fun hideTrack(id: String) { main.post { this@MainActivity.hideTrack(id) } }
        @JavascriptInterface fun unhideAll() { main.post { this@MainActivity.unhideAll() } }
        @JavascriptInterface fun deleteTrack(id: String) { main.post { this@MainActivity.deleteTrack(id) } }

        // рекомендации
        @JavascriptInterface fun stats(): String = Stats.json(this@MainActivity)

        @JavascriptInterface fun levels(): String = Spectrum.levels()
    }
}
