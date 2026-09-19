[English](README_EN.md) | **简体中文**

# DTBO Refresh Overclocker

**DTBO Refresh Overclocker** 是一款专为 Android 设备打造的屏幕刷新率超频与时序深度调校工具。通过对设备树重叠分层镜像（DTBO，Device Tree Blob Overlay）的精准解构、时序重算与安全重构，帮助用户突破物理面板出厂刷新率限制，获得更流畅的高刷显示体验。

应用采用 **免 Root（SAF 离线处理）** 与 **Root（活跃槽位直刷）** 双工作流架构，内置自主研发的纯 Kotlin DTBO 编解码引擎、当前物理屏幕自动探测算法、多档位连续暂存与集中打包机制，以及严密的三层防砖安全保障体系。

---


## 🌟 核心特性

### 1. 纯 Kotlin DTBO 编解码引擎（零 Python / 无需 mkdtboimg）
- **规范全支持**：原生支持 AOSP DTBO 表版本 **v0、v1 以及 2026 最新 v2 规范**（含 v2 64 字节表项头格式）。
- **元数据无损保留**：严格保留原始镜像的 `magic`、`header_size`、`entry_size`、`page_size`、`version`、`flags`、条目顺序、条目 ID、硬件版本号（rev）及全部自定义字段（custom fields）。
- **主流压缩格式支持**：纯 Kotlin 解压与重构未压缩条目、zlib 压缩条目、gzip 压缩条目以及 **LZ4 Frame** 压缩条目，非修改条目直接复用原始 Payload 字节，杜绝重复压缩损耗。
- **写后二次反编译校验**：重构完成后，镜像通过独立解码器二次反编译回读验证，确保生成的 DTB/DTS 属性完全符合目标要求，方可进入导出或刷写流程。

### 2. 活跃屏幕面板智能探测（Active Panel Detector）
- **多层级硬件识别算法**：
  1. **Tier 1 - 内核启动命令行**：探测 `/proc/cmdline` 中的 `msm_drm.dsi_display` 与 `androidboot.panel` 参数。
  2. **Tier 2 - 小米 / 红米显示驱动**：读取 `/sys/class/mi_display/disp-DSI-0/panel_info` 及各级节点。
  3. **Tier 3 - 其他主流厂商节点**：探测 OPPO/一加 `oplus_display/panel_name`、触控驱动 `touchpanel/panel_name` 以及各类 LCD 显示驱动节点。
- **智能高亮匹配**：在 DTBO 包含多个屏幕时序（如混用多种屏幕供应商）时，自动识别当前手机正在点亮的物理屏幕面板，并在界面中高亮标出候选节点，避免改错屏幕时序导致黑屏。

### 3. 多样化时序修改模式（Patch Modes）
- **编辑修改档位（Overwrite Existing）**：将选中的原始时序档位（如 60Hz 或 120Hz）直接替换为目标刷新率（如 144Hz）。
- **新增独立档位（Append New）**：完整保留原有时序档位，以此为基准克隆并插入全新的同级时序节点。支持智能节点命名解析（识别 `@<num>`、`-<num>`、`_<num>` 或 `_${hz}hz` 后缀），实现真正的“无损新增高刷档位”。
- **删除指定档位（Delete Existing）**：移除冗余或导致异常的刷新率节点。内置安全基准防护（禁止删除面板最后唯一的档位，确保屏幕开机能够正常点亮），并自动检测与重定向设备树中的 `native-mode` 开机默认时序引用，防止开机黑屏。

### 4. 科学的时序计算策略（Calculation Strategies）
- **平衡消隐时间（Balanced Blanking Time，推荐默认）**：
  - 基于物理显示时序公式：$\text{Refresh} \propto \frac{\text{Pixel Clock}}{\text{VTotal}}$。
  - 同步等比放缩 Pixel Clock 与垂直前肩（VFP）/ 垂直后肩（VBP），维持消隐区（Blanking）的时间尺度恒定，保持水平时序与垂直同步脉冲（VSync）不变。
  - 具备时钟倍率安全边界检查（0.50x ~ 3.00x），稳定性与色彩表现最高。
- **仅像素时钟（Pixel Clock Only）**：
  - 锁定前后肩等消隐区参数不变，仅按刷新率比例 $\frac{\text{Target Hz}}{\text{Current Hz}}$ 放缩 Pixel Clock。
- **仅帧率属性（Framerate Only）**：
  - 仅修改 `panel-framerate` 描述属性，不改动硬件时钟。此模式风险较高且兼容性因设备而异，**仅允许离线导出，严禁直接刷入物理分区**。
- **自定义时序参数（Custom Timing）**：
  - 专为高级玩家设计，允许手动精确指定 Pixel Clock（Hz/MHz）、垂直前肩（VFP）、垂直后肩（VBP）、水平前肩（HFP）与水平后肩（HBP）。

### 5. 实时可视化时序图谱与预览卡片
- **时序几何图谱（Timing Geometry Chart）**：以可视化图形直观展示 Active 有效显示区、Front Porch（前肩）、Back Porch（后肩）以及 Sync Pulse（同步脉冲）的几何占比与物理结构。
- **超频实时预览卡片（Overclock Preview Card）**：在确认修改前，实时对比超频前后的 Pixel Clock、H-Total、V-Total、刷新率、MIPI DSI 估算吞吐带宽等关键电气参数。

### 6. 连续暂存修改与集中打包工作流（Staged Changes Workflow）
- 支持对多个面板、多个档位进行连续修改、新增或删除，操作自动记录至**暂存修改清单**。
- 支持随时清空或重置工作区（恢复到初始解析状态）。
- 点击**集中打包生成**，引擎将批量重编译所有受影响的 DTB 节点，并由纯 Kotlin DTBO builder 一次性合成最终镜像，大幅简化复杂的多档位调校流程。

### 7. 时间轴备份与一键安全回滚（Timeline Backup & Rollback）
- **双模式备份机制**：
  - **自动备份**：在每次物理刷写前强制完整备份当前硬件分区。
  - **手动备份**：支持用户在任何时刻主动抓取当前 DTBO 分区并填写备注说明。
- **多维度元数据记录**：记录生成时间、Android 版本、系统 Build ID、设备型号、活跃槽位、物理块设备路径、文件尺寸、MD5 与 SHA-256 双重校验值。
- **一键 MD5 校验与防篡改**：在时间轴列表中支持单键快速比对本地镜像文件 MD5。
- **一键回滚刷回物理分区**：若新时序不稳定或黑屏，可通过回滚界面一键还原至任意历史备份，具备 5 秒安全倒计时与写后二次回读校验保障。
- **便捷导出与管理**：支持导出备份镜像至公共 `Download` 目录或自定义存储路径，支持独立删除过期备份。

### 8. 三层防砖安全保障体系（Triple Safety Guard）
针对物理刷写的安全性，应用设立了业内最严密的三层防御屏障：
1. **第一层：强制物理备份与双重哈希持久化**：
   - 刷写前强制使用 `dd` 读取物理分区原厂数据。
   - 内部缓存与外部公共存储双重持久化，并做 SHA-256 / MD5 哈希校验，任何校验失败均立刻中止刷写。
2. **第二层：离线 Recovery 救砖包预生成**：
   - 在开始物理写入前，自动将原厂备份封装为标准的 Recovery 卡刷救砖包（Rescue ZIP）并导出至外部存储。即使手机发生意外无法开机，也可进入第三方 Recovery（如 TWRP / OrangeFox）刷入该救砖包瞬时复原。
3. **第三层：严格白名单、单槽位物理隔离与写后自动回滚**：
   - **严格白名单**：仅允许写入 `/dev/block/by-name/dtbo`、`dtbo_a` 或 `dtbo_b`。
   - **单槽位隔离**：精准识别当前活动槽位，绝不修改对侧备用槽位。
   - **交互防误触**：刷写操作强制执行 5 秒倒计时，并要求手动输入语义确认词（如 `目标 Hz` 或全大写 `FLASH`）。
   - **写后回读与自动回滚**：物理写入后立刻从硬件分区回读前 N 个字节并比对 SHA-256。一旦发现不匹配，立刻触发自动回滚，将原厂备份写回分区。

### 9. 多格式离线部署包导出
- **修补后的 DTBO 镜像 (`.img`)**：适用于 Fastboot 手动刷入或镜像归档。
- **Recovery 卡刷包 (`.zip`)**：内含跨平台刷机脚本与安全校验，支持在第三方 Recovery 中直接刷入。
- **PC Fastboot 刷机工具包 (`.zip`)**：内含 Windows 批处理 (`flash-windows.bat`)、Linux Shell 脚本 (`flash-linux.sh`) 及说明文档，无需安装 Android 平台工具即可在电脑端一键刷写。
- **Recovery 救砖还原包 (`.zip`)**：物理写入前自动生成的原厂还原包。

### 10. 实时终端回显与系统诊断
- **终端回显面板**：实时滚动展示底层 DTC 编译、Codec 解析、分区块写入及 Hash 校验的详细输出。
- **一键复制与日志导出**：支持一键复制终端文本、导出完整应用诊断日志，或安全清空日志缓存。

---

## 🏗️ 源码结构

```text
app/src/main/java/io/mo/dtbooverclocker/
├── core/
│   ├── ActivePanelDetector.kt       # 活跃屏幕面板多级硬件识别与匹配
│   ├── BackupManager.kt             # 时间轴备份管理、JSON 清单、MD5 校验与回滚
│   ├── DtboImageCodec.kt            # 纯 Kotlin DTBO v0/v1/v2 编解码与压缩条目处理
│   ├── DtboPatchEngine.kt           # 工作区管理、DTS 分析、暂存修改与集中打包
│   ├── DtsTimingPatcher.kt          # DTS 时序语法树分析、重算策略、新增/删除节点
│   ├── NativeToolExecutor.kt        # JNI 原生 DTC 可执行文件调用封装
│   ├── RootDetector.kt              # Root 权限与 Shell 执行器
│   ├── SafetyGuardManager.kt        # 三层防砖机制、救砖包/Fastboot 包生成与安全直刷
│   └── SlotDetector.kt              # A/B 槽位与 DTBO 块设备路径探测
├── model/
│   ├── BackupModels.kt              # 备份记录与校验状态数据模型
│   └── Models.kt                    # 时序候选、工作区、打包报告与配置模型
├── ui/
│   ├── MainActivity.kt              # 主页面交互、连续暂存工作流与各功能入口
│   ├── MainViewModel.kt             # 核心业务状态机与异步调度
│   ├── RollbackScreen.kt            # 时间轴备份列表、MD5 校验与回滚交互界面
│   ├── SettingsScreen.kt            # 环境状态、存储清理、日志管理与安全设置
│   ├── AboutScreen.kt               # 应用信息、源码链接与开源声明
│   └── components/
│       ├── DisclaimerDialog.kt      # 免责声明与高危操作风险警示弹窗
│       ├── OverclockPreviewCard.kt  # 超频前后电气时序参数对比卡片
│       ├── TimingCandidateSelector.kt # 候选时序多维度选择与活跃面板高亮组件
│       ├── TimingGeometryChart.kt   # 屏幕时序消隐几何结构图谱绘制组件
│       └── TimingUtils.kt           # 时序节点命名与面板标识解析工具
└── util/
    ├── AppLogger.kt                 # 应用级环形日志缓冲与文件持久化
    ├── HashUtils.kt                 # MD5 与 SHA-256 算法工具
    └── StorageUtils.kt              # 存储大小格式化与文件系统操作工具
```

---

## ⚙️ 原生二进制工具说明

本应用无需 Python 运行环境或 `mkdtboimg`，仅需一个合规编译的 Android ARM64 原生可执行文件：

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

> **注意**：
> - 尽管文件采用 `.so` 后缀命名，但其本质是 **AArch64 ELF 可执行文件**（并非由 `System.loadLibrary()` 加载的动态共享库）。
> - 采用 `.so` 命名是为了利用 Android 系统的原生 JNI 打包机制，确保其在安装时被自动解压至不可写的只读目录 `applicationInfo.nativeLibraryDir`。
> - 应用**绝不会**将可执行代码复制到可写的 `cacheDir` 或外部存储中执行，完全符合 Android 10+ 的 W^X（Write XOR Execute）安全规范。
> - 详情请参阅 [arm64-v8a/README.md](app/src/main/jniLibs/arm64-v8a/README.md)。

---

## 🛠️ 构建环境与技术栈

- **开发语言**：Kotlin 2.4.20
- **UI 框架**：Jetpack Compose + Material 3 (Compose BOM 2026.08.00)
- **编译工具**：Android Gradle Plugin 9.4.0 / Gradle 9.6.0
- **SDK 要求**：
  - `compileSdk`：37
  - `targetSdk`：37
  - `minSdk`：26 (Android 8.0+)
- **JDK 版本**：JDK 17
- **目标架构**：`arm64-v8a`

### 本地编译命令

```bash
# 首次编译如需配置 Gradle Wrapper
gradle wrapper --gradle-version 9.6.0

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease
```

---

## 📖 使用指南

### 方式一：免 Root 模式（安全离线调校）
1. 从当前机型的官方 ROM、卡刷包或第三方备份中提取 `dtbo.img`。
2. 打开应用，在“镜像来源”中选择 **SAF 本地镜像导入**。
3. 应用自动调用纯 Kotlin Codec 解构 DTBO，并通过 DTC 导出 DTS 时序文本。
4. 浏览时序候选节点，选择目标面板与基准档位。
5. 设定目标刷新率，选择时序策略（推荐“平衡时序”），查看实时预览卡片与时序几何图谱。
6. 点击 **暂存时序修改**（可重复多次操作不同档位）。
7. 点击 **集中打包生成**，验证元数据与重编译结果。
8. 点击 **导出部署**：
   - 导出为 `.img` 后通过电脑 `fastboot flash dtbo dtbo_patched.img` 刷入。
   - 导出为 Recovery 单槽卡刷包在第三方 Recovery 中刷入。
   - 导出为 PC Fastboot 刷机工具包在电脑端双击执行刷写。

### 方式二：Root 模式（一键提取与安全直刷）
1. 设备需拥有 Root 权限（KernelSU / APatch / Magisk）。
2. 在“镜像来源”中选择 **从物理 DTBO 分区读取**（应用自动识别当前活跃 A/B 槽位）。
3. 应用会自动通过 Active Panel Detector 锁定当前物理屏幕面板，并高亮推荐时序。
4. 配置超频参数并暂存修改，点击集中打包。
5. 在部署选项中选择 **Root 直接刷入物理分区**：
   - 界面将启动 **5 秒安全倒计时**。
   - 强制生成原厂物理备份并做本地/外部 SHA-256 校验。
   - 强制生成 Recovery 离线救砖包。
   - 手动输入确认词后开始写入物理分区。
   - 写入完成后自动执行**写后回读校验**。
6. 重启手机即可享受全新高刷。

### 方式三：备份与紧急回滚
- **主界面右上角时钟图标** 或 **设置 -> 镜像备份与回滚管理** 可进入回滚时间轴。
- 支持查看历次自动与手动备份的设备环境、时间与哈希信息。
- 点击“校验 MD5”可确认文件未发生意外变动。
- 点击“回滚到此备份”，确认后将以安全机制写回物理分区并回读核验。

---

## ⚠️ 免责声明与高危风险提示

1. **底层硬件风险**：修改 DTBO 刷新率属于底层硬件超频操作。面板物理材质、驱动 IC、显示排线可能无法承受超频带来的电气载荷，存在发热异常、残影、烧屏、花屏甚至永久性硬件损坏的潜在风险。
2. **黑屏与启动失败风险**：若选用了不兼容的时序参数或错误修改了非点亮面板的默认时序，可能导致开机黑屏（无法显示画面）或系统无法完成 Display 驱动初始化而发生 Bootloop。
3. **安全自负**：使用者需充分理解相关风险，并具备使用 PC Fastboot 或第三方 Recovery 独立救砖的能力。对于因使用本软件造成的任何数据丢失、设备损坏或经济损失，开发者不承担任何直接或连带责任。

---

## 📄 开源协议

本项目基于 **[GNU General Public License v3.0 (GPLv3)](LICENSE)** 开源。
