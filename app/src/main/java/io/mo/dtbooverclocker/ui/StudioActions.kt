package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.model.ChargingNode
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import java.io.File

/** 环境与页面跳转。 */
class NavigationActions(
    val onRefreshEnvironment: () -> Unit,
    val onRequestRoot: () -> Unit,
    val onOpenRollback: () -> Unit,
    val onOpenAdvancedSettings: () -> Unit,
    val onOpenAbout: () -> Unit
)

/** 镜像导入与事务队列。 */
class WorkspaceActions(
    val onImport: () -> Unit,
    val onExtract: () -> Unit,
    val onPackage: () -> Unit,
    val onReset: () -> Unit,
    val onUndoLastTransaction: () -> Unit,
    val onUndoThroughTransaction: (String) -> Unit
)

/** 刷新率时序模块。 */
class TimingActions(
    val onSelect: (String) -> Unit,
    val onTarget: (Int) -> Unit,
    val onStrategy: (PatchStrategy) -> Unit,
    val onPatchMode: (PatchMode) -> Unit,
    val onCustomPixelClock: (String) -> Unit,
    val onCustomVfp: (String) -> Unit,
    val onCustomVbp: (String) -> Unit,
    val onCustomHfp: (String) -> Unit,
    val onCustomHbp: (String) -> Unit,
    val onApplySuggestedCustom: () -> Unit,
    val onStageChange: () -> Unit,
    val onStageCharging: (ChargingNode, Map<String, String>) -> Unit
)

/** 通用设备树编辑。 */
class DeviceTreeActions(
    val onSetProperty: (Int, String, String, String?) -> Unit,
    val onAddProperty: (Int, String, String, String?) -> Unit,
    val onDeleteProperty: (Int, String, String) -> Unit,
    val onAddNode: (Int, String, String) -> Unit,
    val onCloneNode: (Int, String, String) -> Unit,
    val onRenameNode: (Int, String, String) -> Unit,
    val onDeleteNode: (Int, String) -> Unit
)

/** 打包输出、刷写与救砖。 */
class OutputActions(
    val onSavePatched: (File) -> Unit,
    val onRecoveryZip: () -> Unit,
    val onFastbootBundle: () -> Unit,
    val onModuleZip: () -> Unit,
    val onFlash: () -> Unit,
    val onFlashModule: () -> Unit,
    val onExportBackup: (File) -> Unit,
    val onExportRescue: (File) -> Unit,
    val onScreenshot: () -> Unit,
    val onCopy: (String) -> Unit,
    val onClearLogs: () -> Unit
)
