package io.mo.dtbooverclocker.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.dtbooverclocker.core.ActivePanelDetectionResult
import io.mo.dtbooverclocker.core.ActivePanelDetector
import io.mo.dtbooverclocker.core.DtboPatchEngine
import io.mo.dtbooverclocker.core.DtsTimingPatcher
import io.mo.dtbooverclocker.core.NativeToolExecutor
import io.mo.dtbooverclocker.core.RootDetector
import io.mo.dtbooverclocker.core.SafetyGuardManager
import io.mo.dtbooverclocker.core.SlotDetector
import io.mo.dtbooverclocker.model.BackupRecord
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.BackupVerificationState
import io.mo.dtbooverclocker.model.BackupVerificationStatus
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.FlashResult
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.RootState
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.model.SourceMode
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

    companion object {
        private const val KEY_HAS_REQUESTED_ROOT = "has_requested_root"
        private const val KEY_AUTO_CHECK_ROOT = "auto_check_root"
    }

    init {
        AppLogger.init(application)
        refreshEnvironment()
        refreshCacheSize()
        refreshLogStats()
        loadBackups()
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
                val workspace = patchEngine.analyze(image)
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
    }

    fun setPatchMode(mode: PatchMode) {
        _state.update { it.copy(patchMode = mode, patchReport = null) }
    }

    fun patchSelected() {
        viewModelScope.launch {
            val current = _state.value
            val workspace = current.workspace ?: return@launch showError(
                IllegalStateException("请先导入或提取 DTBO 镜像")
            )
            val candidate = workspace.candidates.firstOrNull { it.id == current.selectedCandidateId }
                ?: return@launch showError(IllegalStateException("请选择一个 DSI 时序节点"))

            val actionName = if (current.patchMode == PatchMode.APPEND_NEW) "正在新增档位并重打包 DTBO…" else "正在修补并二次校验 DTBO…"
            setBusy(true, actionName)
            runCatching {
                val report = patchEngine.patch(workspace, candidate, current.targetHz, current.strategy, current.patchMode)
                // 若为新增档位模式，则重新分析 patchedDts 中的时序候选，并更新工作区候选列表，使界面即刻展现追加的新档位
                if (current.patchMode == PatchMode.APPEND_NEW) {
                    val patchedDts = java.io.File(workspace.rootDir, "patched_entry_${candidate.entryIndex}.dts")
                    if (patchedDts.isFile) {
                        val refreshedCandidates = workspace.candidates.toMutableList()
                        refreshedCandidates.removeAll { it.entryIndex == candidate.entryIndex }
                        val newEntryCandidates = DtsTimingPatcher.analyzeEntry(candidate.entryIndex, patchedDts)
                        refreshedCandidates.addAll(newEntryCandidates)
                        val newCandidate = newEntryCandidates.firstOrNull { it.currentHz == current.targetHz }
                        val updatedWorkspace = workspace.copy(candidates = refreshedCandidates)
                        _state.update {
                            it.copy(
                                workspace = updatedWorkspace,
                                selectedCandidateId = newCandidate?.id ?: it.selectedCandidateId,
                                patchReport = report,
                                status = "已生成 ${report.outputImage.name} 并追加新档位"
                            )
                        }
                        return@runCatching report
                    }
                }
                report
            }.onSuccess { report ->
                _state.update {
                    it.copy(
                        patchReport = report,
                        status = "已生成 ${report.outputImage.name}"
                    )
                }
                refreshCacheSize()
            }.onFailure(::showError)
            setBusy(false)
        }
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
                patchReport = null,
                lastFlash = null,
                activePanelIdentifier = activePanelId,
                activePanelDisplayName = activePanelName,
                activePanelSource = activePanelSource,
                status = if (workspace.candidates.isEmpty()) {
                    "解析完成，但没有找到可识别的 DSI framerate 节点"
                } else if (activePanelName != null) {
                    "解析完成：已为您自动匹配并推荐本机在用屏幕 $activePanelName"
                } else {
                    "解析完成：${workspace.metadata.entries.size} 个 DTB 条目，${workspace.candidates.size} 个时序候选"
                }
            )
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
    val lastFlash: FlashResult? = null,
    val logs: List<String> = emptyList(),
    val activePanelIdentifier: String? = null,
    val activePanelDisplayName: String? = null,
    val activePanelSource: String? = null,
    val cacheSizeBytes: Long = 0L,
    val logFilesCount: Int = 0,
    val logFilesSizeBytes: Long = 0L,
    val backups: List<BackupRecord> = emptyList(),
    val backupVerificationStates: Map<String, BackupVerificationState> = emptyMap()
)
