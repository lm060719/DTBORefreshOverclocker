package io.mo.dtbooverclocker.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure helpers for level × channel thermal tables; rows are levels, columns are channels, values are raw units. */
object ThermalTableAdjuster {
    data class Result(val levels: List<List<Long>>, val clamped: Int)

    /** Columns with identical values at every level, in first-seen order. Vendors often repeat one limit per charge-pump mode. */
    fun identicalColumns(levels: List<List<Long>>): List<List<Int>> {
        val columnCount = levels.firstOrNull()?.size ?: return emptyList()
        val groups = linkedMapOf<List<Long>, MutableList<Int>>()
        for (column in 0 until columnCount) groups.getOrPut(levels.map { it[column] }) { mutableListOf() }.add(column)
        return groups.values.toList()
    }

    /** Scales levels [fromLevel]..[toLevel] of [columns] by [percent] and rounds to [step].
     * Scaling keeps order inside the range, so only its edges are clamped against untouched neighbours.
     */
    fun scale(levels: List<List<Long>>, columns: Set<Int>, fromLevel: Int, toLevel: Int, percent: BigDecimal, step: Long): Result {
        require(fromLevel in levels.indices && toLevel in fromLevel..levels.lastIndex) { "档位范围无效" }
        require(step > 0) { "取整步进必须大于 0" }
        val factor = BigDecimal.ONE + percent.movePointLeft(2)
        require(factor.signum() >= 0) { "调整比例不能低于 -100%" }
        val stepValue = BigDecimal.valueOf(step)
        val table = levels.map { it.toMutableList() }
        var clamped = 0
        for (column in columns) {
            for (level in fromLevel..toLevel) {
                table[level][column] = BigDecimal.valueOf(levels[level][column]).multiply(factor)
                    .divide(stepValue, 0, RoundingMode.HALF_UP).multiply(stepValue).longValueExact()
            }
            if (fromLevel > 0) for (level in fromLevel..toLevel) {
                if (table[level][column] > table[level - 1][column]) { table[level][column] = table[level - 1][column]; clamped++ }
            }
            if (toLevel < table.lastIndex) for (level in toLevel downTo fromLevel) {
                if (table[level][column] < table[level + 1][column]) { table[level][column] = table[level + 1][column]; clamped++ }
            }
        }
        return Result(table, clamped)
    }
}
