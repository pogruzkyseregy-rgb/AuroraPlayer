package ru.avrora.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Один трек в плейлисте. cover: имя файла обложки в папке covers или null. */
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val cover: String?
) {
    fun toMediaItem(ctx: Context): MediaItem {
        val u = Uri.parse(uri)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle("Аврора")
        cover?.let { meta.setArtworkUri(Uri.fromFile(File(TrackStore.coverDir(ctx), it))) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(u)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
            .setMediaMetadata(meta.build())
            .build()
    }
}

/** Плейлист хранится в настройках приложения, обложки лежат файлами. */
object TrackStore {
    private const val PREFS = "aurora"
    private const val KEY = "tracks"
    const val UNKNOWN_ARTIST = "Неизвестный исполнитель"

    fun coverDir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }

    fun load(ctx: Context): List<Track> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Track(
                    o.getString("id"), o.getString("uri"), o.getString("title"),
                    o.getString("artist"), o.optString("cover").ifEmpty { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<Track>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("uri", it.uri).put("title", it.title)
                    .put("artist", it.artist).put("cover", it.cover ?: "")
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Читает название, исполнителя и обложку из тегов файла. */
    fun read(ctx: Context, uri: Uri): Track {
        val id = UUID.randomUUID().toString()
        var title: String? = null
        var artist: String? = null
        var cover: String? = null

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            r.embeddedPicture?.let { bytes ->
                File(coverDir(ctx), "$id.jpg").writeBytes(bytes)
                cover = "$id.jpg"
            }
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }

        // Если тегов нет, берём имя файла вида "Исполнитель - Название.mp3"
        if (title.isNullOrBlank()) {
            val base = fileName(ctx, uri).substringBeforeLast('.').replace('_', ' ')
            val parts = base.split(" - ", limit = 2)
            if (parts.size == 2) {
                if (artist.isNullOrBlank()) artist = parts[0].trim()
                title = parts[1].trim()
            } else {
                title = base
            }
        }
        return Track(id, uri.toString(), title!!, artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST, cover)
    }

    private fun fileName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "Без названия"
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Без названия"
    }
}
