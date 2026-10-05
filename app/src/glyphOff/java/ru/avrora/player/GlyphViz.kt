package ru.avrora.player

import android.content.Context

/**
 * Заглушка на случай, если app/libs/glyph-matrix-sdk-2.0.aar не положен в проект.
 * Тот же набор методов, что у настоящего GlyphViz (src/glyphOn/...), но без единого
 * импорта из com.nothing.ketchum — поэтому компилируется всегда, без скачанного SDK.
 * Какой из двух файлов реально попадёт в сборку, решает build.gradle.kts по тому,
 * есть ли AAR на диске.
 */
object GlyphViz {
    fun init(ctx: Context) {}
    fun resume() {}
    fun pause() {}
    fun destroy() {}
}
