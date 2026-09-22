package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.model.ChargingField
import io.mo.dtbooverclocker.model.ChargingParameter

/** MiCode/kernel_devicetree, dada-v-oss: qcom/mca.dtsi and dada-charger-common.dtsi.
 * Match compatible, never a substring of a node name. Preserve unknown vendor tables.
 */
internal object McaChargingBindings {
    private data class Binding(val parameter: ChargingParameter, val columns: List<String> = emptyList(), val thermal: Boolean = false)
    private fun scalar(name: String, label: String, unit: String) = Binding(ChargingParameter(name, label, unit, minimum = 0))
    private fun array(name: String, label: String, unit: String, columns: List<String>) =
        Binding(ChargingParameter(name, label, unit, minimum = 0), columns)
    private val ratios = listOf("DIV1", "DIV2", "DIV4")
    private val quickCommon = listOf(
        scalar("min_vbat", "启动电池电压下限", "mV"),
        scalar("max_vbat", "电池电压上限", "mV"),
        scalar("recharge_vbat", "重新充电电压", "mV"),
        array("div_single_curr", "单路充电电流", "mA", ratios),
        array("div_max_curr", "充电电流上限", "mA", ratios),
        array("multi_ibus_th", "多路切换电流阈值", "mA", ratios),
        array("ibus_inc_hysteresis", "输入电流增加回差", "mA", ratios),
        array("ibus_dec_hysteresis", "输入电流减少回差", "mA", ratios)
    )
    private val bindings = mapOf(
        "mca,strategy_buckchg" to buildList {
            val protocols = listOf("dcp" to "DCP", "pd" to "PD", "hvdcp" to "QC 2.0", "hvdcp3" to "QC 3.0",
                "hvdcp3p5" to "QC 3.5", "cdp" to "CDP", "sdp" to "SDP", "float" to "Float")
            protocols.forEach { (key, label) -> add(scalar("in_$key", "$label 输入电流", "mA")) }
            protocols.forEach { (key, label) -> add(scalar("chg_$key", "$label 充电电流", "mA")) }
            add(scalar("chg_batt_auth_failed", "电池认证失败时充电电流", "mA"))
            add(scalar("pmic_fv_compensation", "PMIC 浮充电压补偿", "mV"))
            listOf("rev_req_vadp" to "反向充电请求电压", "rev_vadp_valid_h" to "反向充电电压上限",
                "rev_vadp_valid_l" to "反向充电电压下限").forEach { (key, label) ->
                add(array(key, label, "mV", listOf("PPS / 已认证 PD", "其他协议")))
            }
        },
        "mca,quick_charger" to quickCommon + listOf(
            scalar("qc_normal_charge_fv", "QC 普通充电浮充电压", "mV"),
            scalar("qc3_max_vbus_limit", "QC 3.0 输入电压上限", "mV"),
            scalar("qc3_max_ibus_limit", "QC 3.0 输入电流上限", "mA"),
            scalar("qc3_ibat_max_limit", "QC 3.0 电池电流上限", "mA"),
            scalar("qc3p5_max_vbus_limit", "QC 3.5 输入电压上限", "mV"),
            scalar("qc3p5_max_ibus_limit", "QC 3.5 输入电流上限", "mA"),
            scalar("qc3p5_ibat_max_limit", "QC 3.5 电池电流上限", "mA"),
            scalar("qc_taper_fcc_thr", "QC 收尾电流阈值", "mA"),
            scalar("pps_taper_fcc_thr", "PPS 收尾电流阈值", "mA"),
            scalar("qc3_taper_vol_hys", "QC 3.0 收尾电压回差", "mV"),
            scalar("qc3p5_taper_vol_hys", "QC 3.5 收尾电压回差", "mV"),
            scalar("pps_taper_vol_hys", "PPS 收尾电压回差", "mV"),
            scalar("cp_switch_pmic_th", "切换至 PMIC 电流阈值", "mA"),
            array("buck_icl_fcc_curr", "并行降压充电电流", "mA", listOf("输入电流", "充电电流")),
            array("div_delta_volt", "充电泵电压增量", "mV", ratios),
            array("div_delta_ibat", "充电泵电池电流增量", "mA", ratios),
            array("open_path_th", "开启通路电流阈值", "mA", ratios),
            array("ibus_compensation", "输入电流补偿", "mA", ratios)
        ),
        "mca,quick_wireless" to quickCommon,
        "mca,basic_wireless" to listOf(
            scalar("phone_icl", "无线反向供电输入电流", "mA"),
            scalar("phone_vol", "无线反向供电电压", "mV"),
            scalar("offstd_phone_icl", "非标准无线供电输入电流", "mA"),
            scalar("pmic_fv_compensation", "PMIC 浮充电压补偿", "mV"),
            array("rx_max_iout", "无线接收输出电流上限", "mA", listOf("配置 1", "配置 2"))
        ),
        "mca,wireless_revchg" to listOf(scalar("rev_boost_voltage", "无线反向充电升压电压", "mV")),
        "mca_charger_thermal" to listOf(
            Binding(ChargingParameter("wired_thermal", "有线温控", "mA", minimum = 0, descendingStride = 10),
                listOf("5 V 输入", "9 V 输入", "5 V 充电", "9 V 充电", "DIV1 单路", "DIV1 多路", "DIV2 单路", "DIV2 多路", "DIV4 单路", "DIV4 多路"), true),
            Binding(ChargingParameter("wireless_thermal", "无线温控", "mA", minimum = 0, descendingStride = 10),
                listOf("BPP 输入", "BPP QC2", "BPP QC3", "EPP 输入", "CP 20 W", "CP 30 W", "CP 50 W", "CP 80 W", "音箱", "磁吸 30 W"), true)
        )
    )

    fun fields(node: DeviceTreeNode, compatible: String?): List<ChargingField> {
        val matches = compatible?.split('"').orEmpty().mapNotNull(bindings::get)
        // Ambiguous driver identities must not combine different schemas.
        val schema = matches.singleOrNull() ?: return emptyList()
        return schema.flatMap { binding ->
            val properties = node.properties.filter { it.name == binding.parameter.name }
            val property = properties.firstOrNull() ?: return@flatMap emptyList()
            val cells = if (binding.columns.isEmpty()) DtsNumericValueCodec.decodeU32(property.rawValue)?.let(::listOf)
                else DtsNumericValueCodec.decodeCells(property.rawValue)
            val validShape = cells != null && if (binding.thermal) cells.isNotEmpty() && cells.size % binding.columns.size == 0
                else cells.size == binding.columns.size.coerceAtLeast(1)
            if (properties.size != 1 || !validShape) return@flatMap listOf(ChargingField(binding.parameter, true, property.rawValue,
                null, if (properties.size != 1) "属性重复，需先在设备树中修复" else "格式或数组长度与 MCA 配置不符，保留原始内容"))
            requireNotNull(cells).mapIndexed { index, value ->
                val column = binding.columns.getOrNull(index % binding.columns.size.coerceAtLeast(1))
                val group = if (binding.thermal) "${binding.parameter.label} · $column"
                    else if (column != null) binding.parameter.label else "基础参数"
                val label = if (binding.thermal) "$group · 第 ${index / binding.columns.size + 1} 档"
                    else if (column != null) "${binding.parameter.label} · $column" else binding.parameter.label
                ChargingField(binding.parameter.copy(label = label), true, property.rawValue, value,
                    cellIndex = if (binding.columns.isEmpty()) null else index, group = group)
            }
        }
    }
}
