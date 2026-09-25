package io.mo.dtbooverclocker.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ThermalTableAdjusterTest {
    // Xiaomi 15 Ultra wired_thermal levels 1-4: 5V in, 9V in, then two identical DIV columns.
    private val table = listOf(
        listOf(1900L, 1500L, 13500L, 13500L),
        listOf(1900L, 1500L, 11000L, 11000L),
        listOf(1900L, 1500L, 9000L, 9000L),
        listOf(1900L, 1450L, 8000L, 8000L)
    )

    @Test fun groupsColumnsIdenticalAtEveryLevel() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), ThermalTableAdjuster.identicalColumns(table))
    }

    @Test fun scalesSelectedRangeAndRoundsToStep() {
        val result = ThermalTableAdjuster.scale(table, setOf(2, 3), 1, 3, BigDecimal("10"), 10)
        assertEquals(listOf(13500L, 12100L, 9900L, 8800L), result.levels.map { it[2] })
        assertEquals(result.levels.map { it[2] }, result.levels.map { it[3] })
        assertEquals(table.map { it[0] }, result.levels.map { it[0] })
        assertEquals(0, result.clamped)
    }

    @Test fun clampsIncreaseAtUpperEdge() {
        val result = ThermalTableAdjuster.scale(table, setOf(2), 1, 2, BigDecimal("30"), 10)
        assertEquals(listOf(13500L, 13500L, 11700L, 8000L), result.levels.map { it[2] })
        assertEquals(1, result.clamped)
    }

    @Test fun clampsDecreaseAtLowerEdge() {
        val result = ThermalTableAdjuster.scale(table, setOf(2), 0, 1, BigDecimal("-30"), 10)
        assertEquals(listOf(9450L, 9000L, 9000L, 8000L), result.levels.map { it[2] })
        assertEquals(1, result.clamped)
    }

    @Test fun rejectsInvalidRangeAndRatio() {
        assertThrows(IllegalArgumentException::class.java) { ThermalTableAdjuster.scale(table, setOf(2), 2, 1, BigDecimal.TEN, 10) }
        assertThrows(IllegalArgumentException::class.java) { ThermalTableAdjuster.scale(table, setOf(2), 0, 3, BigDecimal("-150"), 10) }
    }
}
