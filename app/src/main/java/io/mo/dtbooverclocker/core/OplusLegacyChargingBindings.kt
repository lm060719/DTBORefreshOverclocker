package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.model.ChargingField
import io.mo.dtbooverclocker.model.ChargingParameter

/**
 * OPlus 旧版充电框架（oplus_chg v1，`drivers/power/oplus/oplus_charger.c`）的保守绑定。
 *
 * 该框架把参数直接写在 `&battery_charger` 等节点上、通常没有自己的 compatible，
 * 因此按 `oplus_charger.c` 必读的属性签名识别。每个开放的属性名都在驱动的
 * `of_property_read_u32()` 中核对过，并确认是以 mA / mV 参与比较或下发；
 * 驱动未解析的同名变体（如 FFC warm 浮充电压）与电池容量、关机电压等不开放。
 * voocphy / PPS 策略表的单位不统一（部分为 100 mA），继续只读。
 */
internal object OplusLegacyChargingBindings
{
    private val signature = listOf("qcom,iterm_ma", "qcom,temp_normal_vfloat_mv", "qcom,normal_vfloat_sw_limit")

    private const val CURRENT = "OPlus 旧版 · 温区充电电流"
    private const val VOLTAGE = "OPlus 旧版 · 温区浮充电压"
    private const val FFC = "OPlus 旧版 · FFC 快充"
    private const val INPUT = "OPlus 旧版 · 输入电流"
    private const val PROTECTION = "OPlus 旧版 · 终止与保护"
    private const val DEFAULTS = "OPlus 旧版 · 默认值"

    private data class Binding(val parameter: ChargingParameter, val group: String)

    private fun ma(name: String, label: String, group: String) =
        Binding(ChargingParameter("qcom,$name", label, "mA", minimum = 0), group)

    private fun mv(name: String, label: String, group: String) =
        Binding(ChargingParameter("qcom,$name", label, "mV", minimum = 0), group)

    private val zones = listOf(
        "freeze" to "极寒区", "cold" to "寒冷区", "little_cold" to "低温区", "cool" to "偏冷区",
        "little_cool" to "微凉区", "normal" to "常温区", "warm" to "高温区"
    )

    /** Protocol prefix + suffix combinations the driver actually parses for each zone. */
    private val zoneCurrentVariants = mapOf(
        "freeze" to listOf("|", "|_high", "|_low", "pd_|_high", "pd_|_low", "qc_|_high", "qc_|_low"),
        "cold" to listOf("|", "|_high", "|_low", "pd_|_high", "pd_|_low", "qc_|_high", "qc_|_low"),
        "little_cold" to listOf("|", "|_high", "|_low", "pd_|_high", "pd_|_low", "qc_|_high", "qc_|_low"),
        "cool" to listOf("|_high", "|_low", "pd_|_high", "pd_|_low", "qc_|_high", "qc_|_low"),
        "little_cool" to listOf("|", "|_high", "|_low", "pd_|", "pd_|_high", "pd_|_low", "qc_|", "qc_|_high", "qc_|_low"),
        "normal" to listOf("|", "|_high", "|_low", "pd_|", "pd_|_high", "pd_|_low", "qc_|"),
        "warm" to listOf("|", "pd_|", "qc_|")
    )

    // Driver picks `_high` below 4180 mV battery voltage and `_low` above it.
    private val currentBindings = zones.flatMap { (zone, label) ->
        zoneCurrentVariants.getValue(zone).map { variant ->
            val (protocol, suffix) = variant.split('|')
            val protocolLabel = mapOf("pd_" to "PD ", "qc_" to "QC ")[protocol].orEmpty()
            val suffixLabel = mapOf("_high" to " · 4.18V 以下", "_low" to " · 4.18V 以上")[suffix].orEmpty()
            ma("${protocol}temp_${zone}_fastchg_current_ma$suffix", "$label ${protocolLabel}充电电流$suffixLabel", CURRENT)
        }
    } + listOf(
        ma("temp_warm_fastchg_current_ma_led_on", "高温区亮屏充电电流", CURRENT),
        ma("non_standard_fastchg_current_ma", "非标充电器充电电流", CURRENT),
        ma("short_c_bat_fastchg_current_ma", "电池微短路保护充电电流", CURRENT)
    )

    /** Zone -> (software full threshold, charger float voltage, over-voltage step-down threshold). */
    private val voltageTriples = zones.drop(1).map { (zone, label) ->
        Triple(mv("${zone}_vfloat_sw_limit", "$label 软件满充电压", VOLTAGE),
            mv("temp_${zone}_vfloat_mv", "$label 浮充电压", VOLTAGE),
            mv("${zone}_vfloat_over_sw_limit", "$label 过压降档电压", VOLTAGE))
    } + Triple(mv("non_standard_vfloat_sw_limit", "非标充电器 软件满充电压", VOLTAGE),
        mv("non_standard_vfloat_mv", "非标充电器 浮充电压", VOLTAGE),
        mv("non_standard_vfloat_over_sw_limit", "非标充电器 过压降档电压", VOLTAGE))

    private val ffcTriples = listOf(
        Triple("ffc_normal_vfloat_sw_limit", "ffc_temp_normal_vfloat_mv", "ffc_normal_vfloat_over_sw_limit"),
        Triple("ffc_normal_vfloat_sw_limit", "ffc1_temp_normal_vfloat_mv", "ffc1_normal_vfloat_over_sw_limit"),
        Triple("ffc2_normal_vfloat_sw_limit", "ffc2_temp_normal_vfloat_mv", "ffc2_normal_vfloat_over_sw_limit")
    )

    private val defaultTriples = listOf(
        Triple("default_normal_vfloat_sw_limit", "default_temp_normal_vfloat_mv", "default_normal_vfloat_over_sw_limit"),
        Triple("default_little_cool_vfloat_sw_limit", "default_temp_little_cool_vfloat_mv", "default_little_cool_vfloat_over_sw_limit")
    )

    private val bindings: List<Binding> = currentBindings +
        voltageTriples.flatMap { it.toList() } + listOf(
            mv("short_c_bat_vfloat_sw_limit", "电池微短路 软件满充电压", VOLTAGE),
            mv("short_c_bat_vfloat_mv", "电池微短路 浮充电压", VOLTAGE),
            mv("short_c_bat_cv_mv", "电池微短路 恒压判定电压", VOLTAGE),

            ma("ff1_normal_fastchg_ma", "FFC1 常温充电电流", FFC),
            ma("ff1_warm_fastchg_ma", "FFC1 高温充电电流", FFC),
            ma("ff1_exit_step_ma", "FFC1 常温退出电流", FFC),
            ma("ff1_warm_exit_step_ma", "FFC1 高温退出电流", FFC),
            ma("ffc2_normal_fastchg_ma", "FFC2 常温充电电流", FFC),
            ma("ffc2_warm_fastchg_ma", "FFC2 高温充电电流", FFC),
            ma("ffc2_exit_step_ma", "FFC2 常温退出电流", FFC),
            ma("ffc2_warm_exit_step_ma", "FFC2 高温退出电流", FFC),
            mv("ffc_normal_vfloat_sw_limit", "FFC1 常温软件满充电压", FFC),
            mv("ffc_temp_normal_vfloat_mv", "FFC 常温浮充电压", FFC),
            mv("ffc_normal_vfloat_over_sw_limit", "FFC 常温过压降档电压", FFC),
            mv("ffc1_temp_normal_vfloat_mv", "FFC1 常温浮充电压", FFC),
            mv("ffc1_normal_vfloat_over_sw_limit", "FFC1 常温过压降档电压", FFC),
            mv("ffc2_normal_vfloat_sw_limit", "FFC2 常温软件满充电压", FFC),
            mv("ffc2_temp_normal_vfloat_mv", "FFC2 常温浮充电压", FFC),
            mv("ffc2_normal_vfloat_over_sw_limit", "FFC2 常温过压降档电压", FFC),
            mv("ffc_warm_vfloat_sw_limit", "FFC1 高温软件满充电压", FFC),
            mv("ffc2_warm_vfloat_sw_limit", "FFC2 高温软件满充电压", FFC),

            ma("input_current_charger_ma", "DCP 充电器输入电流", INPUT),
            ma("pd_input_current_charger_ma", "PD 充电器输入电流", INPUT),
            ma("qc_input_current_charger_ma", "QC 充电器输入电流", INPUT),
            ma("input_current_usb_ma", "USB SDP 输入电流", INPUT),
            ma("input_current_cdp_ma", "USB CDP 输入电流", INPUT),
            ma("input_current_camera_ma", "相机开启时输入电流", INPUT),
            ma("input_current_calling_ma", "通话时输入电流", INPUT),
            ma("input_current_led_ma", "亮屏输入电流", INPUT),
            ma("input_current_led_ma_high", "亮屏输入电流 · 高温", INPUT),
            ma("input_current_led_ma_warm", "亮屏输入电流 · 温热", INPUT),
            ma("input_current_led_ma_normal", "亮屏输入电流 · 常温", INPUT),
            ma("input_current_vooc_led_ma_high", "VOOC 亮屏输入电流 · 高温", INPUT),
            ma("input_current_vooc_led_ma_warm", "VOOC 亮屏输入电流 · 温热", INPUT),
            ma("input_current_vooc_led_ma_normal", "VOOC 亮屏输入电流 · 常温", INPUT),
            ma("input_current_vooc_ma_high", "VOOC 输入电流 · 高温", INPUT),
            ma("input_current_vooc_ma_warm", "VOOC 输入电流 · 温热", INPUT),
            ma("input_current_vooc_ma_normal", "VOOC 输入电流 · 常温", INPUT),
            ma("charger_current_vooc_ma_normal", "VOOC 常温充电电流", INPUT),

            ma("iterm_ma", "充电终止电流", PROTECTION),
            mv("recharge-mv", "重新充电压差", PROTECTION),
            mv("vbatt_full_thr", "满电判定电压", PROTECTION),
            mv("vbatt_hv_thr", "电池过压保护阈值", PROTECTION),
            mv("vfloat_step_mv", "过压降档步进", PROTECTION),
            mv("charger_hv_thr", "充电器输入过压阈值", PROTECTION),
            mv("charger_recv_thr", "充电器过压恢复阈值", PROTECTION),
            mv("charger_lv_thr", "充电器输入欠压阈值", PROTECTION),
            mv("vbatt_pdqc_to_9v_thr", "PD/QC 升至 9V 的电池电压门限", PROTECTION),

            ma("default_iterm_ma", "默认充电终止电流", DEFAULTS),
            ma("default_temp_normal_fastchg_current_ma", "默认常温充电电流", DEFAULTS),
            ma("default_temp_little_cool_fastchg_current_ma", "默认微凉区充电电流", DEFAULTS)
        ) + defaultTriples.flatMap { (sw, vfloat, over) ->
            val zone = if ("little_cool" in sw) "微凉区" else "常温区"
            listOf(mv(sw, "默认$zone 软件满充电压", DEFAULTS), mv(vfloat, "默认$zone 浮充电压", DEFAULTS),
                mv(over, "默认$zone 过压降档电压", DEFAULTS))
        }

    /** (lower, upper, message) pairs the planner enforces on the merged form. */
    val orderedPairs: List<Triple<String, String, String>> = buildList {
        val triples = voltageTriples.map { (sw, vfloat, over) -> Triple(sw.parameter.name, vfloat.parameter.name, over.parameter.name) } +
            (ffcTriples + defaultTriples).map { (sw, vfloat, over) -> Triple("qcom,$sw", "qcom,$vfloat", "qcom,$over") }
        triples.forEach { (sw, vfloat, over) ->
            add(Triple(sw, vfloat, "软件满充电压不能高于浮充电压（${sw.removePrefix("qcom,")}）"))
            add(Triple(vfloat, over, "浮充电压不能高于过压降档电压（${vfloat.removePrefix("qcom,")}）"))
            add(Triple(over, "qcom,vbatt_hv_thr", "过压降档电压不能高于电池过压保护阈值（${over.removePrefix("qcom,")}）"))
        }
        add(Triple("qcom,short_c_bat_vfloat_sw_limit", "qcom,short_c_bat_vfloat_mv", "电池微短路软件满充电压不能高于其浮充电压"))
        add(Triple("qcom,charger_recv_thr", "qcom,charger_hv_thr", "过压恢复阈值不能高于输入过压阈值"))
        add(Triple("qcom,charger_lv_thr", "qcom,charger_recv_thr", "输入欠压阈值不能高于过压恢复阈值"))
    }

    fun matches(node: DeviceTreeNode): Boolean = signature.all { name -> node.properties.any { it.name == name } }

    fun fields(node: DeviceTreeNode): List<ChargingField>
    {
        if (!matches(node)) return emptyList()
        return bindings.mapNotNull { binding ->
            val properties = node.properties.filter { it.name == binding.parameter.name }
            val property = properties.firstOrNull() ?: return@mapNotNull null
            val value = DtsNumericValueCodec.decodeU32(property.rawValue)
            ChargingField(
                parameter = binding.parameter,
                exists = true,
                rawValue = property.rawValue,
                value = value,
                issue = when
                {
                    properties.size != 1 -> "属性重复，需先在设备树中修复"
                    value == null -> "OPlus 旧版仅支持单个 U32 标量；数组/引用继续保持只读"
                    else -> null
                },
                group = binding.group
            )
        }
    }
}
