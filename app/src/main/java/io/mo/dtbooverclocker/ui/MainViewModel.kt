package io.mo.dtbooverclocker.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.dtbooverclocker.core.ActivePanelDetectionResult
import io.mo.dtbooverclocker.core.ActivePanelDetector
import io.mo.dtbooverclocker.core.CapabilityScanner
import io.mo.dtbooverclocker.core.DtboPatchEngine
import io.mo.dtbooverclocker.core.DtsTimingPatcher
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransactionKind
import io.mo.dtbooverclocker.core.NativeToolExecutor
import io.mo.dtbooverclocker.core.RootDetector
import io.mo.dtbooverclocker.core.SafetyGuardManager
import io.mo.dtbooverclocker.core.SlotDetector
import io.mo.dtbooverclocker.model.BackupRecord
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.BackupVerificationState
import io.mo.dtbooverclocker.model.BackupVerificationStatus
import io.mo.dtbooverclocker.model.CapabilityReport
import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.FlashResult
import io.mo.dtbooverclocker.model.ModuleStagedChange
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.ResolutionScope
import io.mo.dtbooverclocker.model.RootState
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.model.SourceMode
import io.mo.dtbooverclocker.model.StagedChange
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.util.AppLogger
import io.mo.dtbooverclocker.util.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
    private val executor = NativeToolExecutor(application, ::appendLog)
    private val rootDetector = RootDetector(executor)
    private val activePanelDetector = ActivePanelDetector(rootDetector)
    private val slotDetector = SlotDetector(executor)
    private val patchEngine = DtboPatchEngine(application, executor, ::appendLog)
    private val safetyGuard = SafetyGuardManager(application, rootDetector, ::appendLog)
    private var capabilityScanGeneration: Long = 0L

    companion object {
        private const val KEY_HAS_REQUESTED_ROOT = "has_requested_root"
        private const val KEY_AUTO_CHECK_ROOT = "auto_check_root"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    }

    init {
        AppLogger.init(application)
        val accepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
        _state.update { it.copy(isDisclaimerAccepted = accepted) }
        if (accepted) {
            refreshEnvironment()
        } else {
            _state.update { it.copy(status = "等待同意免责声明") }
        }
        refreshCacheSize()
        refreshLogStats()
        loadBackups()
    }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
        _state.update { it.copy(isDisclaimerAccepted = true) }
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            runCatching {
                val shouldAutoCheck = prefs.getBoolean(KEY_HAS_REQUESTED_ROOT, false) ||
                    prefs.getBoolean(KEY_AUTO_CHECK_ROOT, true)
                val root = rootDetector.probe(checkAuth = shouldAutoCheck)
                if (root.granted) {
                    prefs.edit().putBoolean(KEY_HAS_REQUESTED_ROOT, true).apply()
                }
                val slot = slotDetector.detect()
                AppLogger.logSystemBaseline(
                    context = getApplication(),
                    rootDetail = if (root.granted) "Root 已授权 (自动保持)" else root.detail,
                    slotLabel = slot.label,
                    blockDevice = slot.blockDevice
                )
                refreshLogStats()
                _state.update {
                    it.copy(
                        rootState = root,
                        slotInfo = slot,
                        status = "环境探测完成"
                    )
                }
            }.onFailure(::showError)
        }
    }

    fun requestRoot() {
        viewModelScope.launch {
            setBusy(true, "正在请求 Root 授权…")
            runCatching { rootDetector.requestRoot() }
                .onSuccess { root ->
                    if (root.granted) {
                        prefs.edit().putBoolean(KEY_HAS_REQUESTED_ROOT, true).apply()
                    }
                    _state.update { it.copy(rootState = root, status = root.detail) }
                }
                .onFailure(::showError)
            setBusy(false)
        }
    }

    fun setSourceMode(mode: SourceMode) {
        _state.update { it.copy(sourceMode = mode) }
    }

    fun importImage(uri: Uri) {
        viewModelScope.launch {
            setBusy(true, "正在导入并解析 DTBO…")
            runCatching {
                val image = patchEngine.importImage(uri)
                val workspace = patchEngine.analyze(image)
                val detectedActive = if (_state.value.rootState.granted) {
                    activePanelDetector.detect()
                } else null
                applyWorkspace(workspace, SourceMode.LOCAL_IMAGE, detectedActive)
                refreshCacheSize()
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun extractActivePartition() {
        viewModelScope.launch {
            setBusy(true, "正在提取当前活跃槽位 DTBO…")
            runCatching {
                val slot = _state.value.slotInfo ?: slotDetector.detect()
                val root = rootDetector.requestRoot()
                require(root.granted) { root.detail }
                prefs.edit().putBoolean(KEY_HAS_REQUESTED_ROOT, true).apply()
                _state.update { it.copy(rootState = root, slotInfo = slot) }

                val image = safetyGuard.extractActiveImage(slot)
                val workspace = patchEngine.analyze(image, SourceMode.ROOT_PARTITION, slot.blockDevice)
                val detectedActive = activePanelDetector.detect()
                applyWorkspace(workspace, SourceMode.ROOT_PARTITION, detectedActive)
                refreshCacheSize()
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun selectCandidate(id: String) {
        val candidate = _state.value.workspace?.candidates?.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                selectedCandidateId = id,
                targetHz = suggestedTarget(candidate.currentHz),
                resolutionWidthText = candidate.hActive?.let { width ->
                    if (width % 4 == 0) (width * 3 / 4).toString() else ""
                } ?: "",
                resolutionHeightText = candidate.vActive?.let { height ->
                    if (height % 4 == 0) (height * 3 / 4).toString() else ""
                } ?: "",
                patchReport = null,
                lastFlash = null
            )
        }
    }

    fun setTargetHz(value: Int) {
        _state.update { it.copy(targetHz = value.coerceIn(30, 360), patchReport = null) }
    }

    fun setStrategy(strategy: PatchStrategy) {
        _state.update { it.copy(strategy = strategy, patchReport = null) }
        if (strategy == PatchStrategy.CUSTOM) {
            val s = _state.value
            if (s.customPixelClockText.isBlank() && s.customVfpText.isBlank()) {
                applySuggestedCustomParams()
            }
        }
    }

    fun setPatchMode(mode: PatchMode) {
        _state.update { it.copy(patchMode = mode, patchReport = null) }
    }

    fun setCustomPixelClock(value: String) {
        _state.update { it.copy(customPixelClockText = value, patchReport = null) }
    }

    fun setCustomVfp(value: String) {
        _state.update { it.copy(customVfpText = value, patchReport = null) }
    }

    fun setCustomVbp(value: String) {
        _state.update { it.copy(customVbpText = value, patchReport = null) }
    }

    fun setCustomHfp(value: String) {
        _state.update { it.copy(customHfpText = value, patchReport = null) }
    }

    fun setCustomHbp(value: String) {
        _state.update { it.copy(customHbpText = value, patchReport = null) }
    }

    fun applySuggestedCustomParams() {
        val current = _state.value
        val candidate = current.workspace?.candidates?.firstOrNull { it.id == current.selectedCandidateId } ?: return
        val sim = TimingUtils.calculateSimulation(candidate, current.targetHz, PatchStrategy.BALANCED_BLANKING_TIME)
        _state.update {
            it.copy(
                customPixelClockText = (sim.estimatedClockHz ?: candidate.pixelClockHz ?: "").toString(),
                customVfpText = (sim.estimatedVfp ?: candidate.vFrontPorch ?: "").toString(),
                customVbpText = (sim.estimatedVbp ?: candidate.vBackPorch ?: "").toString(),
                customHfpText = (candidate.hFrontPorch ?: "").toString(),
                customHbpText = (candidate.hBackPorch ?: "").toString(),
                patchReport = null
            )
        }
    }

    fun stageTimingChange() {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch showError(
                IllegalStateException("请先导入或提取 DTBO 镜像")
            )
            val candidate = workspace.candidates.firstOrNull { it.id == current.selectedCandidateId }
                ?: return@launch showError(IllegalStateException("请选择一个 DSI 时序节点"))

            if (current.patchMode == PatchMode.DELETE_EXISTING) {
                val countInEntry = workspace.candidates.count { it.entryIndex == candidate.entryIndex }
                requireOrReport(countInEntry > 1) {
                    "当前 DTB 镜像条目仅存 1 个时序档位，删除会导致设备无法点亮屏幕，已拒绝操作。"
                } ?: return@launch
            }

            val customParams = if (current.strategy == PatchStrategy.CUSTOM && current.patchMode != PatchMode.DELETE_EXISTING) {
                val p = current.customTimingParams
                requireOrReport(p != null && (p.pixelClockHz != null || candidate.pixelClockHz != null)) {
                    "在自定义计算策略下，必须输入有效的像素时钟 (Pixel Clock)"
                } ?: return@launch
                p
            } else null

            runCatching {
                patchEngine.applyTimingChange(
                    workspace = workspace,
                    candidate = candidate,
                    targetHz = current.targetHz,
                    strategy = current.strategy,
                    mode = current.patchMode,
                    customParams = customParams
                )
            }.onSuccess { result ->
                val transaction = DeviceTreeTransaction.refreshRate(
                    stagedChange = result.stagedChange,
                    operations = result.operations,
                    warnings = result.warnings,
                    directFlashAllowed = result.stagedChange.strategy != PatchStrategy.FRAMERATE_ONLY
                )
                val newTransactions = current.transactions + transaction
                _state.update {
                    it.copy(
                        workspace = result.updatedWorkspace,
                        selectedCandidateId = result.selectedCandidateId,
                        transactions = newTransactions,
                        patchReport = null,
                        status = "已暂存修改：${transaction.summary} (共 ${newTransactions.size} 个事务待打包)",
                        patchMode = if (current.patchMode == PatchMode.DELETE_EXISTING) PatchMode.OVERWRITE_EXISTING else it.patchMode
                    )
                }
                refreshCapabilities(result.updatedWorkspace)
            }.onFailure(::showError)
        }
    }


    fun setResolutionWidth(value: String)
    {
        _state.update {
            it.copy(
                resolutionWidthText = value.filter(Char::isDigit),
                patchReport = null
            )
        }
    }

    fun setResolutionHeight(value: String)
    {
        _state.update {
            it.copy(
                resolutionHeightText = value.filter(Char::isDigit),
                patchReport = null
            )
        }
    }

    fun setResolutionScope(scope: ResolutionScope)
    {
        _state.update { it.copy(resolutionScope = scope, patchReport = null) }
    }

    fun applyResolutionPreset(width: Int, height: Int)
    {
        _state.update {
            it.copy(
                resolutionWidthText = width.toString(),
                resolutionHeightText = height.toString(),
                patchReport = null
            )
        }
    }

    fun stageResolutionChange()
    {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch showError(
                IllegalStateException("请先导入或提取 DTBO 镜像")
            )
            val candidate = workspace.candidates
                .firstOrNull { it.id == current.selectedCandidateId }
                ?: return@launch showError(IllegalStateException("请选择一个带分辨率信息的 DSI 时序节点"))

            val targetWidth = current.resolutionWidthText.toIntOrNull()
                ?: return@launch showError(IllegalArgumentException("请输入有效的目标宽度"))
            val targetHeight = current.resolutionHeightText.toIntOrNull()
                ?: return@launch showError(IllegalArgumentException("请输入有效的目标高度"))

            runCatching {
                patchEngine.applyResolutionChange(
                    workspace = workspace,
                    candidate = candidate,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    scope = current.resolutionScope
                )
            }.onSuccess { result ->
                val transaction = DeviceTreeTransaction.resolution(
                    moduleChange = result.stagedChange,
                    operations = result.operations
                )
                val newTransactions = current.transactions + transaction

                _state.update {
                    it.copy(
                        workspace = result.updatedWorkspace,
                        selectedCandidateId = result.selectedCandidateId,
                        transactions = newTransactions,
                        patchReport = null,
                        lastFlash = null,
                        status = "已暂存分辨率修改：${transaction.summary} (共 ${newTransactions.size} 个事务待打包)"
                    )
                }
                refreshCapabilities(result.updatedWorkspace)
            }.onFailure(::showError)
        }
    }


    fun setDeviceTreeProperty(
        entryIndex: Int,
        nodePath: String,
        propertyName: String,
        newRawValue: String?
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildSetChange(
                entryIndex = entryIndex,
                text = text,
                nodePath = nodePath,
                propertyName = propertyName,
                newRawValue = newRawValue
            )
        }
    }

    fun addDeviceTreeProperty(
        entryIndex: Int,
        nodePath: String,
        propertyName: String,
        rawValue: String?
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildAddChange(
                entryIndex = entryIndex,
                text = text,
                nodePath = nodePath,
                propertyName = propertyName,
                newRawValue = rawValue
            )
        }
    }

    fun deleteDeviceTreeProperty(
        entryIndex: Int,
        nodePath: String,
        propertyName: String
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildDeleteChange(
                entryIndex = entryIndex,
                text = text,
                nodePath = nodePath,
                propertyName = propertyName
            )
        }
    }


    fun addDeviceTreeNode(
        entryIndex: Int,
        parentNodePath: String,
        nodeName: String
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildAddNodeChange(
                entryIndex = entryIndex,
                text = text,
                parentNodePath = parentNodePath,
                nodeName = nodeName
            )
        }
    }

    fun cloneDeviceTreeNode(
        entryIndex: Int,
        sourceNodePath: String,
        newNodeName: String
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildCloneNodeChange(
                entryIndex = entryIndex,
                text = text,
                sourceNodePath = sourceNodePath,
                newNodeName = newNodeName
            )
        }
    }

    fun renameDeviceTreeNode(
        entryIndex: Int,
        nodePath: String,
        newNodeName: String
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildRenameNodeChange(
                entryIndex = entryIndex,
                text = text,
                nodePath = nodePath,
                newNodeName = newNodeName
            )
        }
    }

    fun deleteDeviceTreeNode(
        entryIndex: Int,
        nodePath: String
    ) {
        stageDeviceTreeChange(entryIndex) { text ->
            DeviceTreeEditor.buildDeleteNodeChange(
                entryIndex = entryIndex,
                text = text,
                nodePath = nodePath
            )
        }
    }

    fun undoDeviceTreeChange(changeId: String) {
        viewModelScope.launch {
            undoLastTransactionInternal(requiredGenericChangeId = changeId)
        }
    }

    fun undoLastTransaction()
    {
        viewModelScope.launch {
            undoLastTransactionInternal(requiredGenericChangeId = null)
        }
    }

    private suspend fun undoLastTransactionInternal(requiredGenericChangeId: String?)
    {
        val current = _state.value
        val workspace = current.workspace ?: return
        val transaction = current.transactions.lastOrNull() ?: return

        if (requiredGenericChangeId != null)
        {
            val change = transaction.operations.singleOrNull { it.id == requiredGenericChangeId }
            requireOrReport(
                transaction.kind == DeviceTreeTransactionKind.GENERIC_EDIT && change != null
            ) {
                "为避免破坏事务顺序，设备树详情页只能撤销当前队列最后一个通用编辑事务。"
            } ?: return
        }

        runCatching {
            var updatedWorkspace = workspace
            transaction.operations.asReversed().forEach { operation ->
                updatedWorkspace = patchEngine.applyDeviceTreeChange(
                    updatedWorkspace,
                    operation.inverse()
                )
            }
            updatedWorkspace
        }.onSuccess { updatedWorkspace ->
            val remaining = current.transactions.dropLast(1)
            _state.update {
                it.copy(
                    workspace = updatedWorkspace,
                    transactions = remaining,
                    patchReport = null,
                    lastFlash = null,
                    status = "已原子撤销事务：${transaction.kind.displayName} · ${transaction.summary}"
                )
            }
            refreshCapabilities(updatedWorkspace)
        }.onFailure(::showError)
    }

    private fun stageDeviceTreeChange(
        entryIndex: Int,
        builder: (String) -> DeviceTreeChange
    ) {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch showError(
                IllegalStateException("请先导入或提取 DTBO 镜像")
            )

            runCatching {
                val dtsFile = File(workspace.rootDir, "dts/entry_$entryIndex.dts")
                require(dtsFile.isFile) { "Entry $entryIndex 没有可编辑的 DTS 文件" }
                val change = withContext(Dispatchers.IO) {
                    builder(dtsFile.readText())
                }
                val updatedWorkspace = patchEngine.applyDeviceTreeChange(workspace, change)
                change to updatedWorkspace
            }.onSuccess { (change, updatedWorkspace) ->
                val transaction = DeviceTreeTransaction.generic(change)
                val newTransactions = current.transactions + transaction
                _state.update {
                    it.copy(
                        workspace = updatedWorkspace,
                        transactions = newTransactions,
                        patchReport = null,
                        lastFlash = null,
                        status = "已暂存设备树修改：${transaction.summary} (共 ${newTransactions.size} 个事务待打包)"
                    )
                }
                refreshCapabilities(updatedWorkspace)
            }.onFailure(::showError)
        }
    }

    fun resetStagedChanges() {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch
            setBusy(true, "正在重置所有修改…")
            runCatching {
                patchEngine.resetWorkspace(workspace)
            }.onSuccess { restoredWorkspace ->
                _state.update {
                    it.copy(
                        workspace = restoredWorkspace,
                        selectedCandidateId = restoredWorkspace.candidates.firstOrNull()?.id,
                        transactions = emptyList(),
                        patchReport = null,
                        status = "已重置所有修改，恢复原始工作区"
                    )
                }
                refreshCapabilities(restoredWorkspace)
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun packageStagedChanges() {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch showError(
                IllegalStateException("请先导入或提取 DTBO 镜像")
            )
            requireOrReport(current.transactions.isNotEmpty()) {
                "当前尚未暂存任何修改，请先修改功能模块、时序或设备树属性后再打包"
            } ?: return@launch

            setBusy(true, "正在重编译 DTB 并集中打包 DTBO 镜像…")
            runCatching {
                patchEngine.packageStaged(
                    workspace = workspace,
                    transactions = current.transactions
                )
            }.onSuccess { report ->
                _state.update {
                    it.copy(
                        patchReport = report,
                        status = "打包完成，成功生成 ${report.outputImage.name} (包含 ${current.transactions.size} 个事务 / ${current.transactions.sumOf { transaction -> transaction.operationCount }} 个底层操作)"
                    )
                }
                refreshCacheSize()
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun patchSelected() {
        stageTimingChange()
    }

    fun flashPatched() {
        viewModelScope.launch {
            val current = _state.value
            val report = current.patchReport
                ?: return@launch showError(IllegalStateException("尚未生成修补镜像"))
            val slot = current.slotInfo
                ?: return@launch showError(IllegalStateException("无法确定目标槽位"))
            requireOrReport(current.sourceMode == SourceMode.ROOT_PARTITION) {
                "直接刷写仅允许用于“从手机当前分区读取”的工作区，防止误刷入来自其他设备的导入镜像。"
            } ?: return@launch
            requireOrReport(current.transactions.isNotEmpty()) {
                "当前没有可刷写的设备树事务。"
            } ?: return@launch
            requireOrReport(current.transactions.all { it.directFlashAllowed }) {
                "当前事务队列包含仅允许导出验证的修改（例如分辨率或通用设备树编辑），已禁止 Root 直刷。"
            } ?: return@launch

            setBusy(true, "正在执行备份、救援包生成与单槽位刷写…")
            runCatching {
                safetyGuard.flashPatchedImage(report, slot)
            }.onSuccess { result ->
                loadBackups()
                _state.update {
                    it.copy(
                        lastFlash = result,
                        status = "刷写完成并通过回读校验"
                    )
                }
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun prepareRecoveryZip(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val current = _state.value
            val patched = current.patchReport?.outputImage
                ?: return@launch showError(IllegalStateException("请先生成修补镜像"))
            val slot = current.slotInfo
                ?: return@launch showError(IllegalStateException("无法确定 DTBO 分区路径"))

            setBusy(true, "正在生成 Recovery 单槽位刷机 Zip…")
            runCatching {
                safetyGuard.generatePatchedRecoveryZip(patched, slot)
            }.onSuccess(onReady).onFailure(::showError)
            setBusy(false)
        }
    }

    fun prepareFastbootBundle(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val current = _state.value
            val patched = current.patchReport?.outputImage
                ?: return@launch showError(IllegalStateException("请先生成修补镜像"))
            val slot = current.slotInfo
                ?: return@launch showError(IllegalStateException("无法确定槽位"))
            val original = current.workspace?.inputImage

            setBusy(true, "正在生成 PC Fastboot 一键包…")
            runCatching {
                safetyGuard.generateFastbootBundle(patched, slot, original)
            }.onSuccess(onReady).onFailure(::showError)
            setBusy(false)
        }
    }

    fun exportFile(file: File, uri: Uri) {
        viewModelScope.launch {
            setBusy(true, "正在导出 ${file.name}…")
            runCatching { patchEngine.exportFile(file, uri) }
                .onSuccess { _state.update { it.copy(status = "导出完成：${file.name}") } }
                .onFailure(::showError)
            setBusy(false)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val size = StorageUtils.getAppCacheSize(app)
            _state.update { it.copy(cacheSizeBytes = size) }
        }
    }

    fun clearAllCache(onComplete: ((freedBytes: Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            setBusy(true, "正在清理应用所有缓存…")
            runCatching {
                val app = getApplication<Application>()
                val before = StorageUtils.getAppCacheSize(app)
                StorageUtils.clearAllCache(app)
                val after = StorageUtils.getAppCacheSize(app)
                val freed = (before - after).coerceAtLeast(0L)

                _state.update { current ->
                    val keepWorkspace = current.workspace?.rootDir?.exists() == true
                    val keepReport = current.patchReport?.outputImage?.exists() == true
                    current.copy(
                        cacheSizeBytes = after,
                        workspace = if (keepWorkspace) current.workspace else null,
                        patchReport = if (keepReport) current.patchReport else null,
                        status = "已清除缓存 (释放 ${StorageUtils.formatFileSize(freed)})"
                    )
                }
                appendLog("[INFO] 已清除应用缓存，释放 ${StorageUtils.formatFileSize(freed)}")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(freed)
                }
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    fun refreshLogStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = AppLogger.getLogFiles().size
            val size = AppLogger.getTotalLogSize()
            _state.update {
                it.copy(logFilesCount = count, logFilesSizeBytes = size)
            }
        }
    }

    fun clearLogFiles(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.clearAllLogs()
            val count = AppLogger.getLogFiles().size
            val size = AppLogger.getTotalLogSize()
            _state.update {
                it.copy(
                    logs = emptyList(),
                    logFilesCount = count,
                    logFilesSizeBytes = size,
                    status = "日志文件已清空"
                )
            }
            appendLog("[INFO] 历史日志已清空，已开启新会话日志")
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun exportLogsToUri(targetUri: Uri, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            setBusy(true, "正在导出完整日志…")
            val success = runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(targetUri, "w")?.use { out ->
                    AppLogger.exportLogs(out)
                } ?: error("无法打开目标 URI 写入日志")
                _state.update { it.copy(status = "日志导出完成") }
                appendLog("[OK] 完整日志已成功导出至指定文件")
                true
            }.getOrElse {
                showError(it)
                false
            }
            refreshLogStats()
            setBusy(false)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(success)
            }
        }
    }

    fun loadBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = safetyGuard.backupManager.getBackups()
            _state.update { it.copy(backups = list) }
        }
    }

    fun createManualBackup(description: String = "", onComplete: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val slot = _state.value.slotInfo
            if (slot == null) {
                val err = "无法获取当前分区槽位信息"
                showError(IllegalStateException(err))
                onComplete?.invoke(false, err)
                return@launch
            }

            setBusy(true, "正在手动备份当前物理分区 ${slot.blockDevice}…")
            runCatching {
                safetyGuard.backupManager.createBackupFromPartition(
                    slot = slot,
                    type = BackupType.MANUAL,
                    description = description.ifBlank { "手动备份当前 DTBO 分区" }
                )
            }.onSuccess { record ->
                loadBackups()
                _state.update { it.copy(status = "手动备份成功：${record.fileName}") }
                onComplete?.invoke(true, "备份成功：${record.fileName}")
            }.onFailure { err ->
                showError(err)
                onComplete?.invoke(false, err.message ?: "备份失败")
            }
            setBusy(false)
        }
    }

    fun verifyBackupMd5(record: BackupRecord) {
        viewModelScope.launch {
            _state.update { current ->
                current.copy(
                    backupVerificationStates = current.backupVerificationStates + (record.id to BackupVerificationState(
                        status = BackupVerificationStatus.VERIFYING
                    ))
                )
            }
            val result = safetyGuard.backupManager.verifyMd5(record)
            _state.update { current ->
                current.copy(
                    backupVerificationStates = current.backupVerificationStates + (record.id to result)
                )
            }
        }
    }

    fun exportBackup(
        record: BackupRecord,
        targetUri: Uri? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            setBusy(true, "正在导出 ${record.fileName}…")
            runCatching {
                safetyGuard.backupManager.exportBackup(record, targetUri)
            }.onSuccess { path ->
                _state.update { it.copy(status = "导出完成：$path") }
                onComplete?.invoke(true, path)
            }.onFailure { err ->
                showError(err)
                onComplete?.invoke(false, err.message ?: "导出失败")
            }
            setBusy(false)
        }
    }

    fun flashBackup(
        record: BackupRecord,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val slot = _state.value.slotInfo
            if (slot == null) {
                val err = "无法获取当前分区槽位信息"
                showError(IllegalStateException(err))
                onComplete?.invoke(false, err)
                return@launch
            }

            setBusy(true, "正在回滚刷入备份 ${record.fileName}…")
            runCatching {
                safetyGuard.backupManager.flashBackup(record, slot)
            }.onSuccess {
                loadBackups()
                _state.update { it.copy(status = "回滚刷入成功！已写入 ${slot.blockDevice}") }
                onComplete?.invoke(true, "回滚刷入成功！已写回并完成回读校验")
            }.onFailure { err ->
                showError(err)
                onComplete?.invoke(false, err.message ?: "回滚刷入失败")
            }
            setBusy(false)
        }
    }

    fun deleteBackup(
        record: BackupRecord,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = safetyGuard.backupManager.deleteBackup(record.id)
            loadBackups()
            withContext(Dispatchers.Main) {
                onComplete?.invoke(success)
            }
        }
    }

    private fun applyWorkspace(
        workspace: DtboWorkspace,
        sourceMode: SourceMode,
        detectedActive: ActivePanelDetectionResult? = null
    ) {
        var activePanelId: String? = null
        var activePanelName: String? = null
        var activePanelSource: String? = null
        var selectedCandidate: TimingCandidate? = null

        if (detectedActive != null) {
            val bestCandidate = ActivePanelDetector.findBestMatchCandidate(
                workspace.candidates,
                detectedActive.rawIdentifier
            )
            if (bestCandidate != null) {
                selectedCandidate = bestCandidate
                activePanelId = TimingUtils.parsePanelIdentifier(bestCandidate.nodePath)
                activePanelName = TimingUtils.formatPanelDisplayName(activePanelId)
                activePanelSource = detectedActive.source
                appendLog("[INFO] 自动匹配到本机活动屏幕面板：$activePanelName（来源：${detectedActive.source}）")
            } else {
                appendLog("[INFO] 探测到在用面板标识 ${detectedActive.rawIdentifier}，但在当前 DTBO 中未找到对应时序节点")
            }
        }

        if (selectedCandidate == null) {
            selectedCandidate = workspace.candidates.firstOrNull()
        }

        _state.update {
            it.copy(
                sourceMode = sourceMode,
                workspace = workspace,
                selectedCandidateId = selectedCandidate?.id,
                targetHz = suggestedTarget(selectedCandidate?.currentHz ?: 60),
                resolutionWidthText = selectedCandidate?.hActive?.let { width ->
                    if (width % 4 == 0) (width * 3 / 4).toString() else ""
                } ?: "",
                resolutionHeightText = selectedCandidate?.vActive?.let { height ->
                    if (height % 4 == 0) (height * 3 / 4).toString() else ""
                } ?: "",
                patchReport = null,
                transactions = emptyList(),
                lastFlash = null,
                activePanelIdentifier = activePanelId,
                activePanelDisplayName = activePanelName,
                activePanelSource = activePanelSource,
                capabilityReport = null,
                capabilityScanInProgress = true,
                status = if (workspace.candidates.isEmpty()) {
                    "解析完成，但没有找到可识别的 DSI framerate 节点"
                } else if (activePanelName != null) {
                    "解析完成：已为您自动匹配并推荐本机在用屏幕 $activePanelName"
                } else {
                    "解析完成：${workspace.metadata.entries.size} 个 DTB 条目，${workspace.candidates.size} 个时序候选"
                }
            )
        }
        refreshCapabilities(workspace)
    }

    private fun refreshCapabilities(workspace: DtboWorkspace)
    {
        val generation = ++capabilityScanGeneration
        _state.update { it.copy(capabilityScanInProgress = true) }

        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { CapabilityScanner.scan(workspace) }
            withContext(Dispatchers.Main) {
                if (generation != capabilityScanGeneration)
                {
                    return@withContext
                }

                result.onSuccess { report ->
                    _state.update {
                        it.copy(
                            capabilityReport = report,
                            capabilityScanInProgress = false
                        )
                    }
                    appendLog(
                        "[CAPABILITY] 扫描完成：${report.nodeCount} 节点 / ${report.propertyCount} 属性 / " +
                            "${report.dscTopologies.size} 个 DSC timing"
                    )
                }.onFailure { throwable ->
                    _state.update { it.copy(capabilityScanInProgress = false) }
                    appendLog("[WARN][CAPABILITY] ${throwable.message ?: throwable::class.java.simpleName}")
                }
            }
        }
    }

    private fun suggestedTarget(currentHz: Int): Int {
        return when {
            currentHz < 60 -> 60
            currentHz < 90 -> 90
            currentHz < 120 -> 120
            currentHz < 144 -> 144
            currentHz < 165 -> 165
            else -> (currentHz + 15).coerceAtMost(240)
        }
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        _state.update { current ->
            current.copy(
                busy = busy,
                status = status ?: current.status
            )
        }
    }

    private fun showError(throwable: Throwable) {
        val message = throwable.message ?: throwable::class.java.simpleName
        appendLog("[ERROR] $message")
        _state.update { it.copy(status = "错误：$message") }
    }

    private fun appendLog(line: String) {
        AppLogger.log(line)
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(800))
        }
    }

    private inline fun requireOrReport(condition: Boolean, message: () -> String): Unit? {
        if (condition) return Unit
        showError(IllegalStateException(message()))
        return null
    }
}

data class MainUiState(
    val isDisclaimerAccepted: Boolean = false,
    val rootState: RootState = RootState(),
    val slotInfo: SlotInfo? = null,
    val sourceMode: SourceMode = SourceMode.LOCAL_IMAGE,
    val busy: Boolean = false,
    val status: String = "初始化中…",
    val workspace: DtboWorkspace? = null,
    val selectedCandidateId: String? = null,
    val targetHz: Int = 75,
    val strategy: PatchStrategy = PatchStrategy.BALANCED_BLANKING_TIME,
    val patchMode: PatchMode = PatchMode.OVERWRITE_EXISTING,
    val patchReport: PatchReport? = null,
    val transactions: List<DeviceTreeTransaction> = emptyList(),
    val lastFlash: FlashResult? = null,
    val logs: List<String> = emptyList(),
    val activePanelIdentifier: String? = null,
    val activePanelDisplayName: String? = null,
    val activePanelSource: String? = null,
    val capabilityReport: CapabilityReport? = null,
    val capabilityScanInProgress: Boolean = false,
    val cacheSizeBytes: Long = 0L,
    val logFilesCount: Int = 0,
    val logFilesSizeBytes: Long = 0L,
    val backups: List<BackupRecord> = emptyList(),
    val backupVerificationStates: Map<String, BackupVerificationState> = emptyMap(),
    val resolutionWidthText: String = "",
    val resolutionHeightText: String = "",
    val resolutionScope: ResolutionScope = ResolutionScope.MATCHING_GROUP,
    val customPixelClockText: String = "",
    val customVfpText: String = "",
    val customVbpText: String = "",
    val customHfpText: String = "",
    val customHbpText: String = ""
) {
    // 兼容现有 UI / 报告的数据视图：transactions 才是唯一暂存状态源。
    // 这些列表禁止独立写入，后续迁移完成后可逐步删除兼容层。
    val stagedChanges: List<StagedChange>
        get() = transactions.mapNotNull { it.timingChange }

    val timingDeviceTreeChanges: List<DeviceTreeChange>
        get() = transactions
            .filter { it.kind == DeviceTreeTransactionKind.REFRESH_RATE }
            .flatMap { it.operations }

    val moduleStagedChanges: List<ModuleStagedChange>
        get() = transactions.mapNotNull { it.moduleChange }

    val moduleDeviceTreeChanges: List<DeviceTreeChange>
        get() = transactions
            .filter { it.kind == DeviceTreeTransactionKind.RESOLUTION }
            .flatMap { it.operations }

    val deviceTreeChanges: List<DeviceTreeChange>
        get() = transactions
            .filter { it.kind == DeviceTreeTransactionKind.GENERIC_EDIT }
            .flatMap { it.operations }

    val modifiedEntryIndices: Set<Int>
        get() = transactions.flatMap { it.entryIndices }.toSet()

    val customTimingParams: CustomTimingParams?
        get() = if (strategy == PatchStrategy.CUSTOM) {
            CustomTimingParams(
                pixelClockHz = customPixelClockText.filter(Char::isDigit).toLongOrNull(),
                vFrontPorch = customVfpText.filter(Char::isDigit).toIntOrNull(),
                vBackPorch = customVbpText.filter(Char::isDigit).toIntOrNull(),
                hFrontPorch = customHfpText.filter(Char::isDigit).toIntOrNull(),
                hBackPorch = customHbpText.filter(Char::isDigit).toIntOrNull()
            )
        } else null
}
