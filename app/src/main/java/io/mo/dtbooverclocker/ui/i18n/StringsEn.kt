package io.mo.dtbooverclocker.ui.i18n

object StringsEn : AppStrings {
    // General
    override val appName: String = "DTBO Studio"
    override val cancel: String = "Cancel"
    override val confirm: String = "Confirm"
    override val delete: String = "Delete"
    override val clear: String = "Clear"
    override val save: String = "Save"
    override val reset: String = "Reset"
    override val copied: String = "Copied"
    override val copy: String = "Copy"
    override val refresh: String = "Refresh"
    override val back: String = "Back"
    override val backToHome: String = "Back to Home"
    override val backToSettings: String = "Back to Settings"
    override val statusReady: String = "Ready"
    override val statusBusy: String = "Processing…"
    override val statusInitializing: String = "Initializing…"
    override val statusWaitingDisclaimer: String = "Awaiting disclaimer acceptance"
    override val warning: String = "Warning"
    override val error: String = "Error"
    override val success: String = "Success"
    override val resetAll: String = "Reset All"
    override val close: String = "Close"

    // Settings
    override val settingsTitle: String = "Settings"
    override val settingsLanguage: String = "Language"
    override val settingsLanguageDesc: String = "Switch application display language."
    override val langFollowSystem: String = "Follow System"
    override val langEnglish: String = "English"
    override val langChinese: String = "Chinese"
    override val appCache: String = "App Cache"
    override val appCacheDesc: String =
        "Contains imported DTBO image caches, decompiled DTS workspaces, and flash verification temporary files."
    override val clearAllCache: String = "Clear All Cache"
    override val runtimeLogs: String = "Runtime Logs"
    override fun logFilesStats(count: Int, size: String): String = "$count files · $size"
    override val exportFullLogs: String = "Export Full Logs"
    override val clearLogs: String = "Clear Logs"
    override val confirmClearCacheTitle: String = "Clear App Cache?"
    override fun confirmClearCacheBody(size: String): String =
        "This will clear all imported image caches and decompiled DTS workspaces (current size: $size).\n\nIf you have a DTBO workspace currently being edited but not exported, it will be reset."
    override val clearAction: String = "Clear"
    override fun cacheClearedFreed(freed: String): String = "Cache cleared successfully, freed $freed"
    override val cacheCleared: String = "Cache cleared"
    override val confirmClearLogsTitle: String = "Clear All Runtime Logs?"
    override fun confirmClearLogsBody(count: Int, size: String): String =
        "This will delete all saved historical session logs ($count files, $size total).\n\nAfter clearing, a new clean session will begin."
    override val logsCleared: String = "Log files cleared"

    // Navigation & Tabs
    override val tabOverview: String = "Overview"
    override val tabModules: String = "Modules"
    override val tabDeviceTree: String = "Device Tree"
    override val tabSettings: String = "Settings"
    override val backupAndRestore: String = "Backup & Restore"
    override val refreshEnvironment: String = "Refresh Env"
    override val refreshEnvironmentProbe: String = "Refresh Environment Probe"
    override val reprobe: String = "Reprobe"
    override val requestRoot: String = "Request Root"
    override val envStatus: String = "Environment Status"
    override val rootGrantedPill: String = "Root Granted"
    override val rootNotGrantedPill: String = "Not Granted"
    override val currentSlot: String = "Current Slot"
    override val dtboPartition: String = "DTBO Partition"
    override val detectingPartition: String = "Detecting partition path…"
    override fun backupCountSubtitle(count: Int): String = "$count DTBO backups saved"
    override val advancedSettings: String = "Advanced Settings"
    override val advancedSettingsSubtitle: String = "Language, cache, logs, and maintenance"
    override val aboutStudio: String = "About DTBO Studio"
    override val aboutStudioSubtitle: String = "Version, project info, and disclaimer"

    // Workflow & Overview Cards
    override val workflowSubtitle: String = "Import, analyze, edit, verify, and rebuild DTBO."
    override val rootGrantedStatus: String = "Root ✓"
    override val nonRootAvailable: String = "Non-Root Available"
    override val workspaceLoaded: String = "Workspace Loaded"
    override val waitingForImage: String = "Waiting for Image"
    override val stepImport: String = "Import"
    override val stepAnalyze: String = "Analyze"
    override val stepEdit: String = "Edit"
    override val stepPackage: String = "Package"
    override val stepExport: String = "Export"
    override val imageSource: String = "Image Source"
    override val manualImport: String = "Manual Import"
    override val extractCurrentPartition: String = "Extract Partition"
    override val hintNoRootImport: String =
        "Root permission not detected. Tap \"Manual Import\" to select an external dtbo.img file."
    override fun hintRootExtract(device: String): String =
        "Manual import supports external images (no root required); extracting partition only reads $device"
    override val imageParseResult: String = "Image Analysis Result"
    override val dtbCount: String = "DTB"
    override val uniquePanels: String = "Unique Panels"
    override val timingCandidates: String = "Timing Candidates"
    override fun panelDtbInstances(count: Int): String = "Panel DTB instances: $count"
    override fun vendorPanels(count: Int): String = "Vendor panels: $count"
    override val avbNone: String = "AVB: None"
    override val avbUnsigned: String = "AVB: Unsigned"
    override fun avbSigned(algorithm: String?): String = "AVB: Signed${if (algorithm != null) " $algorithm" else ""}"
    override fun panelInUse(displayName: String): String = "Active: $displayName"
    override fun panelDeduplicationHint(vendor: Int, ref: Int, sim: Int, unk: Int): String =
        "Panel stats deduplicated by unique identifier; same panel across multiple DTB entries counts as 1. Breakdown: Vendor $vendor / QCOM Ref $ref / Sim $sim / Unknown $unk."
    override val activePanelHint: String =
        "Active panel detected from runtime device info; vendor classification uses positive match only."
    override fun dtTransactions(size: Int): String = "Device Tree Transactions · $size"
    override fun dtOperationsPending(count: Int): String = "$count operations pending packaging"
    override val packageBatch: String = "Package All"
    override val undoLastTransaction: String = "Undo Last"
    override val output: String = "Output"
    override val export: String = "Export"
    override val savePatchedImg: String = "Save dtbo_patched.img"
    override val exportRecoveryZip: String = "Export Recovery Flashable Zip"
    override val exportFastbootBundle: String = "Export PC Fastboot Bundle"
    override val exportModuleZip: String = "Export KernelSU / Magisk Module"
    override val flashToDevice: String = "Flash to Device"
    override val directFlashPartition: String = "Flash Directly to Slot"
    override val makeModuleAndFlash: String = "Flash via Magisk/KSU Module"
    override val moduleFlashHint: String =
        "Module method installs via KernelSU / Magisk / APatch, flashing on install; uninstalling module in manager restores original DTBO on reboot."
    override val directFlashRequiresRoot: String = "Direct flashing requires Root authorization."
    override val rescueMemo: String = "Rescue Memo"
    override val flashedPartition: String = "Flashed"
    override val backupSha256: String = "Backup SHA-256"
    override val backupLocation: String = "Backup External Path"
    override val rescueZipLocation: String = "Rescue Zip External Path"
    override val exportBackup: String = "Export Backup"
    override val exportRescue: String = "Export Rescue Zip"
    override val saveScreenshotMemo: String = "Save Screenshot Memo"
    override val terminalEcho: String = "Terminal Logs"
    override fun linesCount(lines: Int): String = "$lines lines"
    override val expand: String = "Expand"
    override val collapse: String = "Collapse"
    override val flash: String = "Flash"
    override val stage: String = "Stage"
    override val previousPage: String = "Previous"
    override val nextPage: String = "Next"
    override val backupTypeAuto: String = "Auto Backup"
    override val backupTypeManual: String = "Manual Backup"
    override fun singleTransactionSummary(kind: String, opCount: Int, risk: String): String =
        "$kind transaction completed · $opCount low-level ops · $risk"
    override fun multiTransactionSummary(txCount: Int, opCount: Int): String =
        "Transaction package complete: $txCount transactions / $opCount low-level ops"

    // Modules Tab
    override val modulesTitle: String = "Modules"
    override val modulesSubtitle: String =
        "Modules generate constraint-validated device tree transactions; scan discovers capabilities without altering DTS."
    override val noWorkspaceYet: String = "No Workspace Yet"
    override val noWorkspaceHint: String =
        "First import a dtbo.img on Overview tab or extract partition on rooted device."
    override val capabilityScan: String = "Device Tree Capability Scan"
    override val scanning: String = "Scanning…"
    override val scanCompleted: String = "Completed"
    override val waitingForScan: String = "Waiting for scan"
    override fun scanStats(dtb: Int, nodes: Int, props: Int): String =
        "$dtb DTBs · $nodes nodes · $props properties"
    override val scanHint: String = "Automatically identifies refresh rate timings and charging params upon DTBO import."
    override val moduleRefreshRate: String = "Refresh Rate"
    override val moduleCharging: String = "Charging"
    override val moduleAdvancedProps: String = "Advanced Properties"
    override val moduleEditorAlwaysAvailable: String = "Device Tree Editor · Always Available"
    override fun candidatesCount(count: Int): String = "$count candidates"
    override fun statusAvailable(count: Int): String = "Available · $count"
    override fun statusAnalysisOnly(count: Int): String = "Analysis Only · $count"
    override val statusNotFound: String = "Not found in current DTBO"

    // About Screen
    override val aboutTitle: String = "About"
    override val appSubtitle: String = "Android DTBO / Device Tree Analysis, Editing, and Safe Rebuilding Tool"
    override val checkUpdate: String = "Check for Updates"
    override val openSourceRepo: String = "Open Source Repository"
    override val viewSource: String = "View Source"
    override val noBrowserFound: String = "No available browser found"
    override val repoLinkCopied: String = "Repository link copied"
    override val coreArchTitle: String = "Core Architecture & Safety"
    override val coreArchContent: String =
        "• Pure Kotlin DTBO codec engine: Full v0/v1/v2 spec support, automatic metadata verification, preserves compressed entries.\n" +
        "• Triple brick-prevention safeguards: Mandatory physical partition backup, pre-generated offline Recovery zip, read-back SHA-256 auto-rollback.\n" +
        "• Single-slot physical isolation: Strictly operates only on current active A/B slot to avoid dual-slot corruption.\n" +
        "• Multiple timing patch strategies: Balanced Blanking Time, Pixel Clock only, Framerate only modes."
    override val disclaimerCardTitle: String = "Disclaimer & Risk Notice"
    override val disclaimerCardBody: String =
        "This tool performs high-risk low-level hardware modifications. Ensure you fully understand the risks of black screens, bootloops, and hardware wear, and possess independent recovery skills."
    override val viewFullDisclaimer: String = "View Full Disclaimer"
    override val licenseNotice: String =
        "This open-source tool is intended for device owners and developers for display testing and overclocking research. Physical partition flashing involves risk; always keep pre-generated backup rescue files safe."

    // Timing Panel
    override val timingPanelTitle: String = "Timing Tuning & Overclock Deduction"
    override val targetRefreshRate: String = "Target Refresh Rate"
    override val calculationStrategy: String = "Calculation Strategy"
    override val patchMode: String = "Patch Mode"
    override val customParams: String = "Custom Parameters"
    override val pixelClock: String = "Pixel Clock"
    override val verticalFrontPorch: String = "Vertical Front Porch (VFP)"
    override val verticalBackPorch: String = "Vertical Back Porch (VBP)"
    override val horizontalFrontPorch: String = "Horizontal Front Porch (HFP)"
    override val horizontalBackPorch: String = "Horizontal Back Porch (HBP)"
    override val stageTimingChange: String = "Stage Timing Change"
    override val deleteCandidate: String = "Delete Mode"
    override val confirmDeleteModeTitle: String = "Confirm Delete This Mode?"
    override val confirmDeleteModeBody: String =
        "This timing node will be completely removed from the device tree. If no modes remain, the display driver will fail to initialize."
    override val applySuggested: String = "Apply Suggested Parameters"
    override val operationMode: String = "Operation Mode"
    override val deleteTimingCandidateTitle: String = "Prepare to Delete Timing Candidate"
    override fun deleteNodeLabel(name: String, hz: Int): String = "Node to delete: $name ($hz Hz)"
    override fun fullNodePath(path: String): String = "Full node path: $path"
    override val deleteOnlyModeWarning: String =
        "Black screen prevention: This DTB entry only has this single mode. The display panel must retain at least 1 timing mode for display driver initialization. Deletion prohibited!"
    override fun deleteModeRetainHint(count: Int): String =
        "After deletion, this DTB entry will still retain $count timing modes. If this was the default native-mode boot timing, the system will automatically redirect to a remaining mode."
    override val deleteThisCandidateBtn: String = "Delete This Candidate (Stage)"
    override val autoDynamicModeUnsupported: String = "Dynamic Modes Do Not Support Direct Overclock"
    override val autoDynamicModeDesc1: String =
        "This mode contains dynamic frequency switching or low-power parameters and dedicated display commands. Altering refresh rate or copying it to a high refresh mode may cause black screen, abnormal refresh switching, or bootloop."
    override val autoDynamicModeDesc2: String =
        "Please select a standard 'normal' mode for the same panel above, then edit or add. For instance, to add 144 Hz, choose normal_120hz rather than auto_120_to_30hz."
    override val selectNormalModeToContinue: String = "Please select a standard mode to continue"
    override val quickPresets: String = "Quick Presets:"
    override val targetHzInputLabel: String = "Target Refresh Rate (Hz)"
    override val fillSuggestedCustom: String = "Fill Balanced Values"
    override val pixelClockSupporting: String = "Device tree pixel/lane clock in Hz"
    override val advancedBlankingParams: String = "Advanced Blanking Parameters (HFP / HBP)"
    override fun theoreticalRefreshRate(hz: String, target: Int): String =
        "Theoretical physical refresh rate: $hz Hz (Target: $target Hz)"
    override val stageAppendNewMode: String = "Append as New Mode for Panel (Stage)"
    override val stageApplyCurrentMode: String = "Apply to Current Timing (Stage)"
    override val deleteCandidateConfirmTitle: String = "Confirm Deleting This Timing Mode?"
    override fun deleteCandidateConfirmBody(name: String, hz: Int): String =
        "Will remove $name ($hz Hz) node from workspace device tree.\nOnce removed, it will be added to the pending package list until unified packaging."

    // Charging & Thermal
    override val chargingTitle: String = "Charging Parameter Editor"
    override val chargingSubtitle: String = "Charging Parameters"
    override val noChargingNodes: String =
        "No charging parameters found in current DTBO. Configurations may reside in base DTB, vendor_boot, or power management drivers."
    override val scanningCharging: String = "Scanning charging parameters…"
    override val showReadOnlyNodes: String = "Show Read-Only Nodes"
    override fun hideReadOnlyDesc(count: Int): String = "Hiding $count related nodes without verified editable parameters by default"
    override val stageChargingChange: String = "Stage Charging Changes"
    override fun chargingParamsSummary(paramCount: Int, nodeCount: Int, pathCount: Int, dtbCount: Int): String =
        "$paramCount editable params · $nodeCount editable nodes · $pathCount unique paths / $dtbCount DTB instances"
    override fun thermalTableSummary(title: String, levels: Int, cols: Int, unit: String): String =
        "$title · $levels levels × $cols channels ($unit)"
    override val thermalTableDesc: String =
        "Level 1 is most relaxed; higher levels apply stricter current limits as temperature rises. Scroll horizontally to view all channels."
    override val linkedChannels: String = "Link Channels Synchronously"
    override val selectChargingNode: String = "Select Charging Node"
    override val oplusConservativeNotice: String =
        "OPlus conservative binding enabled: Only verified single-value mA/mV parameters are editable, complex strategy tables remain read-only."
    override fun overlayTarget(target: String): String = "Overlay target: &$target"
    override fun editableParamsAndStatus(count: Int, status: String): String =
        "$count editable params · status: $status"
    override fun goToThermalTable(dtb: Int, node: String): String = "Go to thermal table: DTB $dtb · $node"
    override fun toggleNodeList(selecting: Boolean, count: Int): String =
        if (selecting) "Collapse node list" else "Switch charging node ($count)"
    override val nodeWithThermal: String = " · With thermal table"
    override fun nodeStatusWarning(status: String): String =
        "Node status is $status; modifying parameters will not automatically enable the node."
    override val readOnlyNodeNotice: String = "This is a read-only node with no verified editable parameters."
    override val backToEditableNode: String = "Back to editable node"
    override fun stagedModificationsNotice(count: Int): String =
        "Staged $count modifications for this node. Go to Overview to package or undo recent transactions."
    override val noUnitParametersFound: String =
        "Relevant node discovered, but no parameters with confirmed units/formats found. Raw properties viewable below."
    override val chargingParametersTitle: String = "Charging Parameters"
    override val chargingParametersHint: String =
        "Edit in annotated units, automatically converted to device tree units; only modifies current node in current DTB."
    override val thermalTableRulesHint: String =
        "Values in thermal table are current limits per thermal level, preserving level count; subsequent levels in the same channel cannot exceed previous ones."
    override val otherParameters: String = "Other Parameters"
    override val parameterGroups: String = "Parameter groups (scroll horizontally)"
    override fun pendingStageCount(count: Int): String = " · $count pending staging"
    override fun pageAndItemsCount(page: Int, total: Int, items: Int): String =
        "Page $page / $total · $items items"
    override val statusEnabled: String = "Current: Enabled"
    override val statusDisabled: String = "Current: Disabled (property undeclared)"
    override fun currentValueWithRaw(cur: String, unit: String, raw: String, rawUnit: String): String =
        "Current: $cur $unit · Raw: $raw $rawUnit"
    override fun toggleReadOnlyFields(show: Boolean, count: Int): String =
        if (show) "Collapse read-only / abnormal parameters" else "View read-only / abnormal parameters ($count)"
    override fun toggleOtherProps(show: Boolean, count: Int): String =
        if (show) "Collapse other properties" else "View other raw properties ($count)"
    override val otherPropsPreserved: String = "The following properties are not included in charging edits and are preserved as-is."
    override val modificationPreview: String = "Modification Preview"
    override val noModificationsYet: String = "No parameters modified yet"
    override val batterySpecsWarning: String =
        "Please set current and voltage according to battery and charge IC specs. Valid parameters do not imply hardware support; staging allows export testing, package from Overview."
    override val resetUnstagedInput: String = "Reset unstaged input"
    override val stageChargingChanges: String = "Stage Charging Changes"
    override val linkSameChannels: String = "Link Identical Channels"
    override fun linkSameChannelsDesc(channels: String): String =
        "$channels have identical original values; merged into one column for simultaneous edits"
    override val levelHeader: String = "Level"
    override fun columnsLinked(count: Int): String = "+$count linked"
    override val redBoxWarning: String =
        "Red border: Invalid value, or higher than previous level. Subsequent levels cannot exceed previous levels in the same channel."
    override val restoreTableOriginal: String = "Restore Table Original Values"
    override fun toggleBatchAdjust(expanded: Boolean): String =
        if (expanded) "Collapse batch adjust" else "Batch Adjust (Percentage)"
    override val selectChannel: String = "Select Channels"
    override fun andOtherColumns(name: String, count: Int): String = "$name and $count other cols"
    override val fromLevel: String = "From level"
    override val toLevel: String = "To level"
    override val adjustPercent: String = "Adjust %"
    override fun batchAdjustExample(unit: String): String =
        "e.g. +10 scales up by 10%, -20 scales down by 20%. Results rounded to nearest 10 $unit; truncated to ensure subsequent levels don't exceed previous ones."
    override val applyToTable: String = "Apply to Table"
    override fun rawPrefix(orig: String): String = "Orig: $orig"

    // Rollback Screen
    override val rollbackTitle: String = "Image Rollback"
    override val rollbackSubtitle: String = "DTBO Partition Backup Timeline & Restore"
    override val manualBackup: String = "Manual Partition Backup"
    override val refreshBackups: String = "Refresh Backups"
    override val noBackups: String = "No Backup Records"
    override val noBackupsHint: String =
        "Before any physical flashing, the app automatically creates a full verified backup. You can also tap the top right icon to backup manually."
    override val manualBackupTitle: String = "Create Manual Backup"
    override val backupDescLabel: String = "Backup Description / Note"
    override val backupDescPlaceholder: String = "e.g. Stock unmodified backup before OC"
    override val createBackup: String = "Create Backup"
    override val verifyMd5: String = "Verify Integrity"
    override val restoreThisBackup: String = "Restore This Backup"
    override val flashThisBackupTitle: String = "Confirm Flashing This Backup?"
    override fun flashThisBackupBody(partition: String, file: String): String =
        "This will write backup file $file directly into physical partition $partition.\n\nEnsure this backup matches your device and current slot exactly!"
    override val confirmRestore: String = "Confirm Restore"
    override val deleteBackupTitle: String = "Delete This Backup?"
    override fun deleteBackupBody(file: String): String =
        "This will permanently delete backup file $file and its metadata from local storage. This action cannot be undone!"
    override val backupRepo: String = "Backup Image Repository"
    override fun currentSlotSubtitle(slot: String, dev: String): String = "Current slot: $slot ($dev)"
    override fun totalBackupsCount(count: Int): String = "Total $count backups"
    override val manualBackupCurrentPartition: String = "Manually back up current phone DTBO image"
    override fun manualBackupDialogBody(device: String): String =
        "Will read active partition ($device) via Root and save as rollback image."
    override val backupNow: String = "Back Up Now"
    override val confirmRollbackFlashWarning: String =
        "You are about to physically write the selected backup image to the device partition. This will overwrite current DTBO partition!"
    override fun rollbackTargetPartition(dev: String): String = "• Target partition: $dev"
    override fun rollbackBackupFile(file: String): String = "• Backup file: $file"
    override fun rollbackBackupTime(time: String): String = "• Backup time: $time"
    override fun rollbackAndroidVersion(ver: String): String = "• Android version: $ver"
    override fun rollbackBuildDisplay(disp: String): String = "• Build display: $disp"
    override fun rollbackRecordedMd5(md5: String): String = "• Recorded MD5: $md5"
    override val rollbackVerifyNotice: String =
        "System will automatically perform read-back MD5 verification after writing. Ensure battery is sufficient and do not power off or restart during flashing."
    override val confirmRollbackFlashBtn: String = "Confirm Rollback Flash"
    override val infoRowAndroidVersion: String = "Android Version"
    override val infoRowBuildDisplay: String = "Firmware Build"
    override val infoRowDeviceModel: String = "Device Model"
    override val infoRowBackupSlot: String = "Backup Slot"
    override val infoRowFileSize: String = "File Size"
    override val md5Copied: String = "MD5 copied to clipboard"
    override val md5Unchecked: String = "Integrity unchecked"
    override val md5Verifying: String = "Verifying MD5…"
    override val md5Matched: String = "MD5 verified (Match)"
    override val md5Mismatch: String = "MD5 mismatch"
    override val md5FileMissing: String = "Backup image file missing"
    override val emptyBackupsBtn: String = "Back up current image now"

    // Device Tree Editor
    override val deviceTreeTitle: String = "Device Tree"
    override val searchNodes: String = "Search nodes or properties…"
    override val addNode: String = "Add Child Node"
    override val addProperty: String = "Add Property"
    override val editProperty: String = "Edit Property"
    override val deleteNode: String = "Delete Node"
    override val deleteProperty: String = "Delete Property"
    override val cloneNode: String = "Clone Node"
    override val renameNode: String = "Rename Node"
    override val nodeName: String = "Node Name"
    override val propertyName: String = "Property Name"
    override val propertyValue: String = "Property Value"
    override val propertyType: String = "Data Type"
    override val emptyDeviceTree: String = "No Device Tree Loaded"
    override val addChildNode: String = "Add Child Node"
    override val cloneNodeWarning: String =
        "Cloning copies the entire subtree. Subtrees containing labels, phandles, or linux,phandle will be safely blocked to avoid duplicate identities."
    override val nodeNamePlaceholder: String = "Supports unit-address, e.g. timing@3, panel@ae94000"
    override val stageChanges: String = "Stage Changes"
    override val sameNodeNameError: String = "New node name is identical to original"
    override val duplicateChildNodeError: String = "Child node with same name already exists"
    override val searchScopeAll: String = "All"
    override val searchScopeNode: String = "Node"
    override val searchScopeProperty: String = "Property"
    override val searchScopeValue: String = "Value"
    override val searchScopeReference: String = "Reference"
    override val searchScopeModified: String = "Modified"

    // Dangerous Flash Dialog
    override val flashDangerousTitle: String = "High Risk: Flash Physical DTBO Partition"
    override fun flashTarget(partition: String): String = "Target: $partition"
    override val flashWarningBody: String =
        "This app strictly writes to the current active slot. Automatic backup, SHA-256 validation, and Rescue Zip generation occur before writing."
    override val flashViaModuleNotice: String =
        "Packaged as a module and installed via KernelSU / Magisk / APatch; removing module and rebooting automatically restores the original DTBO."
    override fun flashConfirmPrompt(targetHz: Int): String =
        "Type target refresh rate $targetHz, or type uppercase FLASH:"
    override fun flashButtonCountdown(seconds: Int): String =
        "Confirm button unlocks in ${seconds}s"
    override val confirmModuleFlash: String = "Confirm Flash via Module"
    override val confirmSlotFlash: String = "Confirm Single-Slot Flash"
    override val windowSizeInvalid: String = "Current window size is invalid"
    override fun screenshotFailed(code: Int): String = "Screenshot failed: PixelCopy=$code"
    override val screenshotSaved: String = "Screenshot saved"
    override val screenshotWriteFailed: String = "Failed to save screenshot"

    // Disclaimer Dialog
    override val disclaimerTitle: String = "Risk Notice & Terms of Use"
    override val disclaimerWelcome: String =
        "Welcome to DTBO Refresh Overclocker. Before proceeding and granting Root access, please read the following carefully:"
    override val disclaimerSec1Title: String = "1. High-Risk Operation Notice"
    override val disclaimerSec1Content: String =
        "This is a low-level Android hardware tuning tool. Using this software requires Root superuser permissions and directly unpacks, modifies, and rewrites Kernel Device Tree Blobs on the device's physical dtbo partition."
    override val disclaimerSec2Title: String = "2. Potential Serious Risks"
    override val disclaimerSec2Content: String =
        "Refresh rate overclocking is limited by display panel quality and display driver IC (DDIC) tolerance. Improper timing or frequency may cause:\n\n" +
        "• Black / Glitched Screen: Display fails to illuminate or exhibits severe discoloration and burn-in;\n" +
        "• Bootloop: Kernel failure leading to boot logo freeze or reboot loops;\n" +
        "• Hardware Wear: Running beyond rated specs may accelerate component aging, overheating, or permanent physical damage."
    override val disclaimerSec3Title: String = "3. Prerequisites"
    override val disclaimerSec3Content: String =
        "To use this software, you must possess the ability and tools to recover from a bricked state."
    override val disclaimerSec4Title: String = "4. Disclaimer"
    override val disclaimerSec4Content: String =
        "This software is for personal study, display technology research, and performance testing by device owners only. While safeguards and single-slot isolation are implemented, compatibility and safety cannot be guaranteed across all devices, kernels, and ROMs. Users assume sole responsibility for any device damage, data loss, voided warranties, or hardware failures."
    override val disclaimerAgreeCheckbox: String =
        "I have fully read and understood the above risks, confirm I have brick-recovery capability, and voluntarily assume all consequences."
    override fun disclaimerAgreeBtnCountdown(seconds: Int): String = "Agree & Continue (${seconds}s)"
    override val disclaimerAgreeBtn: String = "Agree & Continue"
    override val disclaimerExitApp: String = "Exit App"
    override val disclaimerUnderstood: String = "I Understand"

    // Patch Strategies & Modes
    override val strategyBalancedName: String = "Balanced Timing"
    override val strategyBalancedDesc: String =
        "Adjusts clock and vertical porches for standard timings; preserves porches and scales clock with MDP budget for command mode."
    override val strategyPixelClockName: String = "Pixel Clock Only"
    override val strategyPixelClockDesc: String =
        "Keeps porches unchanged, scales pixel clock proportionally to refresh rate, and scales existing MDP transfer time."
    override val strategyFramerateName: String = "Framerate Only"
    override val strategyFramerateDesc: String =
        "Only modifies framerate property. High compatibility but high risk; not recommended for direct flashing."
    override val strategyCustomName: String = "Custom Timing"
    override val strategyCustomDesc: String =
        "Manually specify Pixel Clock, Vertical Front Porch (VFP), Vertical Back Porch (VBP), and horizontal porches."

    override val patchModeOverwriteName: String = "Modify Existing Mode"
    override val patchModeOverwriteDesc: String =
        "Overclocks selected timing mode directly to target refresh rate (replaces mode)."
    override val patchModeAppendName: String = "Append New Mode"
    override val patchModeAppendDesc: String =
        "Preserves original modes, clones candidate as blueprint, and appends a new mode node."
    override val patchModeDeleteName: String = "Delete Mode"
    override val patchModeDeleteDesc: String =
        "Completely removes selected timing node from device tree (at least one mode must remain)."

    // Capabilities
    override val capabilityRefreshRate: String = "Refresh Rate"
    override val capabilityCharging: String = "Charging"
    override val capabilityAvailable: String = "Available"
    override val capabilityAnalysisOnly: String = "Analysis Only"
    override val capabilityNotFound: String = "Not Found"
}
