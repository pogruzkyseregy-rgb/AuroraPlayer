package ru.avrora.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager

/**
 * Подсветка Glyph на задней панели Nothing Phone (2) в такт музыке.
 * Личная фишка — только для тебя, не для всех, кто ставит плеер.
 * На любом другом телефоне просто ничего не делает: сервис Glyph там
 * не существует, onServiceConnected никогда не сработает, ни одной ошибки.
 *
 * Светит, только пока экран плеера открыт (так работает сама система
 * Nothing — Glyph обслуживает лишь то приложение, что сейчас на переднем
 * плане), поэтому запускаем в onResume и гасим в onPause.
 */
object GlyphViz {
    private var gm: GlyphManager? = null
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            push()
            handler.postDelayed(this, 110)
        }
    }

    fun init(ctx: Context) {
        if (!Common.is22111()) return   // не Phone (2) — дальше даже не пытаемся
        val mgr = GlyphManager.getInstance(ctx.applicationContext)
        gm = mgr
        mgr.init(object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                try {
                    mgr.register(Glyph.DEVICE_22111)
                    mgr.openSession()
                    ready = true
                } catch (_: GlyphException) { /* нет Glyph-сервиса — просто молчим */ }
            }
            override fun onServiceDisconnected(componentName: ComponentName) {
                ready = false
            }
        })
    }

    fun resume() {
        if (!ready || running) return
        running = true
        handler.post(tick)
    }

    fun pause() {
        running = false
        handler.removeCallbacks(tick)
        try { gm?.turnOff() } catch (_: Exception) { }
    }

    fun destroy() {
        pause()
        try { gm?.closeSession() } catch (_: Exception) { }
        gm?.unInit()
        gm = null; ready = false
    }

    /** Один тик: басы — прогрессом на большом сегменте D1, мини-спектр — по 16 зонам C1. */
    private fun push() {
        val mgr = gm ?: return
        val parts = Spectrum.levels().split(",")
        if (parts.size < Spectrum.BANDS) return
        val lv = FloatArray(Spectrum.BANDS) { parts[it].toFloatOrNull() ?: 0f }
        val bass = ((lv[0] + lv[1] + lv[2]) / 3f).coerceIn(0f, 1f)

        try {
            val d = mgr.getGlyphFrameBuilder().buildChannelD().build()
            mgr.displayProgress(d, (bass * 100).toInt())
        } catch (_: Exception) { }

        try {
            val b = mgr.getGlyphFrameBuilder()
            for (i in 0 until 16) {
                val band = lv[i * Spectrum.BANDS / 16]
                if (band > 0.22f) b.buildChannel(Glyph.C1_1 + i)
            }
            mgr.toggle(b.build())   // пустой набор каналов, если музыка тихая, — гасит C1 сам собой
        } catch (_: Exception) { }
    }
}
