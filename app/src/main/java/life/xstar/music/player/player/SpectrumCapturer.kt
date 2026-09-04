package life.xstar.music.player.player

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class SpectrumCapturer(private val onBandsChanged: (FloatArray) -> Unit) {
    private var visualizer: Visualizer? = null
    private var captureSize = 1024
    private var sampleRate = 44_100
    private var active = false
    private var lastEmitAt = 0L
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private val main = Handler(Looper.getMainLooper())
    private val smooth = FloatArray(SPECTRUM_BAND_COUNT)
    private var peakEnv = 0f
    private var lastFrameAt = 0L
    private var lastRebuildAt = 0L

    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId) return
        sessionId = audioSessionId
        rebuildVisualizer()
    }

    fun ensureSession(audioSessionId: Int) {
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != sessionId) {
            sessionId = audioSessionId
            rebuildVisualizer()
        }
    }

    fun setEnabled(value: Boolean) {
        if (value == active) return
        active = value
        if (value) {
            if (visualizer == null) rebuildVisualizer()
            runCatching { visualizer?.enabled = true }
            scheduleWatchdog()
        } else {
            main.removeCallbacks(watchdog)
            peakEnv = 0f
            smooth.fill(0f)
            lastEmitAt = 0L
            main.post { onBandsChanged(FloatArray(SPECTRUM_BAND_COUNT)) }
        }
    }

    fun release() {
        main.removeCallbacks(watchdog)
        releaseVisualizer()
    }

    private fun rebuildVisualizer() {
        releaseVisualizer()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        val created = runCatching { Visualizer(sessionId) }.getOrElse {
            Log.w(TAG, "Visualizer 不可用：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        val range = Visualizer.getCaptureSizeRange()
        var size = 0
        runCatching {
            created.setCaptureSize(CAPTURE_SIZE.coerceIn(range[0], range[1]))
            size = created.getCaptureSize()
        }.onFailure {
            Log.w(TAG, "setCaptureSize($CAPTURE_SIZE) 失败 range=[${range[0]},${range[1]}] err=${it.message}")
            size = runCatching { created.getCaptureSize() }.getOrDefault(0)
        }
        if (size <= 0) {
            Log.w(TAG, "无可用频谱抓取尺寸")
            created.release()
            return
        }
        visualizer = created
        captureSize = size
        sampleRate = normalizedSampleRate(runCatching { created.getSamplingRate() }.getOrDefault(0))
        registerListener(created)
        runCatching { created.enabled = true }
        lastFrameAt = 0L
    }

    private fun registerListener(created: Visualizer) {
        runCatching { created.setDataCaptureListener(listener, CAPTURE_INTERVAL_MS, false, true) }
            .onFailure { Log.w(TAG, "setDataCaptureListener 失败：${it.message}") }
    }

    private fun releaseVisualizer() {
        val created = visualizer
        visualizer = null
        runCatching { created?.enabled = false }
        created?.release()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt > STALL_REBUILD_MS && now - lastRebuildAt > REBUILD_COOLDOWN_MS) {
                lastRebuildAt = now
                rebuildVisualizer()
            }
            scheduleWatchdog()
        }
    }

    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit

        override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
            val now = SystemClock.uptimeMillis()
            if (now - lastEmitAt < CAPTURE_INTERVAL_MS.toLong()) return
            lastEmitAt = now
            lastFrameAt = now
            val bands = computeBands(fft)
            main.post { if (active) onBandsChanged(bands) }
        }
    }

    private fun computeBands(fft: ByteArray): FloatArray {
        val out = FloatArray(SPECTRUM_BAND_COUNT)
        val half = fft.size / 2
        if (half < 2) return out
        val binHz = sampleRate.toFloat() / captureSize
        val fMin = MIN_FREQ
        val fMax = min(sampleRate / 2f, MAX_FREQ)
        if (fMax <= fMin) return out
        val logSpan = ln(fMax / fMin)

        val binMag = FloatArray(half)
        var rmsAcc = 0f
        for (i in 0 until half) {
            val re = fft[i * 2].toInt().toFloat()
            val im = fft[i * 2 + 1].toInt().toFloat()
            val magnitude = sqrtF(re * re + im * im)
            binMag[i] = magnitude
            if (i > 0) rmsAcc += magnitude * magnitude
        }
        val frameRms = sqrtF(rmsAcc / (half - 1))
        peakEnv = max(frameRms, peakEnv * PEAK_RELEASE)
        if (peakEnv <= 0f) return out
        val env = (frameRms / peakEnv).coerceIn(0f, 1f)

        val sums = FloatArray(SPECTRUM_BAND_COUNT)
        val counts = IntArray(SPECTRUM_BAND_COUNT)
        for (i in 1 until half) {
            val freq = i * binHz
            if (freq < fMin || freq > fMax) continue
            val band = (ln(freq / fMin) / logSpan * SPECTRUM_BAND_COUNT).toInt()
                .coerceIn(0, SPECTRUM_BAND_COUNT - 1)
            sums[band] += binMag[i] * binMag[i]
            counts[band]++
        }
        val t = FloatArray(SPECTRUM_BAND_COUNT)
        for (b in 0 until SPECTRUM_BAND_COUNT) {
            if (counts[b] == 0) continue
            val bandRms = sqrtF(sums[b] / counts[b])
            val center = exp(ln(fMin) + logSpan * ((b + 0.5f) / SPECTRUM_BAND_COUNT))
            val weight = MID_WEIGHT + (1f - MID_WEIGHT) * bell(center)
            t[b] = ((bandRms / peakEnv) * weight * env).coerceIn(0f, 1f)
        }
        for (b in 0 until SPECTRUM_BAND_COUNT) {
            smooth[b] = if (t[b] > smooth[b]) smooth[b] + (t[b] - smooth[b]) * ATTACK else smooth[b] * RELEASE
            out[b] = max(smooth[b], IDLE_FLOOR)
        }
        return out
    }

    private fun bell(freq: Float): Float {
        val x = ln(freq / 1000f)
        return exp(-x * x / 0.9f)
    }

    private fun normalizedSampleRate(raw: Int): Int {
        if (raw in 8_000..192_000) return raw
        if (raw in 8_000_000..192_000_000) return raw / 1000
        return 44_100
    }

    private fun sqrtF(value: Float) = sqrt(value.toDouble()).toFloat()

    private companion object {
        const val TAG = "SpectrumCapturer"
        const val CAPTURE_SIZE = 1024
        const val CAPTURE_INTERVAL_MS = 60
        const val MIN_FREQ = 60f
        const val MAX_FREQ = 18_000f
        const val PEAK_RELEASE = 0.90f
        const val MID_WEIGHT = 0.45f
        const val ATTACK = 0.55f
        const val RELEASE = 0.90f
        const val IDLE_FLOOR = 0.02f
        const val WATCHDOG_INTERVAL_MS = 4_000L
        const val STALL_REBUILD_MS = 30_000L
        const val REBUILD_COOLDOWN_MS = 60_000L
    }
}
