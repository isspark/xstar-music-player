package life.xstar.music.player.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {
    private fun tone(frequency: Double, amplitude: Float) = FloatArray(SpectrumAnalyzer.WINDOW_SIZE) {
        (amplitude * sin(2 * PI * frequency * it / 48_000)).toFloat()
    }

    @Test
    fun silenceProducesZeroLevels() {
        val levels = SpectrumAnalyzer().analyze(FloatArray(SpectrumAnalyzer.WINDOW_SIZE), 48_000)
        assertTrue(levels.all { it == 0f })
    }

    @Test
    fun tonesAreMappedToTheirFrequencyBands() {
        val analyzer = SpectrumAnalyzer()
        for (frequency in listOf(120.0, 1000.0, 8000.0)) {
            val levels = analyzer.analyze(tone(frequency, 0.5f), 48_000)
            val peak = levels.indices.maxBy { levels[it] }
            val expected = (kotlin.math.ln(frequency / 40) / kotlin.math.ln(400.0) * 48).toInt()
            assertTrue("frequency=$frequency peak=$peak expected=$expected", kotlin.math.abs(peak - expected) <= 2)
            assertTrue(levels[peak] > 0.7f)
        }
    }

    @Test
    fun quieterAudioProducesLowerLevelsAndSilenceClearsPreviousFrame() {
        val analyzer = SpectrumAnalyzer()
        val loud = analyzer.analyze(tone(1000.0, 0.8f), 48_000).max()
        val quiet = analyzer.analyze(tone(1000.0, 0.01f), 48_000).max()
        assertTrue(loud - quiet > 0.4f)
        assertTrue(analyzer.analyze(FloatArray(SpectrumAnalyzer.WINDOW_SIZE), 48_000).all { it == 0f })
    }
}
