package ru.avrora.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/** Виджет на рабочий стол: солнце, название трека и три кнопки. */
@OptIn(UnstableApi::class)
class AuroraWidget : AppWidgetProvider() {

    // «Вперёд» и «Назад» управляют плеером напрямую: через кнопочный канал
    // Media3 пропускает только Play, а остальные команды отбрасывает.
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEXT -> PlaybackService.instance?.exo?.run {
                if (hasNextMediaItem()) seekToNextMediaItem() else if (mediaItemCount > 0) seekToDefaultPosition(0)
                play()
            }
            ACTION_PREV -> PlaybackService.instance?.exo?.run {
                seekToPrevious()
                play()
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val p = PlaybackService.instance?.exo
        if (p != null) update(context, p) else push(context, null, null, false)
    }

    companion object {
        private const val ACTION_NEXT = "ru.avrora.player.WIDGET_NEXT"
        private const val ACTION_PREV = "ru.avrora.player.WIDGET_PREV"

        fun update(ctx: Context, p: Player) {
            val m = p.mediaMetadata
            push(ctx, m.title?.toString(), m.artist?.toString(), p.playWhenReady && p.playbackState != Player.STATE_ENDED)
        }

        fun push(ctx: Context, title: String?, artist: String?, playing: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val cn = ComponentName(ctx, AuroraWidget::class.java)
            if (mgr.getAppWidgetIds(cn).isEmpty()) return

            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, title ?: "fluorite_blue")
            v.setTextViewText(R.id.w_artist, artist ?: "Нажми ▶, чтобы продолжить")
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            v.setOnClickPendingIntent(R.id.w_play, key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
            v.setOnClickPendingIntent(R.id.w_prev, action(ctx, ACTION_PREV, 2))
            v.setOnClickPendingIntent(R.id.w_next, action(ctx, ACTION_NEXT, 3))
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_sun, open)
            v.setOnClickPendingIntent(R.id.w_info, open)
            mgr.updateAppWidget(cn, v)
        }

        private fun action(ctx: Context, act: String, req: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, req, Intent(ctx, AuroraWidget::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** Кнопка Play работает как кнопка на наушниках: запускает музыку, даже если приложение закрыто. */
        private fun key(ctx: Context, code: Int, req: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(ctx, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
