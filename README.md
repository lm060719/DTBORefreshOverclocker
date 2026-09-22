[English](README_EN.md) | **简体中文**

# DTBO Studio

> 当前开发分支：`chatgpt/device-tree-core-phase1`  
> 当前应用版本：`1.1.5`

**DTBO Studio** 是一个面向 Android DTBO（Device Tree Blob Overlay）的解析、分析、编辑、验证与重构工具。

这个分支已经不再只是“刷新率超频器”。项目当前的核心方向是：

```text
DTBO 镜像
  ↓
DTBO Codec / DTC
  ↓
结构化 Device Tree Core
  ├─ 能力扫描 Capability Scanner
  ├─ 引用索引 Reference Index
  ├─ 刷新率 Planner
  ├─ 分辨率 Planner
  ├─ DSC Topology Analyzer
  └─ 通用节点 / 属性编辑
  ↓
DeviceTreeChange
  ↓
DeviceTreeTransaction
  ↓
回放验证 / 完整性校验
  ↓
DTBO 重建 / 导出 / 受控刷写
```

当前 UI 使用四个主页面：

- **概览**：镜像导入 / Root 提取、事务队列、打包与部署。
- **功能模块**：刷新率、分辨率、DSC、Charging 与能力扫描入口。
- **设备树**：结构化节点浏览、搜索、引用关系和通用编辑。
- **设置**：环境、日志、缓存、备份与其他高级入口。

---

## 当前分支实现状态

| 模块 | 当前状态 | Root 直刷 |
| --- | --- | --- |
| 刷新率 | ✅ 可修改 | ✅ 受安全策略约束 |
| 分辨率 | ✅ 等比例降分辨率 | ❌ 仅导出验证 |
| DSC | ✅ 拓扑分析 | ✅ 参数编辑（仅导出验证） |
| 通用设备树编辑 | ✅ 节点 / 属性级编辑 | ❌ 仅导出验证 |
| Capability Scanner | ✅ 自动扫描 | 不适用 |
| 引用索引 | ✅ label / path / local fixup / external fixup | 不适用 |
| 亮度 / HBM | 🔎 能力检测 | 暂未开放修改 |
| Thermal | 🔎 能力检测 | 暂未开放修改 |
| Charging | ✅ 参数识别与校验 | ✅ 参数编辑（仅导出验证） |
| Touch | 🔎 能力检测 | 暂未开放修改 |

> “发现相关节点”不等于“可以安全修改”。Capability Scanner 只负责识别当前 DTBO 中存在的相关结构，不会把模糊匹配自动变成写入操作。

---

## 1. 通用 Device Tree Core

当前分支已经建立独立于刷新率功能的通用设备树层：

- DTS 解析为结构化 `DeviceTreeDocument / DeviceTreeNode / DeviceTreeProperty`。
- 支持属性类型识别：
  - Boolean
  - String
  - String List
  - U32
  - U64
  - Cells
  - Byte Array
  - Phandle / Unknown
- 支持属性：
  - 添加
  - 修改
  - 删除
  - 类型化编辑
  - U32 十进制 / 十六进制输入
  - Raw DTS 查看
- 支持节点：
  - 添加子节点
  - 克隆
  - 重命名
  - 删除
- 对包含 `label / phandle / linux,phandle` 的节点克隆进行保护，避免制造重复节点身份。

所有通用修改最终都转换为可逆的 `DeviceTreeChange`。

---

## 2. 引用索引

设备树页面会建立独立的引用索引，当前识别：

- `&label`
- `&{/absolute/path}`
- `/__local_fixups__`
- `/__fixups__`
- `/__symbols__`
- 显式 `phandle / linux,phandle`

引用索引支持：

- 节点的 outgoing references
- 节点的 incoming references
- unresolved internal references
- external fixups
- 引用搜索与跳转

### Android 兼容说明

路径引用与 label 引用现在使用**手写扫描器**，不依赖 Android / JVM 对花括号正则表达式的实现差异。

因此 `&label` 与 `&{/path}` 的解析不会再因为 Android `PatternSyntaxException` 导致整个引用索引降级。

---

## 3. 刷新率模块

刷新率的生产修改路径已经迁移到通用 Device Tree Core：

```text
TimingParameterCalculator
  ↓
TimingDeviceTreePlanner
  ↓
DeviceTreeChange[]
  ↓
DeviceTreeEditor replay
  ↓
verifyReplay
  ↓
DeviceTreeTransaction
```

### 支持模式

- **覆盖现有档位**
- **新增独立档位**
- **删除档位**

新增档位会保留原模板节点，并生成新的同级 timing 节点；删除档位会检查剩余档位，并在需要时修正 `native-mode`。

### 参数策略

- Balanced Blanking Time
- Pixel Clock Only
- Framerate Only
- Custom

对存在 `qcom,mdss-mdp-transfer-time-us` 的档位，规划器会同步处理传输预算。

### 安全限制

- 厂商 dynamic / idle 模式不能直接作为普通高刷模板。
- `Framerate Only` 风险较高，不允许 Root 直刷。
- 新增节点会经过节点集合、原模板隔离和属性回放校验。

> `DtsTimingPatcher.analyzeEntry()` 当前仍用于候选发现。旧的 `DtsTimingPatcher.patch()` 不再属于正常生产写入路径，仅保留给回归测试作为历史行为对照。

---

## 4. 分辨率模块

当前分辨率模块采用保守策略，只支持**保持原宽高比的等比例降分辨率**。

它不会只修改 `panel-width / panel-height` 后直接输出，而是会检查能够证明存在耦合关系的显示参数。

当前会处理：

- Panel width
- Panel height
- DSC slice width
- 已验证结构的 ROI alignment

例如在保持原横向 DSC slice 数量的前提下：

```text
1440 × 3200
slice-width = 720
horizontal slices = 2

↓ 等比例降分辨率

1080 × 2400
slice-width = 540
horizontal slices = 2
```

### 分辨率安全边界

规划器会拒绝：

- 提高原生分辨率
- 改变宽高比
- DSC slice 拓扑无法整除
- 未识别的 ROI 结构
- 无法证明安全关联关系的厂商私有参数

当前分辨率事务统一标记为：

`EXPORT_ONLY`

因此**不能在应用内 Root 直刷**，必须先导出镜像 / Recovery ZIP / Fastboot 包进行设备侧验证。

---

## 5. DSC 参数编辑与拓扑分析

DSC 模块支持编辑所选节点的 version、BPC、BPP、slice width/height、slice-per-packet 和 block prediction。修改会作为单个事务暂存，可在概览中撤销、集中打包和导出。

暂存前检查正数、参数范围、面板宽高整除关系和每包 slice 数整除关系。未定义的 version、BPC/BPP 可以留空；其他字段需要填写有效整数。

当前解析：

- DSC version
- SCR version raw value
- bits per component
- bits per pixel
- block prediction
- slice width
- slice height
- slice per packet
- 横向 slice 数
- 纵向 slice 数
- 每帧 slice 数
- ROI alignment

并检查：

- panel width 是否能被 slice width 整除
- panel height 是否能被 slice height 整除
- `slice-per-pkt` 是否超出横向 slice 数
- BPC / BPP 是否落在常见范围
- ROI 是否符合当前已验证结构

**DSC 修改仅支持导出验证，不自动同步 PPS、RC range 或厂商 DSI command。拓扑校验不代表设备兼容性验证。**

---

### Charging 参数编辑

Charging 提供与分辨率模块一致的节点选择、参数输入、修改预览和暂存操作。支持 Qualcomm 充电器、电池配置和标准 `simple-battery` 中已识别的电流、电压、重新充电阈值，以及 SMB5 的 HVDCP / USB PD 禁用开关。

支持小米 MCA 的降压充电、快充、无线充电及反向充电配置，并按通道编辑有线/无线温控表。参数较多时提供分组和分页，切换后保留草稿；温控表按同一通道检查档位顺序，充电泵倍率数组独立编辑。

- 明确区分 DTB 条目和节点路径，只修改所选节点。
- mA / mV 输入精确转换至属性原始单位，保留 µA / µV 精度。
- 提交前校验完整表单；旧扫描结果、超范围输入、相关电流/电压冲突会被拒绝。
- 暂存后可在概览中撤销、集中打包、导出镜像或刷机包；禁止 Root 直刷。
- 支持经 `__fixups__` 定位的 QTI battery charger overlay，以及已确认单位的温控限流数组逐档编辑；保持档位数量并校验非递增顺序。
- 未识别的厂商属性、其他温控/JEITA 数组和设备树引用按原样保留，可展开查看；不凭数值大小猜测单位或创建数值属性。

支持范围与绑定来源见 [Charging 参数说明](docs/charging.md)。

## 6. Capability Scanner

导入 DTBO 后，应用会在后台扫描当前工作区并生成能力报告。

当前分类：

- Refresh Rate
- Resolution
- DSC
- Brightness / HBM
- Thermal
- Charging
- Touch

功能模块页会根据扫描结果显示：

- 可用
- 可分析
- 已发现
- 未发现

如果当前 DTBO 未发现 Thermal / Charging / Touch，并不代表设备没有这些功能；对应配置也可能位于：

- 基础 DTB
- vendor_boot
- vendor_dlkm
- 独立 overlay
- 驱动内部配置

---

## 7. DeviceTreeTransaction 统一事务层

当前 ViewModel 不再把刷新率、分辨率、通用编辑分别维护成多套独立暂存状态。

所有写入统一封装为：

`DeviceTreeTransaction`

事务携带：

- 类型
- 摘要
- 底层 `DeviceTreeChange[]`
- 风险等级
- `directFlashAllowed`
- warnings
- 影响的 DTB Entry

当前事务类型：

- `REFRESH_RATE`
- `RESOLUTION`
- `GENERIC_EDIT`

当前风险等级：

- `TRUSTED`
- `CAUTION`
- `EXPORT_ONLY`

概览页会显示事务数量和底层操作数量，并支持**撤销最近事务**。

事务撤销会按底层操作逆序执行 `inverse()`，保证一次逻辑修改作为整体回滚。

---

## 8. DTBO Codec 与重建

`DtboImageCodec` 是纯 Kotlin/JVM DTBO table parser + rebuilder。

当前支持：

- DTBO table version 0
- DTBO table version 1
- DTBO table version 2
- 未压缩 entry
- zlib
- gzip
- LZ4 Frame

重建时：

- 保留 entry ID / rev / flags / custom fields
- 保留原始 metadata prefix
- 未修改 entry 尽量直接复用原 payload
- 只重新计算必须变化的 size / offset
- 输出后再次解析并校验 DTB 字节

项目不依赖 Python 或 `mkdtboimg`。

DTS / DTB 编译仍需要随 APK 打包的 ARM64 DTC 可执行文件：

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

虽然扩展名为 `.so`，它在项目中作为 AArch64 ELF 可执行文件使用，而不是通过 `System.loadLibrary()` 加载的普通 JNI 动态库。

---

## 9. Root、备份与部署安全

应用支持：

- SAF 导入本地 `dtbo.img`
- Root 提取当前活动槽位 DTBO
- 当前面板检测
- DTBO 备份
- 哈希校验
- Recovery Rescue ZIP
- Recovery 部署 ZIP
- PC Fastboot Bundle
- 受控 Root 直刷

Root 直刷前会检查事务队列：

```text
transactions.all { it.directFlashAllowed }
```

只要事务队列包含：

- 分辨率修改
- 通用设备树自由编辑
- 其他 export-only 事务

应用就会禁止直接 Root 刷写。

---

## 10. 源码结构

```text
app/src/main/java/io/mo/dtbooverclocker/
├── core/
│   ├── ActivePanelDetector.kt
│   ├── CapabilityScanner.kt
│   ├── DscTopologyAnalyzer.kt
│   ├── DtboImageCodec.kt
│   ├── DtboPatchEngine.kt
│   ├── DtsTimingPatcher.kt          # 候选发现 + legacy 回归 oracle
│   ├── ResolutionPlanner.kt
│   ├── TimingDeviceTreePlanner.kt
│   ├── TimingParameterCalculator.kt
│   ├── BackupManager.kt
│   ├── SafetyGuardManager.kt
│   └── devicetree/
│       ├── DeviceTreeModels.kt
│       ├── DeviceTreeParser.kt
│       ├── DeviceTreeEditor.kt
│       ├── DeviceTreeChange.kt
│       ├── DeviceTreeDiff.kt
│       ├── DeviceTreeReferenceIndex.kt
│       ├── DeviceTreeTransaction.kt
│       ├── DeviceTreeValueCodec.kt
│       └── DtsNumericValueCodec.kt
├── model/
│   ├── Models.kt
│   ├── FeatureModuleModels.kt
│   ├── CapabilityModels.kt
│   └── BackupModels.kt
├── ui/
│   ├── MainActivity.kt
│   ├── MainViewModel.kt
│   ├── StudioScreen.kt
│   ├── DeviceTreeScreen.kt
│   ├── ResolutionPanel.kt
│   ├── DscAnalysisPanel.kt
│   └── components/
└── util/
```

---

## 11. 构建环境

当前分支实际配置：

| 项目 | 版本 |
| --- | --- |
| App | 1.1.5 |
| Kotlin | 2.4.20 |
| Android Gradle Plugin | 9.3.0 |
| Gradle Wrapper | 9.6.1 |
| JDK | 17 |
| compileSdk | 37 |
| targetSdk | 37 |
| minSdk | 26 |
| Compose BOM | 2026.08.00 |

### Debug 构建

Linux / macOS：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

---

## 12. GitHub Actions 与构建产物

Workflow：

```text
.github/workflows/dtbo-studio-ui.yml
```

名称：

`DTBO Studio UI validation`

对 `chatgpt/**` 分支 push 后会依次执行：

1. Unit tests
2. Assemble debug
3. Package source
4. Upload source archive
5. Upload Debug APK

GitHub 页面路径：

```text
Repository
  → Actions
  → DTBO Studio UI validation
  → 选择某一次 Run
  → Artifacts
```

成功构建后会看到：

- `DTBOStudio_Debug_APK`
  - 包含 `app-debug.apk`
  - 当前 workflow 保留 14 天
- `DTBOStudio_Source`
  - 对应这次 Run 的源码归档

---

## 13. 当前已知边界

这个分支仍然是开发分支，不应把“能够解析”直接理解为“所有设备都能安全修改”。

当前主要边界：

1. 刷新率候选发现仍依赖 `DtsTimingPatcher.analyzeEntry()`，后续会继续迁移到结构化语义扫描器。
2. DSC 支持核心参数编辑并导出验证，暂不自动同步 PPS、RC range 或厂商命令。
3. 分辨率只开放能通过现有拓扑约束证明的等比例降分辨率。
4. Charging 支持已识别参数的编辑与导出验证；Thermal / Touch / HBM 当前只做能力发现。
5. 通用设备树自由编辑和分辨率事务禁止 Root 直刷。
6. 真机是否能够启动和稳定工作，最终仍必须由对应设备验证。

---

## 14. 风险提示

修改 DTBO 属于底层系统与硬件配置操作。

错误的显示时序、DSC 参数、设备树引用或厂商私有属性可能导致：

- 黑屏
- 显示驱动初始化失败
- Bootloop
- 触控 / 显示异常
- 分区内容损坏
- 极端情况下的硬件风险

在进行任何物理刷写前，请确保：

- Bootloader 已解锁
- 已保留原始 DTBO
- 已准备 Fastboot / Recovery 救援方式
- 清楚当前修改事务的风险等级
- 不绕过应用的 export-only 限制

---

## 15. 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
