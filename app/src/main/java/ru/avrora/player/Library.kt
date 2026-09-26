package ru.avrora.player

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Один трек из памяти телефона. id: номер файла в медиатеке Android. */
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val dur: Long,
    val albumId: Long = 0,
    /** Своя обложка (полный путь к файлу) или null. */
    val art: String? = null
) {
    fun toMediaItem(): MediaItem {
        val u = Uri.parse(uri)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(if (art != null) Uri.fromFile(File(art)) else Uri.parse("$uri/albumart"))
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(u)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
            .setMediaMetadata(meta)
            .build()
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("title", title)
        .put("artist", artist).put("album", album).put("dur", dur).put("albumId", albumId).put("art", art ?: "")

    companion object {
        fun from(o: JSONObject) = Track(
            o.getString("id"), o.getString("uri"), o.optString("title"),
            o.optString("artist"), o.optString("album"), o.optLong("dur"),
            o.optLong("albumId"), o.optString("art").ifEmpty { null }
        )
    }
}

/** Плейлист хранит только номера треков, по порядку. */
data class Playlist(val id: String, var name: String, val ids: MutableList<String>)

/** Всё, что приложение запоминает: медиатека, плейлисты, очередь, эквалайзер. */
object Library {
    const val UNKNOWN = "Неизвестный исполнитель"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("aurora", Context.MODE_PRIVATE)

    /** Находит всю музыку на телефоне через медиатеку Android. */
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        try {
            ctx.contentResolver.query(coll, proj, sel, null, sort)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val dur = c.getLong(4)
                    if (dur in 1 until 15_000) continue // короткие звуки уведомлений не нужны
                    var title = c.getString(1)
                    var artist = c.getString(2)
                    var album = c.getString(3) ?: ""
                    if (title.isNullOrBlank()) title = (c.getString(5) ?: "Без названия").substringBeforeLast('.')
                    if (artist.isNullOrBlank() || artist == "<unknown>") artist = UNKNOWN
                    if (album == "<unknown>") album = ""
                    out.add(Track(id.toString(), ContentUris.withAppendedId(coll, id).toString(), title, artist, album, dur, c.getLong(6)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---------- плейлисты ----------

    fun loadPlaylists(ctx: Context): MutableList<Playlist> {
        val s = prefs(ctx).getString("playlists", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) {
                val o = a.getJSONObject(it)
                val ids = o.getJSONArray("ids")
                Playlist(o.getString("id"), o.getString("name"), MutableList(ids.length()) { i -> ids.getString(i) })
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePlaylists(ctx: Context, list: List<Playlist>) {
        prefs(ctx).edit().putString("playlists", playlistsJson(list).toString()).apply()
    }

    fun playlistsJson(list: List<Playlist>): JSONArray {
        val a = JSONArray()
        list.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("ids", JSONArray(p.ids))) }
        return a
    }

    // ---------- очередь (чтобы продолжить с того же места) ----------

    fun saveQueue(ctx: Context, p: Player) {
        val a = JSONArray()
        for (i in 0 until p.mediaItemCount) {
            val m = p.getMediaItemAt(i)
            val u = m.localConfiguration?.uri ?: m.requestMetadata.mediaUri ?: continue
            a.put(
                Track(
                    m.mediaId, u.toString(), m.mediaMetadata.title?.toString() ?: "",
                    m.mediaMetadata.artist?.toString() ?: "", m.mediaMetadata.albumTitle?.toString() ?: "", 0
                ).toJson()
            )
        }
        prefs(ctx).edit().putString("queue", a.toString())
            .putInt("qIndex", p.currentMediaItemIndex).putLong("qPos", p.currentPosition).apply()
    }

    fun loadQueue(ctx: Context): Triple<List<Track>, Int, Long>? {
        val p = prefs(ctx)
        val s = p.getString("queue", null) ?: return null
        return try {
            val a = JSONArray(s)
            if (a.length() == 0) return null
            val list = List(a.length()) { Track.from(a.getJSONObject(it)) }
            Triple(list, p.getInt("qIndex", 0).coerceIn(0, list.size - 1), p.getLong("qPos", 0))
        } catch (e: Exception) {
            null
        }
    }

    fun loadSource(ctx: Context): String = prefs(ctx).getString("qSource", "") ?: ""
    fun saveSource(ctx: Context, s: String) = prefs(ctx).edit().putString("qSource", s).apply()
}


/**
 * Твои правки: свои названия и обложки для треков и альбомов.
 * Файлы с музыкой не меняются, всё хранится только в приложении.
 */
object Custom {
    private var data: JSONObject? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "custom").apply { mkdirs() }

    @Synchronized private fun get(ctx: Context): JSONObject {
        data?.let { return it }
        val s = Library.prefs(ctx).getString("custom", null)
        val o = try { if (s != null) JSONObject(s) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (!o.has("t")) o.put("t", JSONObject())
        if (!o.has("a")) o.put("a", JSONObject())
        data = o
        return o
    }

    @Synchronized private fun save(ctx: Context) {
        Library.prefs(ctx).edit().putString("custom", get(ctx).toString()).apply()
    }

    private fun entry(ctx: Context, kind: String, key: String): JSONObject {
        val all = get(ctx).getJSONObject(kind)
        return all.optJSONObject(key) ?: JSONObject().also { all.put(key, it) }
    }

    /** Трек с учётом правок. */
    fun apply(ctx: Context, t: Track): Track {
        val o = get(ctx)
        val to = o.getJSONObject("t").optJSONObject(t.id)
        val ao = o.getJSONObject("a").optJSONObject(t.albumId.toString())
        val cover = to?.optString("cover")?.ifEmpty { null } ?: ao?.optString("cover")?.ifEmpty { null }
        return t.copy(
            title = to?.optString("title")?.ifEmpty { null } ?: t.title,
            artist = to?.optString("artist")?.ifEmpty { null } ?: t.artist,
            album = ao?.optString("name")?.ifEmpty { null } ?: t.album,
            art = cover?.let { File(dir(ctx), it) }?.takeIf { it.exists() }?.absolutePath
        )
    }

    @Synchronized fun editTrack(ctx: Context, id: String, title: String, artist: String) {
        entry(ctx, "t", id).put("title", title.trim()).put("artist", artist.trim())
        save(ctx)
    }

    @Synchronized fun editAlbum(ctx: Context, albumId: String, name: String) {
        entry(ctx, "a", albumId).put("name", name.trim())
        save(ctx)
    }

    /** kind: "t" (трек) или "a" (альбом). Старая картинка удаляется. */
    @Synchronized fun setCover(ctx: Context, kind: String, key: String, fileName: String) {
        val e = entry(ctx, kind, key)
        e.optString("cover").ifEmpty { null }?.let { File(dir(ctx), it).delete() }
        e.put("cover", fileName)
        save(ctx)
    }

    @Synchronized fun reset(ctx: Context, kind: String, key: String) {
        val all = get(ctx).getJSONObject(kind)
        all.optJSONObject(key)?.optString("cover")?.ifEmpty { null }?.let { File(dir(ctx), it).delete() }
        all.remove(key)
        save(ctx)
    }

    fun has(ctx: Context, kind: String, key: String) = get(ctx).getJSONObject(kind).has(key)
}

/**
 * Статистика для «Рекомендаций Авроры»: сколько раз трек дослушан
 * хотя бы до середины (или 30 секунд), когда слушал последний раз
 * и даты прослушиваний за последний месяц.
 */
object Stats {
    private const val MONTH = 30L * 24 * 3600 * 1000
    private var data: JSONObject? = null

    @Synchronized private fun get(ctx: Context): JSONObject {
        data?.let { return it }
        val s = Library.prefs(ctx).getString("stats", null)
        return (try { if (s != null) JSONObject(s) else JSONObject() } catch (e: Exception) { JSONObject() }).also { data = it }
    }

    @Synchronized fun record(ctx: Context, id: String) {
        val all = get(ctx)
        val now = System.currentTimeMillis()
        val e = all.optJSONObject(id) ?: JSONObject()
        val old = e.optJSONArray("w") ?: JSONArray()
        val w = JSONArray()
        for (i in 0 until old.length()) { val t = old.optLong(i); if (now - t < MONTH) w.put(t) }
        w.put(now)
        e.put("c", e.optInt("c") + 1).put("l", now).put("w", w)
        all.put(id, e)
        Library.prefs(ctx).edit().putString("stats", all.toString()).apply()
    }

    @Synchronized fun json(ctx: Context): String = get(ctx).toString()
}
