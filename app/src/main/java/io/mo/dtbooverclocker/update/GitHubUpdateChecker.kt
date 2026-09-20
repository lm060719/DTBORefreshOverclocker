package io.mo.dtbooverclocker.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal sealed interface UpdateCheckResult {
    data class Available(val version: String, val notes: String, val releaseUrl: String) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Failure(val message: String) : UpdateCheckResult
}

internal object GitHubUpdateChecker {
    const val REPOSITORY_URL = "https://github.com/lm060719/DTBORefreshOverclocker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/lm060719/DTBORefreshOverclocker/releases/latest"

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("User-Agent", "DTBORefreshOverclocker/$currentVersion")
                when (val status = connection.responseCode) {
                    200 -> {
                        val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        parseRelease(json, currentVersion)
                    }
                    404 -> UpdateCheckResult.NoRelease
                    403, 429 -> UpdateCheckResult.Failure("GitHub 暂时拒绝请求或请求过于频繁，请稍后重试。")
                    else -> UpdateCheckResult.Failure("GitHub 服务暂时不可用（HTTP $status），请稍后重试。")
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            UpdateCheckResult.Failure("连接 GitHub 超时，请检查网络后重试。")
        } catch (_: IOException) {
            UpdateCheckResult.Failure("无法连接 GitHub，请检查网络后重试。")
        } catch (_: JSONException) {
            UpdateCheckResult.Failure("GitHub 返回的版本信息不完整，请稍后重试。")
        }
    }

    private fun parseRelease(body: String, currentVersion: String): UpdateCheckResult {
        val release = JSONObject(body)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
            return UpdateCheckResult.NoRelease
        }
        val tag = release.getString("tag_name")
        val latest = ReleaseVersion.parse(tag)
            ?: return UpdateCheckResult.Failure("无法识别 Release 版本号：$tag，请前往发布页查看。")
        val current = ReleaseVersion.parse(currentVersion)
            ?: return UpdateCheckResult.Failure("无法识别当前应用版本，请前往发布页查看。")
        if (latest <= current) return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(
            version = tag,
            notes = if (release.isNull("body")) "" else release.optString("body").trim(),
            releaseUrl = "$REPOSITORY_URL/releases/tag/${android.net.Uri.encode(tag)}"
        )
    }
}
