package io.mo.dtbooverclocker.ui.components

import android.content.ActivityNotFoundException
import io.mo.dtbooverclocker.ui.theme.Spacing
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.BuildConfig
import io.mo.dtbooverclocker.update.GitHubUpdateChecker
import io.mo.dtbooverclocker.update.UpdateCheckResult

@Composable
fun UpdateCheckDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        result = GitHubUpdateChecker.check(BuildConfig.VERSION_NAME)
    }

    val openRelease: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
        }
    }
    val state = result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when (state) {
                null -> "正在检测更新"
                is UpdateCheckResult.Available -> "发现新版本"
                UpdateCheckResult.UpToDate -> "已是最新版本"
                UpdateCheckResult.NoRelease -> "暂无正式版本"
                is UpdateCheckResult.Failure -> "检测更新失败"
            })
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text("当前版本：${BuildConfig.VERSION_NAME}")
                when (state) {
                    null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("正在查询 GitHub Release…")
                    }
                    is UpdateCheckResult.Available -> {
                        Text("最新版本：${state.version}", style = MaterialTheme.typography.titleMedium)
                        Text("更新说明", style = MaterialTheme.typography.labelLarge)
                        Text(state.notes.ifBlank { "此版本未提供更新说明。" })
                    }
                    UpdateCheckResult.UpToDate -> Text("当前已是最新版本，无需更新。")
                    UpdateCheckResult.NoRelease -> Text("GitHub 上暂未发布可用的正式版本。")
                    is UpdateCheckResult.Failure -> Text(state.message)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateCheckResult.Available -> TextButton(onClick = { openRelease(state.releaseUrl) }) {
                    Text("前往下载")
                }
                is UpdateCheckResult.Failure -> TextButton(onClick = {
                    result = null
                    attempt++
                }) { Text("重试") }
                else -> TextButton(onClick = onDismiss) { Text(if (state == null) "取消" else "确定") }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateCheckResult.Available -> TextButton(onClick = onDismiss) { Text("稍后再说") }
                is UpdateCheckResult.Failure -> TextButton(onClick = {
                    openRelease("${GitHubUpdateChecker.REPOSITORY_URL}/releases")
                }) { Text("查看发布页") }
                else -> Unit
            }
        }
    )
}
