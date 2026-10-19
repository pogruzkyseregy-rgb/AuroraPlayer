package ru.avrora.player

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager

/**
 * Glyph-подсветка на задней панели Nothing Phone (2) в такт музыке.
 *
 * На любом другом телефоне ничего не делает: все обращения к SDK Nothing спрятаны
 * в GlyphImpl, а он вызывается только на Android 14+ и только если модель — Phone (2).
 * Любая ошибка SDK гасится здесь же: подсветка — украшение, плеер не должен из-за неё падать.
 *
 * Glyph-сервис Nothing обслуживает только приложение на переднем плане,
 * поэтому включаем в onResume и гасим в onPause.
 */
object GlyphViz {
    private fun supported() = Build.VERSION.SDK_INT >= 34

    fun init(ctx: Context) {
        if (!supported()) return
        try { GlyphImpl.init(ctx) } catch (_: Throwable) { }
    }

    fun resume() {
        if (!supported()) return
        try { GlyphImpl.resume() } catch (_: Throwable) { }
    }

    fun pause() {
        if (!supported()) return
        try { GlyphImpl.pause() } catch (_: Throwable) { }
    }

    fun destroy() {
        if (!supported()) return
        try { GlyphImpl.destroy() } catch (_: Throwable) { }
    }
}

/**
 * Каналы Phone (2) по номерам из документации Nothing (в SDK нет именованных констант):
 *   0 A1, 1 A2, 2 B1, 3..18 C1_1..C1_16 (длинная дуга), 19..23 C2..C6,
 *   24 E1 (точка снизу), 25..32 D1_1..D1_8 (полоска снизу вверх).
 * Яркость канала: 0..4000.
 */
private object GlyphImpl {
    private const val CHANNELS = 33
    private const val MAX = 4000
    private const val TICK_MS = 70L

    private var gm: GlyphManager? = null
    private var ready = false
    private var running = false
    private var lit = false
    private val handler = Handler(Looper.getMainLooper())
    private val cur = FloatArray(CHANNELS)      // текущая яркость 0..1, с плавным затуханием
    private val lv = FloatArray(Spectrum.BANDS)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { push() } catch (_: Throwable) { }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun init(ctx: Context) {
        if (gm != null || !Common.is22111()) return
        val mgr = GlyphManager.getInstance(ctx.applicationContext)
        gm = mgr
        mgr.init(object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                try {
                    mgr.register(Glyph.DEVICE_22111)
                    mgr.openSession()
                    ready = true
                    if (running) handler.post(tick)
                } catch (_: Throwable) { ready = false }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                ready = false
            }
        })
    }

    fun resume() {
        if (running) return
        running = true
        if (ready) handler.post(tick)   // если сервис ещё не подключился — запустится из onServiceConnected
    }

    fun pause() {
        running = false
        handler.removeCallbacks(tick)
        cur.fill(0f)
        off()
    }

    fun destroy() {
        pause()
        val mgr = gm ?: return
        try { mgr.closeSession() } catch (_: Throwable) { }
        try { mgr.unInit() } catch (_: Throwable) { }
        gm = null
        ready = false
    }

    private fun off() {
        if (!lit) return
        lit = false
        try { gm?.turnOff() } catch (_: Throwable) { }
    }

    private fun push() {
        val mgr = gm ?: return
        if (!ready) return

        // На паузе Spectrum продолжает отдавать последний кадр, поэтому смотрим на сам плеер
        val playing = PlaybackService.instance?.exo?.isPlaying == true
        val s = if (playing) Spectrum.levels() else ""
        if (s.isNotEmpty()) {
            val parts = s.split(',')
            for (i in lv.indices) lv[i] = parts.getOrNull(i)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        } else {
            lv.fill(0f)
        }

        val bass = (lv[0] + lv[1] + lv[2]) / 3f
        val loud = lv.average().toFloat()
        val target = FloatArray(CHANNELS)

        // C1: длинная дуга — общий уровень громкости, как стрелочный индикатор (снизу справа вверх)
        val c1 = (loud * 1.6f * 16f).coerceIn(0f, 16f)
        for (k in 0 until 16) target[3 + k] = (c1 - k).coerceIn(0f, 1f)

        // D1: нижняя полоска — басы (снизу вверх)
        val d1 = (bass * 1.3f * 8f).coerceIn(0f, 8f)
        for (k in 0 until 8) target[25 + k] = (d1 - k).coerceIn(0f, 1f)

        // Остальные зоны — отдельные полосы спектра, от низких к высоким
        target[24] = ((bass - 0.45f) * 2.5f).coerceIn(0f, 1f)          // E1 вспыхивает на сильном басе
        val zones = intArrayOf(0, 1, 2, 19, 20, 21, 22, 23)              // A1 A2 B1 C2..C6
        for ((j, ch) in zones.withIndex()) {
            val band = 3 + j * (Spectrum.BANDS - 4) / zones.size
            target[ch] = (lv[band] * 1.4f).coerceIn(0f, 1f)
        }

        // Мгновенно вверх, плавно вниз — так подсветка не дёргается
        var any = false
        for (i in 0 until CHANNELS) {
            cur[i] = if (target[i] > cur[i]) target[i] else cur[i] * 0.72f
            if (cur[i] < 0.03f) cur[i] = 0f
            if (cur[i] > 0f) any = true
        }

        if (!any) { off(); return }

        // Новый билдер на каждый кадр: общий из getGlyphFrameBuilder() копит каналы между вызовами
        val b = GlyphFrame.Builder(Glyph.DEVICE_22111)
        for (i in 0 until CHANNELS) {
            if (cur[i] > 0f) b.buildChannel(i, (cur[i] * MAX).toInt().coerceIn(1, MAX))
        }
        mgr.toggle(b.build())
        lit = true
    }
}
