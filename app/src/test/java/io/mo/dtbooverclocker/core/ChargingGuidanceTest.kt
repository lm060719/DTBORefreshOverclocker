package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.ChargingField
import io.mo.dtbooverclocker.model.ChargingParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingGuidanceTest
{
    private fun field(name: String, unit: String = "mA") =
        ChargingField(
            parameter = ChargingParameter(
                name = name,
                label = name,
                unit = unit,
                minimum = 0
            ),
            exists = true,
            rawValue = "<1000>",
            value = 1000
        )

    @Test
    fun currentLimitsRecommendConservativeDirection()
    {
        val guide = ChargingGuidanceResolver.resolve(field("qc3_ibat_max_limit"))

        assertEquals(ChargingGuideRisk.MEDIUM, guide.risk)
        assertTrue(guide.lowerEffect.contains("发热"))
        assertTrue(guide.higherEffect.contains("风险"))
        assertTrue(guide.beginnerAdvice.contains("小调") || guide.beginnerAdvice.contains("原厂"))
    }

    @Test
    fun batteryVoltageBoundariesAreHighRisk()
    {
        val guide = ChargingGuidanceResolver.resolve(field("max_vbat", "mV"))

        assertEquals(ChargingGuideRisk.HIGH, guide.risk)
        assertTrue(guide.higherEffect.contains("风险"))
        assertTrue(guide.beginnerAdvice.contains("不要高于原厂"))
    }

    @Test
    fun taperThresholdExplainsOppositeDirection()
    {
        val guide = ChargingGuidanceResolver.resolve(field("qc_taper_fcc_thr"))

        assertTrue(guide.lowerEffect.contains("高电流阶段"))
        assertTrue(guide.higherEffect.contains("提前"))
    }

    @Test
    fun nodeAdviceHighlightsMcaQuickChargePriorities()
    {
        val advice = ChargingGuidanceResolver.nodeAdvice(""mca,quick_charger"")

        requireNotNull(advice)
        assertTrue(advice.contains("div_single_curr"))
        assertTrue(advice.contains("max_vbat"))
    }
}
