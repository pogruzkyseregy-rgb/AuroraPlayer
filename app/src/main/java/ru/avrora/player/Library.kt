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

/** Один трек из памяти телефона. id: номер файла в медиатеке Android. */
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val dur: Long
) {
    fun toMediaItem(): MediaItem {
        val u = Uri.parse(uri)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(Uri.parse("$uri/albumart")) // обложка для уведомления
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(u)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
            .setMediaMetadata(meta)
            .build()
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("title", title)
        .put("artist", artist).put("album", album).put("dur", dur)

    companion object {
        fun from(o: JSONObject) = Track(
            o.getString("id"), o.getString("uri"), o.optString("title"),
            o.optString("artist"), o.optString("album"), o.optLong("dur")
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
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME
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
                    out.add(Track(id.toString(), ContentUris.withAppendedId(coll, id).toString(), title, artist, album, dur))
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
