package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.routes.canSleep
import com.mairwunnx.worldapi.routes.ticksUntilNight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldTest {
    @Test
    fun `night is counted down from any time of day`() {
        assertEquals(13_000, ticksUntilNight(0))
        assertEquals(7_000, ticksUntilNight(6_000))
        assertEquals(0, ticksUntilNight(13_000))
        assertEquals(0, ticksUntilNight(22_999))
        assertEquals(14_000, ticksUntilNight(23_000))
    }

    @Test
    fun `beds work at night, earlier in rain, and always in a thunderstorm`() {
        assertFalse(canSleep(12_000, raining = false, thundering = false))
        assertTrue(canSleep(12_542, raining = false, thundering = false))
        assertFalse(canSleep(23_460, raining = false, thundering = false))
        assertTrue(canSleep(12_010, raining = true, thundering = false))
        assertTrue(canSleep(6_000, raining = true, thundering = true))
    }
}
