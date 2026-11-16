package com.metrolist.desktop.ui.components

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class AudioQualityFormattingTest {
    @Test
    fun retainsEveryBitAndSampleWhenConvertingToKiloUnits() {
        assertEquals("44.056", exactAudioKiloValue(44_056, Locale.UK))
        assertEquals("44.1", exactAudioKiloValue(44_100, Locale.UK))
        assertEquals("320.003", exactAudioKiloValue(320_003, Locale.UK))
        assertEquals("1,500,000.003", exactAudioKiloValue(1_500_000_003, Locale.UK))
    }

    @Test
    fun usesTheChosenLocaleWithoutChangingTheValue() {
        assertEquals("44,056", exactAudioKiloValue(44_056, Locale.GERMANY))
        assertEquals("1.006,273", exactAudioKiloValue(1_006_273, Locale.GERMANY))
    }
}
