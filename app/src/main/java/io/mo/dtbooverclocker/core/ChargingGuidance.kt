package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.ChargingField

enum class ChargingGuideRisk
{
    LOW,
    MEDIUM,
    HIGH
}

data class ChargingGuidance(
    val plainMeaning: String,
    val lowerEffect: String,
    val higherEffect: String,
    val beginnerAdvice: String,
    val risk: ChargingGuideRisk
)

/**
 * 面向 UI 的充电参数解释。
 *
 * 只对已经被 ChargingAnalyzer 判定为可编辑的字段提供说明；复杂策略表仍不会因此开放编辑。
 * 优先使用已知字段的精确语义，未命中时再按“电流上限 / 电压上限 / 温控限流”等安全类别提供保守说明。
 */
object ChargingGuidanceResolver
{
    fun resolve(field: ChargingField): ChargingGuidance
    {
        val name = field.parameter.name.lowercase()

        exact[name]?.let { return it }

        return when
        {
            field.parameter.descendingStride > 0 || "thermal" in name ->
                currentLimit(
                    meaning = "温控触发后的充电限流值。数值越低，温度升高后允许的充电电流越小。",
                    advice = "如果目标只是降低充电温度，优先从这里小幅下调；不要反向抬高原厂温控档位。"
                )

            name == "min_vbat" ->
                ChargingGuidance(
                    plainMeaning = "进入快充/充电泵策略允许的最低电池电压门槛。",
                    lowerEffect = "降低后，电池电压较低时也更容易进入快充，策略更激进。",
                    higherEffect = "提高后，要等电池电压更高才进入快充，通常更保守。",
                    beginnerAdvice = "不追求修改快充进入区间时保持原厂值；若只是想降温，不建议动这个参数。",
                    risk = ChargingGuideRisk.HIGH
                )

            name == "max_vbat" || name.contains("max-vbat") ->
                ChargingGuidance(
                    plainMeaning = "快充策略允许的电池电压上边界/退出门槛。",
                    lowerEffect = "降低后会更早退出高功率阶段，通常更保守，但可能更早降速。",
                    higherEffect = "提高后会把高功率阶段延伸到更高电池电压，增加电池和过压风险。",
                    beginnerAdvice = "不要高于原厂值。若没有明确驱动依据，保持原厂最稳妥。",
                    risk = ChargingGuideRisk.HIGH
                )

            name == "recharge_vbat" ->
                ChargingGuidance(
                    plainMeaning = "充满后电压回落到一定程度时重新补充充电的门槛。",
                    lowerEffect = "降低后通常需要电压掉得更多才重新补充，减少频繁补充。",
                    higherEffect = "提高后更容易较早重新补充，满电附近补充可能更频繁。",
                    beginnerAdvice = "这个参数主要影响满电后的补充策略，不是提升快充速度的参数；一般保持原厂。",
                    risk = ChargingGuideRisk.MEDIUM
                )

            name.contains("taper_fcc") ->
                ChargingGuidance(
                    plainMeaning = "判断是否进入收尾/降流阶段的电流阈值。",
                    lowerEffect = "降低后，通常会把高电流阶段维持得更久，策略更激进。",
                    higherEffect = "提高后，更容易提前满足收尾条件并开始降流，通常更保守。",
                    beginnerAdvice = "想降低末段发热可以适度提高，但会更早降速；不要为了速度盲目降低。",
                    risk = ChargingGuideRisk.MEDIUM
                )

            name.contains("taper_vol_hys") || name.contains("hys") ->
                ChargingGuidance(
                    plainMeaning = "模式切换/收尾判断的回差值，用来避免在阈值附近频繁来回切换。",
                    lowerEffect = "降低后响应更敏感，但更容易在临界点反复切换。",
                    higherEffect = "提高后切换更稳定，但触发恢复会更迟钝。",
                    beginnerAdvice = "这类参数不是功率上限。没有明确问题时不要改。",
                    risk = ChargingGuideRisk.MEDIUM
                )

            name == "cp_switch_pmic_th" ->
                ChargingGuidance(
                    plainMeaning = "热控电流降到该门槛附近时，跳过/退出充电泵并回退 PMIC 路径的阈值。",
                    lowerEffect = "降低后会让充电泵在更低热控电流下继续工作更久。",
                    higherEffect = "提高后会更早回退 PMIC，通常更保守，但可能更早降速。",
                    beginnerAdvice = "如果只是想降温，可考虑提高而不是降低；首次修改建议只做很小幅度并观察行为。",
                    risk = ChargingGuideRisk.MEDIUM
                )

            isInputCurrent(name) ->
                currentLimit(
                    meaning = "充电器输入侧允许的最大电流，主要限制从适配器/USB 侧取多少电流。",
                    advice = "想降低发热或功耗时可以往小调；不要高于原厂值来追求速度。"
                )

            isBatteryCurrent(name) ->
                currentLimit(
                    meaning = "电池侧允许的充电电流上限，直接影响充电速度和电池/充电泵发热。",
                    advice = "新手只建议往小调做降温，不建议超过原厂值。"
                )

            isGenericCurrent(name) ->
                currentLimit(
                    meaning = "该充电路径或协议的电流上限/目标值。",
                    advice = "如果目的是降温，往小调；如果目的是提速，不建议高于原厂配置。"
                )

            isVoltageLimit(name) ->
                voltageLimit(name)

            else ->
                ChargingGuidance(
                    plainMeaning = "这是已确认单位和数据格式的充电参数，但当前没有足够证据把它归入更具体的调节类别。",
                    lowerEffect = "降低后的具体效果取决于对应驱动逻辑。",
                    higherEffect = "提高后的具体效果取决于对应驱动逻辑。",
                    beginnerAdvice = "看不懂时保持原厂值；不要仅凭参数名尝试提高。",
                    risk = ChargingGuideRisk.HIGH
                )
        }
    }

    private fun currentLimit(meaning: String, advice: String): ChargingGuidance =
        ChargingGuidance(
            plainMeaning = meaning,
            lowerEffect = "降低：通常充电更慢，但发热和供电压力更低。",
            higherEffect = "提高：可能允许更高功率，但发热、电池压力和硬件风险都会上升。",
            beginnerAdvice = advice,
            risk = ChargingGuideRisk.MEDIUM
        )

    private fun voltageLimit(name: String): ChargingGuidance
    {
        val busSide = name.contains("vbus") || name.contains("vadp") ||
            name.contains("phone_vol") || name.contains("voltage-mv")

        return if (busSide)
        {
            ChargingGuidance(
                plainMeaning = "适配器/充电路径的电压上限或目标值，不等同于电池充满电压。",
                lowerEffect = "降低后可能限制协议可使用的高压档位，功率可能下降。",
                higherEffect = "提高后可能请求更高路径电压；硬件和协议不支持时可能失败或增加风险。",
                beginnerAdvice = "不要高于原厂值。想降温优先调电流上限，而不是先改总线电压。",
                risk = ChargingGuideRisk.HIGH
            )
        }
        else
        {
            ChargingGuidance(
                plainMeaning = "电池侧浮充/电压限制参数，直接关系到充电末段和电池电压。",
                lowerEffect = "降低后通常会更早限压/结束高功率阶段，可能减少满电容量或更早降速。",
                higherEffect = "提高后会把电池推向更高电压，增加寿命和过压风险。",
                beginnerAdvice = "不要高于原厂值；如果没有电芯规格和驱动依据，保持原厂。",
                risk = ChargingGuideRisk.HIGH
            )
        }
    }

    private fun isInputCurrent(name: String): Boolean =
        name.contains("icl") ||
            name.contains("ibus") ||
            name.contains("input_curr") ||
            name.contains("phone_icl")

    private fun isBatteryCurrent(name: String): Boolean =
        name.contains("ibat") ||
            name.contains("fcc") ||
            name.startsWith("chg_") ||
            name.contains("charge_curr")

    private fun isGenericCurrent(name: String): Boolean =
        name.contains("curr") || name.endsWith("_ma") || name.endsWith("-ma")

    private fun isVoltageLimit(name: String): Boolean =
        name.contains("volt") ||
            name.contains("vbus") ||
            name.contains("vbat") ||
            name.contains("_fv") ||
            name.contains("-fv") ||
            name.endsWith("_mv") ||
            name.endsWith("-mv")

    private val exact = mapOf(
        "qc_normal_charge_fv" to ChargingGuidance(
            plainMeaning = "QC 普通充电阶段使用的浮充电压上限。",
            lowerEffect = "降低后会更早限压/收尾，可能稍早降速或少充一点。",
            higherEffect = "提高会增加电池末段电压压力和过压风险。",
            beginnerAdvice = "不要高于原厂值。想降温应优先降低电流而不是提高/修改浮充电压。",
            risk = ChargingGuideRisk.HIGH
        ),
        "pmic_fv_compensation" to ChargingGuidance(
            plainMeaning = "用于补偿 PMIC 路径电压误差的偏移量，不是单纯的“充电电压”。",
            lowerEffect = "降低可能让补偿不足，影响末段电压控制。",
            higherEffect = "提高可能让补偿过量，改变实际浮充控制。",
            beginnerAdvice = "除非已知硬件压降和驱动算法，否则保持原厂。",
            risk = ChargingGuideRisk.HIGH
        ),
        "div_single_curr" to currentLimit(
            "不同充电泵倍率下，单路充电泵允许的电池侧电流。",
            "这是快充核心电流限制。想降温可往小调；不要为了提速高于原厂。"
        ),
        "div_max_curr" to currentLimit(
            "不同充电泵倍率下，多路/整体允许的最大电池侧电流。",
            "这是快充总电流上限之一。新手只建议下调，不建议提高。"
        ),
        "buck_icl_fcc_curr" to currentLimit(
            "并行 Buck 充电时的输入电流/电池充电电流组合。",
            "这两个值共同限制 Buck 路径功率；如果降温，两项都应保守下调，避免只抬其中一项。"
        ),
        "rev_boost_voltage" to ChargingGuidance(
            plainMeaning = "无线反向充电时的升压目标电压。",
            lowerEffect = "降低可能让反向供电能力下降或兼容性变差。",
            higherEffect = "提高会增加升压和无线反充硬件压力。",
            beginnerAdvice = "它不影响正常有线快充；没有反充问题时不要改。",
            risk = ChargingGuideRisk.HIGH
        )
    )
}
