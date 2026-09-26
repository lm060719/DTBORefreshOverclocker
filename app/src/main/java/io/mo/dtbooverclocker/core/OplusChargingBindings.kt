package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.model.ChargingField
import io.mo.dtbooverclocker.model.ChargingParameter

/**
 * OPlus / OnePlus 充电配置的第一阶段保守绑定。
 *
 * 仅开放同时满足以下条件的属性：
 * 1. 节点 compatible 精确命中已知 OPlus 充电驱动；
 * 2. 属性名自身明确携带 mA / mV 单位；
 * 3. 当前值严格是单个 U32。
 *
 * supplied OPlus dtbo_a.img 中还包含大量 FFC、BCC、温区、SOC 区间和无线策略数组。
 * 在列语义和驱动约束未验证前，这些表继续保持只读，不能仅凭数值形状开放修改。
 */
internal object OplusChargingBindings
{
    private data class Binding(
        val parameter: ChargingParameter,
        val group: String
    )

    private fun ma(name: String, label: String, group: String): Binding =
        Binding(
            ChargingParameter(
                name = name,
                label = label,
                unit = "mA",
                minimum = 0
            ),
            group
        )

    private fun mv(name: String, label: String, group: String): Binding =
        Binding(
            ChargingParameter(
                name = name,
                label = label,
                unit = "mV",
                minimum = 0
            ),
            group
        )

    private val bindings = mapOf(
        "oplus,common-charge" to listOf(
            ma("oplus_spec,iterm-ma", "充电终止电流", "OPlus · 通用充电"),
            mv("oplus_spec,non-standard-vbatdet-mv", "非标准充电电池检测电压", "OPlus · 通用充电"),
            mv("oplus_spec,fcc-gear-thr-mv", "FCC 档位切换电压阈值", "OPlus · 通用充电"),
            mv("oplus_spec,vbatt-ov-thr-mv", "电池过压阈值", "OPlus · 通用充电"),
            mv("oplus_spec,full-pre-ffc-mv", "FFC 前满充判断电压", "OPlus · 通用充电"),
            mv("oplus_spec,vbat_uv_thr_mv", "电池欠压阈值", "OPlus · 通用充电"),
            mv("oplus_spec,vbat_charging_uv_thr_mv", "充电状态电池欠压阈值", "OPlus · 通用充电"),
            mv("oplus,ui_soc_2_voltage_comp_mv", "低电量显示电压补偿", "OPlus · 通用充电"),
            mv("oplus,chg_shutdown_max_mv", "充电关机电压阈值", "OPlus · 通用充电")
        ),
        "oplus,chg_wls" to listOf(
            mv("oplus,max-voltage-mv", "无线充电最大电压", "OPlus · 无线充电"),
            ma("oplus,fastchg_curr_max_ma", "无线快充最大电流", "OPlus · 无线充电"),
            ma("oplus,verity_curr_max_ma", "无线认证阶段最大电流", "OPlus · 无线充电"),
            ma("oplus,wls-fast-chg-call-on-curr-ma", "通话时无线快充电流", "OPlus · 无线充电"),
            ma("oplus,wls-fast-chg-camera-on-curr-ma", "相机开启时无线快充电流", "OPlus · 无线充电"),
            ma("oplus,wls_boost_curr_limit_ma", "无线升压电流上限", "OPlus · 无线充电"),
            mv("oplus,bpp-vol-mv", "BPP 工作电压", "OPlus · 无线协议电压"),
            mv("oplus,epp-vol-mv", "EPP 工作电压", "OPlus · 无线协议电压"),
            mv("oplus,epp_plus-vol-mv", "EPP+ 工作电压", "OPlus · 无线协议电压"),
            mv("oplus,vooc-vol-mv", "VOOC 无线电压", "OPlus · 无线协议电压"),
            mv("oplus,svooc-vol-mv", "SVOOC 无线电压", "OPlus · 无线协议电压"),
            mv("oplus,fastchg-init-vout-mv", "无线快充初始输出电压", "OPlus · 无线协议电压"),
            mv("oplus,full-bridge-vout-mv", "全桥输出电压", "OPlus · 无线协议电压"),
            mv("oplus,cp-open-offset-mv", "充电泵开启电压偏移", "OPlus · 无线协议电压"),
            mv("oplus,cp-open-offset-min-mv", "充电泵最小开启电压偏移", "OPlus · 无线协议电压"),
            mv("oplus,fastch-wait-thr-mv", "无线快充等待电压阈值", "OPlus · 无线协议电压")
        ),
        "oplus,pps_charge" to listOf(
            ma("oplus,curr_max_ma", "PPS 最大电流", "OPlus · PPS")
        ),
        "oplus,ufcs_charge" to listOf(
            ma("oplus,curr_max_ma", "UFCS 最大电流", "OPlus · UFCS")
        ),
        "oplus,virtual_cp" to listOf(
            ma("oplus,input_curr_max_ma", "虚拟充电泵输入电流上限", "OPlus · 充电泵")
        )
    )

    fun fields(node: DeviceTreeNode, compatible: String?): List<ChargingField>
    {
        val compatibles = compatible?.split('"').orEmpty()
        val schemas = compatibles.mapNotNull(bindings::get)

        // 一个节点同时宣告多个已知 OPlus 充电 compatible 时不猜测使用哪个 schema。
        val schema = schemas.singleOrNull() ?: return emptyList()

        return schema.mapNotNull { binding ->
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
                    value == null -> "OPlus 第一阶段仅支持单个 U32 标量；数组/引用继续保持只读"
                    else -> null
                },
                group = binding.group
            )
        }
    }
}
