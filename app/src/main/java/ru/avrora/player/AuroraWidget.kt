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

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val p = PlaybackService.instance?.exo
        if (p != null) update(context, p) else push(context, null, null, false)
    }

    companion object {
        fun update(ctx: Context, p: Player) {
            val m = p.mediaMetadata
            push(ctx, m.title?.toString(), m.artist?.toString(), p.playWhenReady && p.playbackState != Player.STATE_ENDED)
        }

        fun push(ctx: Context, title: String?, artist: String?, playing: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val cn = ComponentName(ctx, AuroraWidget::class.java)
            if (mgr.getAppWidgetIds(cn).isEmpty()) return

            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_title, title ?: "Аврора")
            v.setTextViewText(R.id.w_artist, artist ?: "Нажми ▶, чтобы продолжить")
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            v.setOnClickPendingIntent(R.id.w_play, key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
            v.setOnClickPendingIntent(R.id.w_prev, key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 2))
            v.setOnClickPendingIntent(R.id.w_next, key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, 3))
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_sun, open)
            v.setOnClickPendingIntent(R.id.w_info, open)
            mgr.updateAppWidget(cn, v)
        }

        /** Кнопки виджета работают как кнопки на наушниках. */
        private fun key(ctx: Context, code: Int, req: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(ctx, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
