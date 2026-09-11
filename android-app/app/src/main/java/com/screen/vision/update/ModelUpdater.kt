package com.screen.vision.update

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型热更新管理器。
 *
 * 启动时检查远程服务器是否有新模型版本,
 * 有则下载替换本地缓存的模型文件。
 */
class ModelUpdater(private val context: Context) {

    private val modelDir: File = File(context.filesDir, "models")
    private val versionFile: File = File(modelDir, "version.txt")
    val modelFile: File get() = File(modelDir, "model.tflite")

    fun getCurrentVersion(): Int {
        return try { versionFile.readText().trim().toInt() } catch (_: Exception) { 0 }
    }

    fun getModelPath(): String {
        return if (modelFile.exists() && getCurrentVersion() > 0) modelFile.absolutePath
        else "model.tflite"  // assets 基线模型
    }

    suspend fun checkAndUpdate(
        serverUrl: String = DEFAULT_UPDATE_URL,
        onResult: (Boolean, Int) -> Unit,
    ) {
        try {
            val latest = fetchLatestModelInfo(serverUrl) ?: run { onResult(false, getCurrentVersion()); return }
            val currentVer = getCurrentVersion()
            if (latest.version <= currentVer) { onResult(true, currentVer); return }

            val ok = downloadModel(latest.url, latest.md5)
            if (ok) {
                modelDir.mkdirs()
                versionFile.writeText(latest.version.toString())
                Log.i(TAG, "Model updated to v${latest.version}")
                onResult(true, latest.version)
            } else {
                onResult(false, currentVer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update failed: ${e.message}")
            onResult(false, getCurrentVersion())
        }
    }

    private data class ModelInfo(val version: Int, val url: String, val md5: String)

    private fun fetchLatestModelInfo(serverUrl: String): ModelInfo? {
        return try {
            val conn = URL(serverUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val v = extractJsonInt(json, "version") ?: return null
            val u = extractJsonStr(json, "url") ?: return null
            val m = extractJsonStr(json, "md5") ?: ""
            ModelInfo(v, u, m)
        } catch (e: Exception) { Log.w(TAG, "Fetch failed: ${e.message}"); null }
    }

    private fun downloadModel(url: String, expectedMd5: String): Boolean {
        modelDir.mkdirs()
        val tmp = File(modelDir, "model_download.tmp")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 8192) }
            }
            conn.disconnect()
            tmp.renameTo(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    private fun extractJsonStr(json: String, key: String): String? {
        return "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        return "\"$key\"\\s*:\\s*(\\d+)".toRegex().find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val TAG = "ModelUpdater"
        const val DEFAULT_UPDATE_URL = "https://api.screen-vision.example.com/models/latest"
    }
}