package ru.avrora.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Эквалайзер для неба. Получает копию звука, который идёт в динамик,
 * раскладывает его на частоты (FFT) и отдаёт экрану 22 полосы от 0 до 1.
 * Разрешение на микрофон не нужно: слушаем только свой же плеер.
 */
@OptIn(UnstableApi::class)
object Spectrum : TeeAudioProcessor.AudioBufferSink {

    const val BANDS = 22

    /** Звук уходит в динамик чуть позже, чем попадает сюда.
     *  Если лучи опережают музыку, увеличь; если отстают, уменьши. */
    private const val LATENCY_MS = 200L

    private const val N = 1024
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val edges = IntArray(BANDS + 1) { (2 * 200.0.pow(it.toDouble() / BANDS)).toInt() }

    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2
    private val buf = DoubleArray(N)
    private var fill = 0
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val smooth = FloatArray(BANDS)

    private val queue = ArrayDeque<Pair<Long, FloatArray>>()
    private val lock = Any()

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        channels = max(1, channelCount)
        fill = 0
        synchronized(lock) { queue.clear() }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frame = bytes * channels
        while (b.remaining() >= frame) {
            var s = 0.0
            repeat(channels) { s += if (bytes == 2) b.short / 32768.0 else b.float.toDouble() }
            buf[fill++] = s / channels
            if (fill == N) {
                analyze()
                System.arraycopy(buf, N / 2, buf, 0, N / 2) // перекрытие 50%
                fill = N / 2
            }
        }
    }

    private fun analyze() {
        for (i in 0 until N) { re[i] = buf[i] * window[i]; im[i] = 0.0 }
        fft(re, im)
        val out = FloatArray(BANDS)
        for (k in 0 until BANDS) {
            val lo = edges[k]
            val hi = max(lo + 1, edges[k + 1])
            var m = 0.0
            for (i in lo until hi) m = max(m, hypot(re[i], im[i]))
            val db = 20 * log10(m / (N / 4) + 1e-9)
            val v = ((db + 60) / 55).coerceIn(0.0, 1.0).toFloat()
            smooth[k] = max(v, smooth[k] * 0.82f) // быстро вверх, плавно вниз
            out[k] = smooth[k]
        }
        synchronized(lock) {
            queue.addLast(System.nanoTime() to out)
            while (queue.size > 64) queue.removeFirst()
        }
    }

    /** Полосы через запятую для экрана, например "0.512,0.300,...". */
    fun levels(): String {
        val cutoff = System.nanoTime() - LATENCY_MS * 1_000_000
        val pick: FloatArray?
        synchronized(lock) {
            while (queue.size > 1 && queue[1].first <= cutoff) queue.removeFirst()
            pick = queue.firstOrNull()?.takeIf { it.first <= cutoff }?.second
        }
        return pick?.joinToString(",") { String.format(Locale.US, "%.3f", it) } ?: ""
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
