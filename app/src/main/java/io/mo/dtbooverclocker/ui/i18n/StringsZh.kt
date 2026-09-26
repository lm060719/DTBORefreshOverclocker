package io.mo.dtbooverclocker.ui.i18n

object StringsZh : AppStrings {
    // General
    override val appName: String = "DTBO Studio"
    override val cancel: String = "取消"
    override val confirm: String = "确认"
    override val delete: String = "删除"
    override val clear: String = "清空"
    override val save: String = "保存"
    override val reset: String = "重置"
    override val copied: String = "已复制"
    override val copy: String = "复制"
    override val refresh: String = "刷新"
    override val back: String = "返回"
    override val backToHome: String = "返回主页"
    override val backToSettings: String = "返回设置"
    override val statusReady: String = "就绪"
    override val statusBusy: String = "处理中…"
    override val statusInitializing: String = "初始化中…"
    override val statusWaitingDisclaimer: String = "等待同意免责声明"
    override val warning: String = "警告"
    override val error: String = "错误"
    override val success: String = "成功"
    override val resetAll: String = "全部重置"
    override val close: String = "关闭"

    // Settings
    override val settingsTitle: String = "设置"
    override val settingsLanguage: String = "界面语言"
    override val settingsLanguageDesc: String = "切换应用界面显示语言。"
    override val langFollowSystem: String = "跟随系统"
    override val langEnglish: String = "英文"
    override val langChinese: String = "中文"
    override val appCache: String = "应用缓存"
    override val appCacheDesc: String = "包含导入的 DTBO 镜像缓存、反编译 DTS 临时工作区及刷写校验临时文件。"
    override val clearAllCache: String = "清除所有缓存"
    override val runtimeLogs: String = "运行日志"
    override fun logFilesStats(count: Int, size: String): String = "$count 个文件 · $size"
    override val exportFullLogs: String = "导出完整日志"
    override val clearLogs: String = "清空日志"
    override val confirmClearCacheTitle: String = "确认清空应用缓存？"
    override fun confirmClearCacheBody(size: String): String =
        "将清除当前应用内的所有导入镜像缓存与反编译工作目录（当前占用：$size）。\n\n若当前有正在编辑但尚未导出的 DTBO 工作区，清理后工作区将被重置。"
    override val clearAction: String = "清空"
    override fun cacheClearedFreed(freed: String): String = "已成功清空缓存，释放 $freed"
    override val cacheCleared: String = "缓存已清空"
    override val confirmClearLogsTitle: String = "确认清空所有运行日志？"
    override fun confirmClearLogsBody(count: Int, size: String): String =
        "将删除设备中保存的历史会话日志（当前：$count 个文件，共 $size）。\n\n清空后将自动开启新的空白会话。"
    override val logsCleared: String = "日志文件已清空"

    // Navigation & Tabs
    override val tabOverview: String = "概览"
    override val tabModules: String = "功能模块"
    override val tabDeviceTree: String = "设备树"
    override val tabSettings: String = "设置"
    override val backupAndRestore: String = "备份与恢复"
    override val refreshEnvironment: String = "刷新环境"
    override val refreshEnvironmentProbe: String = "刷新环境探测"
    override val reprobe: String = "重新探测"
    override val requestRoot: String = "请求 Root"
    override val envStatus: String = "环境状态"
    override val rootGrantedPill: String = "Root 已授权"
    override val rootNotGrantedPill: String = "未授权"
    override val currentSlot: String = "当前槽位"
    override val dtboPartition: String = "DTBO 分区"
    override val detectingPartition: String = "分区路径检测中"
    override fun backupCountSubtitle(count: Int): String = "已保存 $count 个 DTBO 备份"
    override val advancedSettings: String = "高级设置"
    override val advancedSettingsSubtitle: String = "语言、缓存、日志与维护选项"
    override val aboutStudio: String = "关于 DTBO Studio"
    override val aboutStudioSubtitle: String = "版本、项目说明与免责声明"

    // Workflow & Overview Cards
    override val workflowSubtitle: String = "导入、分析、编辑、验证并重新构建 DTBO。"
    override val rootGrantedStatus: String = "Root ✓"
    override val nonRootAvailable: String = "免 Root 可用"
    override val workspaceLoaded: String = "工作区已加载"
    override val waitingForImage: String = "等待镜像"
    override val stepImport: String = "导入"
    override val stepAnalyze: String = "识别"
    override val stepEdit: String = "修改"
    override val stepPackage: String = "打包"
    override val stepExport: String = "导出"
    override val imageSource: String = "镜像来源"
    override val manualImport: String = "手动导入"
    override val extractCurrentPartition: String = "提取当前分区"
    override val hintNoRootImport: String = "未检测到 Root 权限，可点击“手动导入”选择外部 dtbo.img 文件。"
    override fun hintRootExtract(device: String): String =
        "手动导入支持外部镜像（免 Root）；提取当前分区只读取 $device"
    override val imageParseResult: String = "镜像解析结果"
    override val dtbCount: String = "DTB"
    override val uniquePanels: String = "唯一面板"
    override val timingCandidates: String = "时序候选"
    override fun panelDtbInstances(count: Int): String = "面板 DTB 实例: $count"
    override fun vendorPanels(count: Int): String = "厂商面板: $count"
    override val avbNone: String = "AVB: 无"
    override val avbUnsigned: String = "AVB: 未签名"
    override fun avbSigned(algorithm: String?): String = "AVB: 已签名${if (algorithm != null) " $algorithm" else ""}"
    override fun panelInUse(displayName: String): String = "在用: $displayName"
    override fun panelDeduplicationHint(vendor: Int, ref: Int, sim: Int, unk: Int): String =
        "面板统计按唯一 panel identifier 去重；同一面板出现在多个 DTB entry 时只算 1 个唯一面板。当前分类：厂商 $vendor / 高通参考 $ref / 仿真 $sim / 未分类 $unk。"
    override val activePanelHint: String =
        "已通过设备运行信息优先标记当前在用面板；“厂商面板”只做正向识别，未知标识不会再自动算作机型专属。"
    override fun dtTransactions(size: Int): String = "设备树事务 · $size 个"
    override fun dtOperationsPending(count: Int): String = "$count 个底层操作待打包"
    override val packageBatch: String = "集中打包"
    override val undoLastTransaction: String = "撤销最近事务"
    override val output: String = "输出"
    override val export: String = "导出"
    override val savePatchedImg: String = "保存 dtbo_patched.img"
    override val exportRecoveryZip: String = "导出 Recovery 刷机 Zip"
    override val exportFastbootBundle: String = "导出 PC Fastboot 一键包"
    override val exportModuleZip: String = "导出 KernelSU / Magisk 模块"
    override val flashToDevice: String = "刷写到设备"
    override val directFlashPartition: String = "直接刷写当前槽位"
    override val makeModuleAndFlash: String = "制作成模块并刷入"
    override val moduleFlashHint: String =
        "模块方式通过 KernelSU / Magisk / APatch 安装，安装时写入当前槽位；在管理器中移除模块并重启即可自动恢复原 DTBO。"
    override val directFlashRequiresRoot: String = "直接刷写需要 Root 授权。"
    override val rescueMemo: String = "救砖备忘录"
    override val flashedPartition: String = "已刷写"
    override val backupSha256: String = "备份 SHA-256"
    override val backupLocation: String = "备份外部位置"
    override val rescueZipLocation: String = "Rescue Zip 外部位置"
    override val exportBackup: String = "导出备份"
    override val exportRescue: String = "导出救援包"
    override val saveScreenshotMemo: String = "截图保存备忘录"
    override val terminalEcho: String = "终端回显"
    override fun linesCount(lines: Int): String = "$lines 行"
    override val expand: String = "展开"
    override val collapse: String = "折叠"
    override val flash: String = "刷入"
    override val stage: String = "暂存"
    override val previousPage: String = "上一页"
    override val nextPage: String = "下一页"
    override val backupTypeAuto: String = "自动备份"
    override val backupTypeManual: String = "手动备份"
    override fun singleTransactionSummary(kind: String, opCount: Int, risk: String): String =
        "${kind}事务完成 · ${opCount} 个底层操作 · $risk"
    override fun multiTransactionSummary(txCount: Int, opCount: Int): String =
        "事务打包完成：$txCount 个事务 / $opCount 个底层操作"

    // Modules Tab
    override val modulesTitle: String = "功能模块"
    override val modulesSubtitle: String =
        "功能模块负责生成经过约束验证的设备树事务；能力扫描只负责发现，不会自动把检测结果变成写入。"
    override val noWorkspaceYet: String = "还没有工作区"
    override val noWorkspaceHint: String =
        "先到“概览”导入 dtbo.img，或在 Root 设备上提取当前 DTBO 分区。"
    override val capabilityScan: String = "设备树能力扫描"
    override val scanning: String = "扫描中…"
    override val scanCompleted: String = "已完成"
    override val waitingForScan: String = "等待扫描"
    override fun scanStats(dtb: Int, nodes: Int, props: Int): String =
        "$dtb 个 DTB · $nodes 个节点 · $props 个属性"
    override val scanHint: String = "导入 DTBO 后自动识别刷新率时序和 Charging 参数。"
    override val moduleRefreshRate: String = "刷新率"
    override val moduleCharging: String = "Charging"
    override val moduleAdvancedProps: String = "高级属性"
    override val moduleEditorAlwaysAvailable: String = "设备树编辑器 · 始终可用"
    override fun candidatesCount(count: Int): String = "$count 个候选"
    override fun statusAvailable(count: Int): String = "可用 · $count 个"
    override fun statusAnalysisOnly(count: Int): String = "可分析 · $count 个"
    override val statusNotFound: String = "当前 DTBO 未发现"

    // About Screen
    override val aboutTitle: String = "关于"
    override val appSubtitle: String = "Android DTBO / Device Tree 分析、编辑与安全重构工具"
    override val checkUpdate: String = "检测更新"
    override val openSourceRepo: String = "开源仓库"
    override val viewSource: String = "查看源码"
    override val noBrowserFound: String = "未找到可用浏览器"
    override val repoLinkCopied: String = "仓库链接已复制"
    override val coreArchTitle: String = "核心架构与安全"
    override val coreArchContent: String =
        "• 纯 Kotlin DTBO 编解码引擎：完整支持 v0/v1/v2 规范、自动校验元数据并保留压缩条目。\n" +
        "• 三层防砖保障：强制物理分区完整备份、离线 Recovery 救砖包预生成、写后回读 SHA-256 自动回滚。\n" +
        "• 单槽位物理隔离：严格仅操作当前活跃 A/B 槽位，杜绝双槽破坏。\n" +
        "• 多种时序调整策略：支持平衡消隐时间 (Blanking Time)、仅像素时钟、仅帧率等调校模式。"
    override val disclaimerCardTitle: String = "免责声明与风险须知"
    override val disclaimerCardBody: String =
        "本软件属于高危底层硬件调试工具。使用前请确保您已完整知悉屏幕黑屏、Bootloop 及硬件损耗风险，并具备独立救砖能力。"
    override val viewFullDisclaimer: String = "查看完整免责声明"
    override val licenseNotice: String =
        "本应用为开源工具，仅供设备所有者与系统开发者进行屏幕显示测试与超频研究。使用物理刷写功能存在一定风险，请务必保管好预生成的备份救砖文件。"

    // Timing Panel
    override val timingPanelTitle: String = "参数微调与超频推演"
    override val targetRefreshRate: String = "目标刷新率"
    override val calculationStrategy: String = "计算策略"
    override val patchMode: String = "刷写模式"
    override val customParams: String = "自定义参数"
    override val pixelClock: String = "像素时钟 (Pixel Clock)"
    override val verticalFrontPorch: String = "垂直前肩 (VFP)"
    override val verticalBackPorch: String = "垂直后肩 (VBP)"
    override val horizontalFrontPorch: String = "水平前肩 (HFP)"
    override val horizontalBackPorch: String = "水平后肩 (HBP)"
    override val stageTimingChange: String = "暂存时序修改"
    override val deleteCandidate: String = "删除此档位"
    override val confirmDeleteModeTitle: String = "确认删除该档位？"
    override val confirmDeleteModeBody: String =
        "将从设备树中彻底删除此显示时序节点。若删除后没有可用档位，系统将无法正确初始化屏幕驱动。"
    override val applySuggested: String = "应用建议参数"
    override val operationMode: String = "操作模式"
    override val deleteTimingCandidateTitle: String = "准备删除时序档位"
    override fun deleteNodeLabel(name: String, hz: Int): String = "待删除节点：$name ($hz Hz)"
    override fun fullNodePath(path: String): String = "完整节点路径：$path"
    override val deleteOnlyModeWarning: String =
        "严防黑屏限制：当前 DTB 镜像条目仅存此单一档位。屏幕面板必须保留至少 1 个时序档位以供显示驱动初始化，禁止删除！"
    override fun deleteModeRetainHint(count: Int): String =
        "删除后，当前 DTB 镜像条目仍保留 $count 个时序档位。若此档位为默认 native-mode 开机档位，系统将自动重定向至剩余档位。"
    override val deleteThisCandidateBtn: String = "删除此档位 (暂存)"
    override val autoDynamicModeUnsupported: String = "自动变频档位不支持直接超频"
    override val autoDynamicModeDesc1: String =
        "该档位包含自动变频或低功耗参数及专用屏幕命令。仅修改刷新率或复制为高刷档位，可能导致黑屏、刷新率切换异常或卡在开机画面。"
    override val autoDynamicModeDesc2: String =
        "请在上方选择同一面板的 normal 普通档位，再编辑或新增。例如新增 144 Hz，应选 normal_120hz，而不是 auto_120_to_30hz。"
    override val selectNormalModeToContinue: String = "请选择普通档位后继续"
    override val quickPresets: String = "快捷预设:"
    override val targetHzInputLabel: String = "目标刷新率数值 (Hz)"
    override val fillSuggestedCustom: String = "填入平衡参考值"
    override val pixelClockSupporting: String = "设备树像素/通道时钟，单位 Hz"
    override val advancedBlankingParams: String = "高级消隐参数 (HFP / HBP)"
    override fun theoreticalRefreshRate(hz: String, target: Int): String =
        "理论推算物理刷新率: $hz Hz (目标: $target Hz)"
    override val stageAppendNewMode: String = "追加为此面板新档位 (暂存)"
    override val stageApplyCurrentMode: String = "应用修改到当前时序 (暂存)"
    override val deleteCandidateConfirmTitle: String = "确认删除该时序档位？"
    override fun deleteCandidateConfirmBody(name: String, hz: Int): String =
        "将从工作区设备树中移除 $name ($hz Hz) 节点。\n删除后将记入待打包修改清单，全部调整完成后可统一打包生成 DTBO 镜像。"

    // Charging & Thermal
    override val chargingTitle: String = "Charging 参数编辑"
    override val chargingSubtitle: String = "Charging 参数"
    override val noChargingNodes: String =
        "当前 DTBO 未发现充电参数。相关配置可能位于基础 DTB、vendor_boot 或电源管理驱动中。"
    override val scanningCharging: String = "正在扫描充电参数…"
    override val showReadOnlyNodes: String = "显示只读节点"
    override fun hideReadOnlyDesc(count: Int): String = "默认隐藏 $count 个没有已验证编辑项的相关节点"
    override val stageChargingChange: String = "暂存 Charging 修改"
    override fun chargingParamsSummary(paramCount: Int, nodeCount: Int, pathCount: Int, dtbCount: Int): String =
        "${paramCount} 个可编辑参数 · ${nodeCount} 个可编辑节点 · ${pathCount} 个唯一路径 / ${dtbCount} 个 DTB 实例"
    override fun thermalTableSummary(title: String, levels: Int, cols: Int, unit: String): String =
        "$title · $levels 档 × $cols 通道（$unit）"
    override val thermalTableDesc: String =
        "第 1 档限流最宽松，档位越高温度越高、限流越严格。左右滑动查看全部通道。"
    override val linkedChannels: String = "多通道关联同步编辑"
    override val selectChargingNode: String = "选择充电节点"
    override val oplusConservativeNotice: String =
        "已启用 OPlus 保守绑定：仅开放已验证的单值 mA / mV 参数，复杂策略表保持只读。"
    override fun overlayTarget(target: String): String = "Overlay 目标：&$target"
    override fun editableParamsAndStatus(count: Int, status: String): String =
        "$count 个可编辑参数 · status: $status"
    override fun goToThermalTable(dtb: Int, node: String): String = "转到温控表：DTB $dtb · $node"
    override fun toggleNodeList(selecting: Boolean, count: Int): String =
        if (selecting) "收起节点列表" else "切换充电节点（$count）"
    override val nodeWithThermal: String = " · 含温控表"
    override fun nodeStatusWarning(status: String): String =
        "此节点 status 为 $status，修改参数不会自动启用节点。"
    override val readOnlyNodeNotice: String = "这是只读相关节点，没有经过验证的可编辑参数。"
    override val backToEditableNode: String = "返回可编辑节点"
    override fun stagedModificationsNotice(count: Int): String =
        "此节点已暂存 $count 次修改。到“概览”集中打包，或撤销最近事务。"
    override val noUnitParametersFound: String =
        "已发现相关节点，但没有可确认单位与格式的编辑参数。可在下方查看原始属性。"
    override val chargingParametersTitle: String = "充电参数"
    override val chargingParametersHint: String =
        "按参数标注的单位编辑，自动换算为设备树单位；只修改当前 DTB 的当前节点。"
    override val thermalTableRulesHint: String =
        "温控表中的数值是各温控档位的限流值，保持原有档位数量；同一通道的后一档不能高于前一档。"
    override val otherParameters: String = "其他参数"
    override val parameterGroups: String = "参数分组（可左右滑动）"
    override fun pendingStageCount(count: Int): String = " · $count 项待暂存"
    override fun pageAndItemsCount(page: Int, total: Int, items: Int): String =
        "${page} / $total 页 · ${items} 项"
    override val statusEnabled: String = "当前：开启"
    override val statusDisabled: String = "当前：关闭（属性未声明）"
    override fun currentValueWithRaw(cur: String, unit: String, raw: String, rawUnit: String): String =
        "当前：$cur $unit · 原始：$raw $rawUnit"
    override fun toggleReadOnlyFields(show: Boolean, count: Int): String =
        if (show) "收起只读 / 异常参数" else "查看只读 / 异常参数（$count）"
    override fun toggleOtherProps(show: Boolean, count: Int): String =
        if (show) "收起其他属性" else "查看其他原始属性（$count）"
    override val otherPropsPreserved: String = "以下属性未纳入充电参数编辑，按原样保留。"
    override val modificationPreview: String = "修改预览"
    override val noModificationsYet: String = "尚未修改参数"
    override val batterySpecsWarning: String =
        "请按电池和充电芯片规格设置电流、电压。参数合法不代表硬件支持；修改仅允许导出验证，暂存后到概览打包。"
    override val resetUnstagedInput: String = "重置未暂存输入"
    override val stageChargingChanges: String = "暂存充电修改"
    override val linkSameChannels: String = "联动相同通道"
    override fun linkSameChannelsDesc(channels: String): String =
        "$channels 原值完全相同，合并为一列同时修改"
    override val levelHeader: String = "档位"
    override fun columnsLinked(count: Int): String = "+$count 列联动"
    override val redBoxWarning: String =
        "红框：数值无效，或高于上一档。同一通道的后一档不能高于前一档。"
    override val restoreTableOriginal: String = "恢复本表原值"
    override fun toggleBatchAdjust(expanded: Boolean): String =
        if (expanded) "收起批量调整" else "批量调整（按百分比）"
    override val selectChannel: String = "选择通道"
    override fun andOtherColumns(name: String, count: Int): String = "$name 等 $count 列"
    override val fromLevel: String = "从第几档"
    override val toLevel: String = "到第几档"
    override val adjustPercent: String = "调整 %"
    override fun batchAdjustExample(unit: String): String =
        "例：+10 表示上调 10%，-20 表示下调 20%。结果按 10 $unit 取整；超出相邻档位时自动截断，保证后一档不高于前一档。"
    override val applyToTable: String = "应用到表格"
    override fun rawPrefix(orig: String): String = "原 $orig"

    // Rollback Screen
    override val rollbackTitle: String = "镜像回滚"
    override val rollbackSubtitle: String = "DTBO 分区备份时间轴与还原"
    override val manualBackup: String = "手动备份当前分区"
    override val refreshBackups: String = "刷新备份列表"
    override val noBackups: String = "暂无备份记录"
    override val noBackupsHint: String =
        "在进行任何物理刷写前，应用会自动创建带有完整哈希校验的物理备份；您也可以随时点击右上角手动备份。"
    override val manualBackupTitle: String = "创建手动物理备份"
    override val backupDescLabel: String = "备份描述 / 备注"
    override val backupDescPlaceholder: String = "例如：超频前官方原版备份"
    override val createBackup: String = "开始备份"
    override val verifyMd5: String = "校验完整性"
    override val restoreThisBackup: String = "还原此备份"
    override val flashThisBackupTitle: String = "确认刷入并还原该备份？"
    override fun flashThisBackupBody(partition: String, file: String): String =
        "将把备份文件 $file 直接写入物理分区 $partition。\n\n请确保备份镜像与当前设备及槽位完全匹配！"
    override val confirmRestore: String = "确认还原"
    override val deleteBackupTitle: String = "确认删除该备份记录？"
    override fun deleteBackupBody(file: String): String =
        "将从本地存储彻底删除备份镜像 $file 及关联元数据。此操作不可恢复！"
    override val backupRepo: String = "备份镜像库"
    override fun currentSlotSubtitle(slot: String, dev: String): String = "当前槽位: $slot ($dev)"
    override fun totalBackupsCount(count: Int): String = "共 $count 个备份"
    override val manualBackupCurrentPartition: String = "手动备份当前手机 DTBO 镜像"
    override fun manualBackupDialogBody(device: String): String =
        "将通过 Root 读取当前活跃分区 ($device) 并保存为回滚镜像。"
    override val backupNow: String = "立即备份"
    override val confirmRollbackFlashWarning: String =
        "您即将把选定的备份镜像物理写入设备分区，此操作将覆盖当前的 DTBO 分区！"
    override fun rollbackTargetPartition(dev: String): String = "• 目标分区：$dev"
    override fun rollbackBackupFile(file: String): String = "• 备份文件：$file"
    override fun rollbackBackupTime(time: String): String = "• 备份时间：$time"
    override fun rollbackAndroidVersion(ver: String): String = "• 备份系统：$ver"
    override fun rollbackBuildDisplay(disp: String): String = "• 系统版本：$disp"
    override fun rollbackRecordedMd5(md5: String): String = "• 记录 MD5：$md5"
    override val rollbackVerifyNotice: String =
        "写入后系统将自动进行写后回读 MD5 校验以确保完整性。请确保电量充足，刷写过程中请勿断电或重启手机。"
    override val confirmRollbackFlashBtn: String = "确认回滚刷入"
    override val infoRowAndroidVersion: String = "系统版本"
    override val infoRowBuildDisplay: String = "系统固件"
    override val infoRowDeviceModel: String = "设备机型"
    override val infoRowBackupSlot: String = "备份槽位"
    override val infoRowFileSize: String = "文件大小"
    override val md5Copied: String = "MD5 已复制到剪贴板"
    override val md5Unchecked: String = "未校验完整性"
    override val md5Verifying: String = "正在校验 MD5…"
    override val md5Matched: String = "MD5 校验通过 (一致)"
    override val md5Mismatch: String = "MD5 不一致"
    override val md5FileMissing: String = "备份镜像文件已丢失"
    override val emptyBackupsBtn: String = "立即手动备份当前镜像"

    // Device Tree Editor
    override val deviceTreeTitle: String = "设备树"
    override val searchNodes: String = "搜索节点或属性…"
    override val addNode: String = "新建子节点"
    override val addProperty: String = "新建属性"
    override val editProperty: String = "编辑属性"
    override val deleteNode: String = "删除节点"
    override val deleteProperty: String = "删除属性"
    override val cloneNode: String = "克隆节点"
    override val renameNode: String = "重命名节点"
    override val nodeName: String = "节点名称"
    override val propertyName: String = "属性名称"
    override val propertyValue: String = "属性值"
    override val propertyType: String = "数据类型"
    override val emptyDeviceTree: String = "未加载设备树"
    override val addChildNode: String = "新增子节点"
    override val cloneNodeWarning: String =
        "克隆会复制整个节点子树。当前阶段包含 label、phandle 或 linux,phandle 的子树会被安全阻止，避免重复节点身份。"
    override val nodeNamePlaceholder: String = "支持 unit-address，例如 timing@3、panel@ae94000"
    override val stageChanges: String = "暂存修改"
    override val sameNodeNameError: String = "新节点名与原节点名相同"
    override val duplicateChildNodeError: String = "已存在同名子节点"
    override val searchScopeAll: String = "全部"
    override val searchScopeNode: String = "节点"
    override val searchScopeProperty: String = "属性"
    override val searchScopeValue: String = "值"
    override val searchScopeReference: String = "引用"
    override val searchScopeModified: String = "已修改"

    // Dangerous Flash Dialog
    override val flashDangerousTitle: String = "高危操作：写入物理 DTBO 分区"
    override fun flashTarget(partition: String): String = "目标：$partition"
    override val flashWarningBody: String =
        "本应用只写当前目标槽位。写入前会强制备份、SHA-256 校验并生成 Rescue Zip。"
    override val flashViaModuleNotice: String =
        "将打包为模块并交给 KernelSU / Magisk / APatch 安装，由模块完成写入；移除模块并重启会自动写回原 DTBO。"
    override fun flashConfirmPrompt(targetHz: Int): String =
        "请输入目标刷新率 $targetHz，或输入大写 FLASH："
    override fun flashButtonCountdown(seconds: Int): String =
        "确认按钮将在 $seconds 秒后解锁"
    override val confirmModuleFlash: String = "确认以模块刷入"
    override val confirmSlotFlash: String = "确认单槽位刷写"
    override val windowSizeInvalid: String = "当前窗口尺寸无效"
    override fun screenshotFailed(code: Int): String = "截图失败：PixelCopy=$code"
    override val screenshotSaved: String = "截图已保存"
    override val screenshotWriteFailed: String = "截图写入失败"

    // Disclaimer Dialog
    override val disclaimerTitle: String = "风险提示与使用须知"
    override val disclaimerWelcome: String =
        "欢迎使用 DTBO Refresh Overclocker。在继续使用并授予 Root 权限前，请务必仔细阅读以下内容："
    override val disclaimerSec1Title: String = "1. 高危操作声明"
    override val disclaimerSec1Content: String =
        "本工具属于 Android 底层硬件调试与调校工具。使用本软件将会请求 Root 超级用户权限，并直接对设备的底层物理分区（dtbo）执行解包、修改并重写内核设备树（Device Tree Blob）操作。"
    override val disclaimerSec2Title: String = "2. 潜在严重风险"
    override val disclaimerSec2Content: String =
        "屏幕刷新率超频受限于您的屏幕面板品质与显示驱动 IC（DDIC）硬件体质。任何不当的时序或频率参数可能导致：\n\n" +
        "• 屏幕黑屏 / 花屏：开机后屏幕无法点亮或严重偏色、残影；\n" +
        "• 系统无法启动（Bootloop）：内核加载异常导致卡开机 LOGO 或反复重启；\n" +
        "• 硬件潜在损耗：长期超出标称频率运行可能导致发热加剧、器件加速老化或不可逆的物理损坏。"
    override val disclaimerSec3Title: String = "3. 使用前提条件"
    override val disclaimerSec3Content: String = "若要使用本软件，您必须拥有救砖的能力。"
    override val disclaimerSec4Title: String = "4. 免责条款"
    override val disclaimerSec4Content: String =
        "本软件仅供设备所有者用于个人学习、显示技术研究与性能测试。开发者已尽可能提供单槽保护与校验机制，但无法担保本软件在所有设备、内核及系统版本下的兼容性与安全性。因使用本软件导致的任何设备损坏、数据丢失、保修失效或硬件故障，均由使用者自行承担全部责任。"
    override val disclaimerAgreeCheckbox: String =
        "我已完整阅读并充分理解上述风险，确认具备独立救砖能力并自愿承担全部后果。"
    override fun disclaimerAgreeBtnCountdown(seconds: Int): String = "同意并继续 (${seconds}s)"
    override val disclaimerAgreeBtn: String = "同意并继续"
    override val disclaimerExitApp: String = "退出应用"
    override val disclaimerUnderstood: String = "我知道了"

    // Patch Strategies & Modes
    override val strategyBalancedName: String = "平衡时序"
    override val strategyBalancedDesc: String =
        "普通时序调整时钟与垂直前后肩；含 MDP 传输预算的命令模式保留前后肩，同步调整时钟与传输时间。"
    override val strategyPixelClockName: String = "仅 Pixel Clock"
    override val strategyPixelClockDesc: String =
        "保持 porch 不变，按刷新率比例调整时钟，并同步缩放已有的 MDP 传输时间。"
    override val strategyFramerateName: String = "仅 Framerate"
    override val strategyFramerateDesc: String =
        "只修改刷新率属性。兼容性最高但风险也最高，不建议用于直接刷写。"
    override val strategyCustomName: String = "自定义参数"
    override val strategyCustomDesc: String =
        "手动指定 Pixel Clock、垂直前肩 (VFP)、垂直后肩 (VBP) 及水平消隐等时序参数。"

    override val patchModeOverwriteName: String = "编辑修改档位"
    override val patchModeOverwriteDesc: String =
        "将选中的原始时序档位直接超频为目标刷新率（替换原档位）。"
    override val patchModeAppendName: String = "新增独立档位"
    override val patchModeAppendDesc: String =
        "完整保留原有时序档位，以此档位为蓝本克隆并追加全新的刷新率节点。"
    override val patchModeDeleteName: String = "删除指定档位"
    override val patchModeDeleteDesc: String =
        "从设备树中彻底移除所选的时序档位节点（需保留至少一个档位以供显示驱动初始化）。"

    // Capabilities
    override val capabilityRefreshRate: String = "刷新率"
    override val capabilityCharging: String = "Charging"
    override val capabilityAvailable: String = "可用"
    override val capabilityAnalysisOnly: String = "可分析"
    override val capabilityNotFound: String = "未发现"
}
