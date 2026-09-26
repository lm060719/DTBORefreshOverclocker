package io.mo.dtbooverclocker.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.mo.dtbooverclocker.model.AppLanguage
import io.mo.dtbooverclocker.util.LocaleHelper

interface AppStrings {
    // General
    val appName: String
    val cancel: String
    val confirm: String
    val delete: String
    val clear: String
    val save: String
    val reset: String
    val copied: String
    val copy: String
    val refresh: String
    val back: String
    val backToHome: String
    val backToSettings: String
    val statusReady: String
    val statusBusy: String
    val statusInitializing: String
    val statusWaitingDisclaimer: String
    val warning: String
    val error: String
    val success: String
    val resetAll: String
    val close: String

    // Settings
    val settingsTitle: String
    val settingsLanguage: String
    val settingsLanguageDesc: String
    val langFollowSystem: String
    val langEnglish: String
    val langChinese: String
    val appCache: String
    val appCacheDesc: String
    val clearAllCache: String
    val runtimeLogs: String
    fun logFilesStats(count: Int, size: String): String
    val exportFullLogs: String
    val clearLogs: String
    val confirmClearCacheTitle: String
    fun confirmClearCacheBody(size: String): String
    val clearAction: String
    fun cacheClearedFreed(freed: String): String
    val cacheCleared: String
    val confirmClearLogsTitle: String
    fun confirmClearLogsBody(count: Int, size: String): String
    val logsCleared: String

    // Navigation & Tabs
    val tabOverview: String
    val tabModules: String
    val tabDeviceTree: String
    val tabSettings: String
    val backupAndRestore: String
    val refreshEnvironment: String
    val refreshEnvironmentProbe: String
    val reprobe: String
    val requestRoot: String
    val envStatus: String
    val rootGrantedPill: String
    val rootNotGrantedPill: String
    val currentSlot: String
    val dtboPartition: String
    val detectingPartition: String
    fun backupCountSubtitle(count: Int): String
    val advancedSettings: String
    val advancedSettingsSubtitle: String
    val aboutStudio: String
    val aboutStudioSubtitle: String

    // Workflow & Overview Cards
    val workflowSubtitle: String
    val rootGrantedStatus: String
    val nonRootAvailable: String
    val workspaceLoaded: String
    val waitingForImage: String
    val stepImport: String
    val stepAnalyze: String
    val stepEdit: String
    val stepPackage: String
    val stepExport: String
    val imageSource: String
    val manualImport: String
    val extractCurrentPartition: String
    val hintNoRootImport: String
    fun hintRootExtract(device: String): String
    val imageParseResult: String
    val dtbCount: String
    val uniquePanels: String
    val timingCandidates: String
    fun panelDtbInstances(count: Int): String
    fun vendorPanels(count: Int): String
    val avbNone: String
    val avbUnsigned: String
    fun avbSigned(algorithm: String?): String
    fun panelInUse(displayName: String): String
    fun panelDeduplicationHint(vendor: Int, ref: Int, sim: Int, unk: Int): String
    val activePanelHint: String
    fun dtTransactions(size: Int): String
    fun dtOperationsPending(count: Int): String
    val packageBatch: String
    val undoLastTransaction: String
    val output: String
    val export: String
    val savePatchedImg: String
    val exportRecoveryZip: String
    val exportFastbootBundle: String
    val exportModuleZip: String
    val flashToDevice: String
    val directFlashPartition: String
    val makeModuleAndFlash: String
    val moduleFlashHint: String
    val directFlashRequiresRoot: String
    val rescueMemo: String
    val flashedPartition: String
    val backupSha256: String
    val backupLocation: String
    val rescueZipLocation: String
    val exportBackup: String
    val exportRescue: String
    val saveScreenshotMemo: String
    val terminalEcho: String
    fun linesCount(lines: Int): String
    val expand: String
    val collapse: String
    val flash: String
    val stage: String
    val previousPage: String
    val nextPage: String
    val backupTypeAuto: String
    val backupTypeManual: String
    fun singleTransactionSummary(kind: String, opCount: Int, risk: String): String
    fun multiTransactionSummary(txCount: Int, opCount: Int): String

    // Modules Tab
    val modulesTitle: String
    val modulesSubtitle: String
    val noWorkspaceYet: String
    val noWorkspaceHint: String
    val capabilityScan: String
    val scanning: String
    val scanCompleted: String
    val waitingForScan: String
    fun scanStats(dtb: Int, nodes: Int, props: Int): String
    val scanHint: String
    val moduleRefreshRate: String
    val moduleCharging: String
    val moduleAdvancedProps: String
    val moduleEditorAlwaysAvailable: String
    fun candidatesCount(count: Int): String
    fun statusAvailable(count: Int): String
    fun statusAnalysisOnly(count: Int): String
    val statusNotFound: String

    // About Screen
    val aboutTitle: String
    val appSubtitle: String
    val checkUpdate: String
    val openSourceRepo: String
    val viewSource: String
    val noBrowserFound: String
    val repoLinkCopied: String
    val coreArchTitle: String
    val coreArchContent: String
    val disclaimerCardTitle: String
    val disclaimerCardBody: String
    val viewFullDisclaimer: String
    val licenseNotice: String

    // Timing Panel
    val timingPanelTitle: String
    val targetRefreshRate: String
    val calculationStrategy: String
    val patchMode: String
    val customParams: String
    val pixelClock: String
    val verticalFrontPorch: String
    val verticalBackPorch: String
    val horizontalFrontPorch: String
    val horizontalBackPorch: String
    val stageTimingChange: String
    val deleteCandidate: String
    val confirmDeleteModeTitle: String
    val confirmDeleteModeBody: String
    val applySuggested: String
    val operationMode: String
    val deleteTimingCandidateTitle: String
    fun deleteNodeLabel(name: String, hz: Int): String
    fun fullNodePath(path: String): String
    val deleteOnlyModeWarning: String
    fun deleteModeRetainHint(count: Int): String
    val deleteThisCandidateBtn: String
    val autoDynamicModeUnsupported: String
    val autoDynamicModeDesc1: String
    val autoDynamicModeDesc2: String
    val selectNormalModeToContinue: String
    val quickPresets: String
    val targetHzInputLabel: String
    val fillSuggestedCustom: String
    val pixelClockSupporting: String
    val advancedBlankingParams: String
    fun theoreticalRefreshRate(hz: String, target: Int): String
    val stageAppendNewMode: String
    val stageApplyCurrentMode: String
    val deleteCandidateConfirmTitle: String
    fun deleteCandidateConfirmBody(name: String, hz: Int): String

    // Charging & Thermal
    val chargingTitle: String
    val chargingSubtitle: String
    val noChargingNodes: String
    val scanningCharging: String
    val showReadOnlyNodes: String
    fun hideReadOnlyDesc(count: Int): String
    val stageChargingChange: String
    fun chargingParamsSummary(paramCount: Int, nodeCount: Int, pathCount: Int, dtbCount: Int): String
    fun thermalTableSummary(title: String, levels: Int, cols: Int, unit: String): String
    val thermalTableDesc: String
    val linkedChannels: String
    val selectChargingNode: String
    val oplusConservativeNotice: String
    fun overlayTarget(target: String): String
    fun editableParamsAndStatus(count: Int, status: String): String
    fun goToThermalTable(dtb: Int, node: String): String
    fun toggleNodeList(selecting: Boolean, count: Int): String
    val nodeWithThermal: String
    fun nodeStatusWarning(status: String): String
    val readOnlyNodeNotice: String
    val backToEditableNode: String
    fun stagedModificationsNotice(count: Int): String
    val noUnitParametersFound: String
    val chargingParametersTitle: String
    val chargingParametersHint: String
    val thermalTableRulesHint: String
    val otherParameters: String
    val parameterGroups: String
    fun pendingStageCount(count: Int): String
    fun pageAndItemsCount(page: Int, total: Int, items: Int): String
    val statusEnabled: String
    val statusDisabled: String
    fun currentValueWithRaw(cur: String, unit: String, raw: String, rawUnit: String): String
    fun toggleReadOnlyFields(show: Boolean, count: Int): String
    fun toggleOtherProps(show: Boolean, count: Int): String
    val otherPropsPreserved: String
    val modificationPreview: String
    val noModificationsYet: String
    val batterySpecsWarning: String
    val resetUnstagedInput: String
    val stageChargingChanges: String
    val linkSameChannels: String
    fun linkSameChannelsDesc(channels: String): String
    val levelHeader: String
    fun columnsLinked(count: Int): String
    val redBoxWarning: String
    val restoreTableOriginal: String
    fun toggleBatchAdjust(expanded: Boolean): String
    val selectChannel: String
    fun andOtherColumns(name: String, count: Int): String
    val fromLevel: String
    val toLevel: String
    val adjustPercent: String
    fun batchAdjustExample(unit: String): String
    val applyToTable: String
    fun rawPrefix(orig: String): String

    // Rollback Screen
    val rollbackTitle: String
    val rollbackSubtitle: String
    val manualBackup: String
    val refreshBackups: String
    val noBackups: String
    val noBackupsHint: String
    val manualBackupTitle: String
    val backupDescLabel: String
    val backupDescPlaceholder: String
    val createBackup: String
    val verifyMd5: String
    val restoreThisBackup: String
    val flashThisBackupTitle: String
    fun flashThisBackupBody(partition: String, file: String): String
    val confirmRestore: String
    val deleteBackupTitle: String
    fun deleteBackupBody(file: String): String
    val backupRepo: String
    fun currentSlotSubtitle(slot: String, dev: String): String
    fun totalBackupsCount(count: Int): String
    val manualBackupCurrentPartition: String
    fun manualBackupDialogBody(device: String): String
    val backupNow: String
    val confirmRollbackFlashWarning: String
    fun rollbackTargetPartition(dev: String): String
    fun rollbackBackupFile(file: String): String
    fun rollbackBackupTime(time: String): String
    fun rollbackAndroidVersion(ver: String): String
    fun rollbackBuildDisplay(disp: String): String
    fun rollbackRecordedMd5(md5: String): String
    val rollbackVerifyNotice: String
    val confirmRollbackFlashBtn: String
    val infoRowAndroidVersion: String
    val infoRowBuildDisplay: String
    val infoRowDeviceModel: String
    val infoRowBackupSlot: String
    val infoRowFileSize: String
    val md5Copied: String
    val md5Unchecked: String
    val md5Verifying: String
    val md5Matched: String
    val md5Mismatch: String
    val md5FileMissing: String
    val emptyBackupsBtn: String

    // Device Tree Editor
    val deviceTreeTitle: String
    val searchNodes: String
    val addNode: String
    val addProperty: String
    val editProperty: String
    val deleteNode: String
    val deleteProperty: String
    val cloneNode: String
    val renameNode: String
    val nodeName: String
    val propertyName: String
    val propertyValue: String
    val propertyType: String
    val emptyDeviceTree: String
    val addChildNode: String
    val cloneNodeWarning: String
    val nodeNamePlaceholder: String
    val stageChanges: String
    val sameNodeNameError: String
    val duplicateChildNodeError: String
    val searchScopeAll: String
    val searchScopeNode: String
    val searchScopeProperty: String
    val searchScopeValue: String
    val searchScopeReference: String
    val searchScopeModified: String

    // Dangerous Flash Dialog
    val flashDangerousTitle: String
    fun flashTarget(partition: String): String
    val flashWarningBody: String
    val flashViaModuleNotice: String
    fun flashConfirmPrompt(targetHz: Int): String
    fun flashButtonCountdown(seconds: Int): String
    val confirmModuleFlash: String
    val confirmSlotFlash: String
    val windowSizeInvalid: String
    fun screenshotFailed(code: Int): String
    val screenshotSaved: String
    val screenshotWriteFailed: String

    // Disclaimer Dialog
    val disclaimerTitle: String
    val disclaimerWelcome: String
    val disclaimerSec1Title: String
    val disclaimerSec1Content: String
    val disclaimerSec2Title: String
    val disclaimerSec2Content: String
    val disclaimerSec3Title: String
    val disclaimerSec3Content: String
    val disclaimerSec4Title: String
    val disclaimerSec4Content: String
    val disclaimerAgreeCheckbox: String
    fun disclaimerAgreeBtnCountdown(seconds: Int): String
    val disclaimerAgreeBtn: String
    val disclaimerExitApp: String
    val disclaimerUnderstood: String

    // Patch Strategies & Modes
    val strategyBalancedName: String
    val strategyBalancedDesc: String
    val strategyPixelClockName: String
    val strategyPixelClockDesc: String
    val strategyFramerateName: String
    val strategyFramerateDesc: String
    val strategyCustomName: String
    val strategyCustomDesc: String

    val patchModeOverwriteName: String
    val patchModeOverwriteDesc: String
    val patchModeAppendName: String
    val patchModeAppendDesc: String
    val patchModeDeleteName: String
    val patchModeDeleteDesc: String

    // Capabilities
    val capabilityRefreshRate: String
    val capabilityCharging: String
    val capabilityAvailable: String
    val capabilityAnalysisOnly: String
    val capabilityNotFound: String
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { StringsZh }

object I18n {
    val current: AppStrings
        @Composable
        @ReadOnlyComposable
        get() = LocalStrings.current

    fun getStrings(language: AppLanguage): AppStrings {
        return when (language) {
            AppLanguage.ENGLISH -> StringsEn
            AppLanguage.CHINESE -> StringsZh
            AppLanguage.FOLLOW_SYSTEM -> {
                val locale = LocaleHelper.getEffectiveLocale(AppLanguage.FOLLOW_SYSTEM)
                if (locale.language.equals("zh", ignoreCase = true)) StringsZh else StringsEn
            }
        }
    }
}
