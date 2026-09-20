package io.mo.dtbooverclocker.update

import java.math.BigInteger

/** Numeric version ordering, accepting GitHub's optional v prefix and build metadata. */
internal data class ReleaseVersion(
    private val numbers: List<BigInteger>,
    private val prerelease: List<String>
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val comparison = (numbers.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.toBigIntegerOrNull()
            val rightNumber = right.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            "^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return ReleaseVersion(
                numbers = match.groupValues[1].split('.').map { it.toBigInteger() },
                prerelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            )
        }
    }
}
