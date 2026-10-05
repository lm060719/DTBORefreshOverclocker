package io.mo.dtbooverclocker.core

/**
 * Infers which DTBO entries the bootloader applied when `androidboot.dtbo_idx` is unavailable,
 * by comparing each entry's overlay assignments against the running device tree
 * (`/sys/firmware/fdt`, the blob the bootloader handed to the kernel after overlaying).
 *
 * Only properties on which the entries disagree are scored. Overlay root properties are never
 * merged, and phandle-bearing values are relocated during overlay, so both are excluded.
 * Inputs are [FdtReader.readAllProperties] maps (`/node/path/prop` -> bytes).
 */
object LiveDeviceTreeMatcher {
    data class EntryScore(val index: Int, val matches: Int, val mismatches: Int)

    data class Result(val indices: Set<Int>, val scores: List<EntryScore>, val discriminating: Int)

    private val ignoredNames = setOf("phandle", "linux,phandle", "name")

    fun match(entries: List<Map<String, ByteArray>>, live: Map<String, ByteArray>): Result? {
        val assignments = entries.map { assignments(it, live) }
        val keys = assignments.flatMap { it.keys }.toSet()
        val discriminating = keys.filter { key ->
            val values = assignments.map { it[key] }
            values.any { value -> !sameBytes(value, values.first()) }
        }
        if (discriminating.isEmpty()) return null

        val scores = assignments.mapIndexed { index, assigned ->
            var matches = 0
            var mismatches = 0
            discriminating.forEach { key ->
                val expected = assigned[key] ?: return@forEach
                if (sameBytes(expected, live[key])) matches++ else mismatches++
            }
            EntryScore(index, matches, mismatches)
        }
        // Tolerate a few runtime fixups by the bootloader, but never a partial match.
        val plausible = scores.filter { it.matches > 0 && it.mismatches * 20 <= it.matches + it.mismatches }
        if (plausible.isEmpty()) return Result(emptySet(), scores, discriminating.size)
        val fewest = plausible.minOf { it.mismatches }
        val best = plausible.filter { it.mismatches == fewest }.map { it.index }.toSet()
        // Entries with identical overlays tie; the SoC id narrows them when it can.
        return Result(narrowBySoc(best, entries, live), scores, discriminating.size)
    }

    /** Live property path -> value this entry writes, last fragment winning as in overlay order. */
    internal fun assignments(entry: Map<String, ByteArray>, live: Map<String, ByteArray>): Map<String, ByteArray> {
        val relocated = mutableSetOf<String>()
        val targetLabels = mutableMapOf<String, String>()
        entry.forEach { (key, value) ->
            when {
                key.startsWith("/__fixups__/") -> strings(value).forEach { descriptor ->
                    val parts = descriptor.split(':')
                    if (parts.size != 3) return@forEach
                    if (parts[1] == "target" && parts[2] == "0") targetLabels[parts[0]] = key.substringAfterLast('/')
                    else relocated += "${parts[0]}/${parts[1]}"
                }
                key.startsWith("/__local_fixups__/") -> relocated += key.removePrefix("/__local_fixups__")
            }
        }
        val result = linkedMapOf<String, ByteArray>()
        entry.forEach { (key, value) ->
            val marker = key.indexOf("/__overlay__")
            if (marker < 0 || key.startsWith("/__") || key in relocated) return@forEach
            val name = key.substringAfterLast('/')
            if (name in ignoredNames) return@forEach
            val fragment = key.substring(0, marker)
            val base = targetBase(fragment, entry, targetLabels, live) ?: return@forEach
            val rest = key.substring(marker + "/__overlay__".length)
            result[base.trimEnd('/') + rest] = value
        }
        return result
    }

    private fun targetBase(
        fragment: String,
        entry: Map<String, ByteArray>,
        targetLabels: Map<String, String>,
        live: Map<String, ByteArray>
    ): String? {
        val path = targetLabels[fragment]?.let { label -> live["/__symbols__/$label"]?.let(::strings)?.singleOrNull() }
            ?: entry["$fragment/target-path"]?.let(::strings)?.singleOrNull()
        return path?.takeIf { it.startsWith("/") }
    }

    private fun narrowBySoc(best: Set<Int>, entries: List<Map<String, ByteArray>>, live: Map<String, ByteArray>): Set<Int> {
        val liveSocs = socIds(live["/qcom,msm-id"])
        if (liveSocs.isEmpty() || best.size < 2) return best
        return best.filter { socIds(entries[it]["/qcom,msm-id"]).any(liveSocs::contains) }.toSet().ifEmpty { best }
    }

    /** `qcom,msm-id` is a list of <soc-id version> pairs; the low 16 bits carry the chip id. */
    private fun socIds(value: ByteArray?): Set<Int> {
        if (value == null || value.size % 8 != 0) return emptySet()
        return (value.indices step 8).map { offset ->
            ((value[offset + 2].toInt() and 0xff) shl 8) or (value[offset + 3].toInt() and 0xff)
        }.toSet()
    }

    private fun strings(value: ByteArray): List<String> =
        String(value, Charsets.US_ASCII).trimEnd('\u0000').split('\u0000')

    private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean =
        if (a == null || b == null) a == null && b == null else a.contentEquals(b)
}
