# Charging 参数编辑

功能模块中的 Charging 面板按 **DTB 索引 + 完整节点路径**定位属性。面板显示当前工作区数值，包括已暂存的修改；“重置未暂存输入”只清空当前表单草稿，已暂存内容在概览中撤销或全部重置。

数值按 mA / mV 输入。以 µA / µV 存储的参数允许最多三位有效小数，使用十进制定点换算，拒绝非整数原始单位、负数、空值和 U32 溢出，不截断或四舍五入。单值参数支持单 Cell、四字节数组或 DTC 输出的四字节字符串；已确认绑定的温控电流数组逐档编辑。引用、格式异常和重复属性不能写入。原有厂商特殊值在没有修改时保留。

## 支持的属性

| 属性 | 原始单位 / 类型 | 显示单位 |
| --- | --- | --- |
| `qcom,fcc-max-ua`, `qcom,usb-icl-ua`, `qcom,dc-icl-ua` | µA | mA |
| `qcom,usb-ocl-ua`, `qcom,wls-current-max-ua`, `qcom,boost-threshold-ua` | µA | mA |
| `qcom,fv-max-uv`, `qcom,max-voltage-uv` | µV | mV |
| `qcom,fastchg-current-ma`, `qcom,chg-term-ua` | mA / µA | mA |
| `qcom,float-voltage-mv`, `qcom,auto-recharge-vbat-mv` | mV | mV |
| `qcom,auto-recharge-soc` | 0–100 | % |
| `qcom,chg-inhibit-threshold-mv` | 50 / 100 / 200 / 300 mV | mV |
| `constant-charge-current-max-microamp`, `precharge-current-microamp`, `charge-term-current-microamp` | µA | mA |
| `constant-charge-voltage-max-microvolt`, `precharge-upper-limit-microvolt` | µV | mV |
| `re-charge-voltage-microvolt`, `over-voltage-threshold-microvolt` | µV | mV |
| `qcom,hvdcp-disable`, `qcom,usb-pd-disable` | 空布尔属性，存在表示禁用 | 禁用开关 |
| `qcom,thermal-mitigation`（QTI Glink battery charger） | µA 数组 | 每档 mA |

数值属性必须已存在；仅明确声明 `compatible = "qcom,qpnp-smb5"` 的节点允许创建原本缺失的两个协议禁用开关。其他节点上已有的同名开关可以编辑，非空布尔属性不会强行转换。

同一节点内，校验已识别的预充/终止电流与最大充电电流、预充电压与恒压上限、恒压上限与过压阈值，以及重新充电电压与浮充上限。USB 输入电流与电池充电电流属于不同电路侧，不直接比较。不会跨电池型号或 DTB 条目自动同步。

修改先在内存中校验、生成事务并回读，成功后才写入工作区。暂存前重新扫描实际 DTS，拒绝过期表单；写入后的分析失败会恢复原 DTS。事务接入现有撤销、DTC 编译、属性差异校验、镜像重建和导出流程。

## 匿名 overlay 与温控限流表

通过 `__fixups__` 中的 `fragment:target:0` 定位外部目标，兼容 DTC 的独立字符串列表和内嵌 `\0` 字符串。元数据本身不参与编辑。明确的 `qcom,battery-charger` compatible，或外部目标 `battery_charger` 同时带有 `qcom,wireless-fw-name` 的 overlay，支持 `qcom,thermal-mitigation` 的 µA 档位数组；同名属性在其他驱动中的单位可能不同，未确认的绑定保持只读。

参考 `dtbo_a.img` 的可编辑节点为 `/fragment@22/__overlay__`，包含四档 `3000000 / 1500000 / 1000000 / 500000 µA`，界面显示 `3000 / 1500 / 1000 / 500 mA`。逐档修改保持数组长度，要求后档不高于前档，允许末档为零。整张表作为一个底层属性操作暂存与撤销，不改 `target`、`__fixups__`、固件文件名及相邻节点。这里修改的是温控限流，不是 USB 供电功率或驱动的无温控最高电流。

真实镜像回归可运行 `gradlew :app:testDebugUnitTest -PchargingSampleImage=<dtbo_a.img绝对路径>`；镜像留在本机，不加入源码仓库。

## 小米 MCA 配置

按完整 `compatible` 识别 `mca,strategy_buckchg`、`mca,quick_charger`、`mca,basic_wireless`、`mca,quick_wireless`、`mca,wireless_revchg` 与 `mca_charger_thermal`。支持已存在的输入/充电电流、电池电压、QC 电流电压限制、充电泵分倍率电流、无线接收电流和反向充电电压。MCA 的这些电流/电压配置使用 mA/mV，不套用 Qualcomm 的 µA/µV 换算。

`wired_thermal` 和 `wireless_thermal` 为每档 10 列的温控表。按原始通道分组展示，每个通道独立检查后档不高于前档；DIV1/DIV2/DIV4 参数数组不使用温控排序规则。数组长度、未知属性、节点状态和协议位掩码保持不变。每个底层数组作为一个可撤销操作写入。界面统计可编辑参数总数，提供分组和分页；切换页面/分组不丢失草稿，暂存包含当前节点所有分组的修改。

手机实际 DTC 输出回归：`-PchargingSampleDts=<entry_0.dts>`。同时传入 `-PchargingDeviceImage=<原始镜像>` 和 `-PhostDtc=<dtc可执行文件>` 可验证修改后 DTC 编译、二进制属性差异及 DTBO 重建。测试输出保存在 DTS 同目录；镜像不加入源码仓库。

## 边界

这是设备树参数编辑，不是实时充电控制。未发现可编辑字段时，面板显示原始节点属性与说明。尚未确认的厂商私有参数、JEITA/字符串曲线表、其他格式温控表、协议协商开关、驱动寄存器与电池校准表保持不变。通用 U32 与关联参数检查不能证明某个芯片支持该值或步进，需结合目标设备规格和驱动确认；事务沿用分辨率/DSC 的 `EXPORT_ONLY` 策略。

## 绑定来源

- [小米 MCA 默认配置](https://github.com/MiCode/kernel_devicetree/blob/dada-v-oss/qcom/mca.dtsi)
- [小米机型充电配置及温控表列定义](https://github.com/MiCode/kernel_devicetree/blob/dada-v-oss/qcom/dada-charger-common.dtsi)

- [QTI battery charger binding（内核源码）](https://github.com/cawilliamson/android_kernel_samsung_q2q/blob/leankernel/arch/arm64/boot/dts/vendor/bindings/power/supply/qcom%2Cbattery-charger.txt)
- [Linux battery binding](https://www.kernel.org/doc/Documentation/devicetree/bindings/power/supply/battery.yaml)
- [Qualcomm SMB5 binding](https://android.googlesource.com/kernel/msm/+/refs/heads/android-msm-coral-4.14-android13/Documentation/devicetree/bindings/power/supply/qcom/qpnp-smb5.txt)
- [Qualcomm battery profile binding](https://android.googlesource.com/kernel/msm/+/android-msm-bluecross-4.9-pie-qpr1/Documentation/devicetree/bindings/batterydata/batterydata.txt)
- [Qualcomm SMB charger binding introduction](https://android.googlesource.com/kernel/msm/+/8efc032aa91582aae00ad071edbbc2fe167820aa%5E2..8efc032aa91582aae00ad071edbbc2fe167820aa/)
