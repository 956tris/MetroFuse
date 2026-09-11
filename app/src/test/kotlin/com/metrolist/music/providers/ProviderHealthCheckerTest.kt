package com.metrolist.music.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderHealthCheckerTest {
    @Test
    fun `soundcloud health targets always have unique ids`() {
        val targets = ProviderHealthChecker.targets("https://resolver.example")
            .filter { it.group == "SoundCloud" }

        assertEquals(targets.size, targets.map { it.id }.distinct().size)
    }
}
