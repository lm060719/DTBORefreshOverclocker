package io.mo.dtbooverclocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LiveDeviceTreeMatcherTest {
    private fun u32(vararg values: Int): ByteArray =
        values.flatMap { v -> listOf(v ushr 24, v ushr 16, v ushr 8, v).map(Int::toByte) }.toByteArray()

    private fun str(vararg values: String): ByteArray = values.joinToString("") { "$it\u0000" }.toByteArray()

    /** One overlay targeting `&battery_charger`, plus a phandle-valued property relocated per entry. */
    private fun entry(iterm: Int, phandle: Int, soc: Int = 519) = mapOf(
        "/qcom,msm-id" to u32(soc, 0x20000),
        "/fragment@0/target" to u32(-1),
        "/fragment@0/__overlay__/qcom,iterm_ma" to u32(iterm),
        "/fragment@0/__overlay__/qcom,vooc_project" to u32(16),
        "/fragment@0/__overlay__/pinctrl-0" to u32(phandle),
        "/fragment@0/__overlay__/child/qcom,vfloat_mv" to u32(4400 + iterm),
        "/__fixups__/battery_charger" to str("/fragment@0:target:0"),
        "/__local_fixups__/fragment@0/__overlay__/pinctrl-0" to u32(0)
    )

    private fun live(iterm: Int, soc: Int = 519) = mapOf(
        "/qcom,msm-id" to u32(soc, 0x20000),
        "/__symbols__/battery_charger" to str("/soc/qcom,pmic_glink/battery_charger"),
        "/soc/qcom,pmic_glink/battery_charger/qcom,iterm_ma" to u32(iterm),
        "/soc/qcom,pmic_glink/battery_charger/qcom,vooc_project" to u32(16),
        "/soc/qcom,pmic_glink/battery_charger/pinctrl-0" to u32(999),
        "/soc/qcom,pmic_glink/battery_charger/child/qcom,vfloat_mv" to u32(4400 + iterm)
    )

    @Test fun picksTheEntryWhoseOverlayMatchesTheRunningTree() {
        val result = LiveDeviceTreeMatcher.match(listOf(entry(122, 71), entry(135, 80)), live(135))!!
        assertEquals(setOf(1), result.indices)
        // Only iterm and the child vfloat differ; the relocated phandle is not compared.
        assertEquals(2, result.discriminating)
    }

    @Test fun identicalOverlaysTieAndSocIdNarrowsThem() {
        val entries = listOf(entry(122, 1, soc = 600), entry(135, 2), entry(135, 3, soc = 604))
        assertEquals(setOf(1), LiveDeviceTreeMatcher.match(entries, live(135))!!.indices)
        assertEquals(setOf(2), LiveDeviceTreeMatcher.match(entries, live(135, soc = 604))!!.indices)
        // An unknown SoC id never empties the result.
        assertEquals(setOf(1, 2), LiveDeviceTreeMatcher.match(entries, live(135, soc = 700))!!.indices)
    }

    @Test fun noDifferencesOrNoMatchAreNotGuessed() {
        assertNull(LiveDeviceTreeMatcher.match(listOf(entry(122, 1), entry(122, 2)), live(122)))
        assertTrue(LiveDeviceTreeMatcher.match(listOf(entry(122, 1), entry(135, 2)), live(150))!!.indices.isEmpty())
    }

    @Test fun realmeImageSeparatesItsTwoChargingVariants() {
        val path = System.getProperty("dtbo.oplusLegacySampleImage", "").orEmpty()
        assumeTrue("Run with -PoplusLegacySampleImage=<dtbo.img>", path.isNotBlank())
        val entries = DtboImageCodec.parse(File(path)).entries.map { FdtReader.readAllProperties(it.decodedBytes) }
        // Labels resolve to stand-in paths; each simulated boot applies exactly one entry.
        val labels = entries.flatMap { e -> e.keys.filter { it.startsWith("/__fixups__/") }.map { it.substringAfterLast('/') } }.toSet()
        val symbols = labels.associate { "/__symbols__/$it" to str("/base/$it") }
        entries.indices.forEach { applied ->
            val live = symbols + LiveDeviceTreeMatcher.assignments(entries[applied], symbols) + mapOf("/qcom,msm-id" to entries[applied].getValue("/qcom,msm-id"))
            val result = LiveDeviceTreeMatcher.match(entries, live)!!
            assertTrue("DTB[$applied] -> ${result.indices} ${result.scores}", applied in result.indices)
            val applyEntry = entries[applied]
            result.indices.forEach { other ->
                assertEquals(LiveDeviceTreeMatcher.assignments(applyEntry, symbols).mapValues { it.value.toList() },
                    LiveDeviceTreeMatcher.assignments(entries[other], symbols).mapValues { it.value.toList() })
            }
            println("DTB[$applied] -> ${result.indices} (${result.discriminating} discriminating)")
        }
    }
}
