package com.tinydictation.android.transcription.models

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TinyDictation"

        val SHERPA_MODEL_INFO = mapOf(
            "whisper-tiny" to ModelInfo(
                name = "Whisper Tiny (Fast)",
                sizeMB = 75,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
            ),
            "whisper-base" to ModelInfo(
                name = "Whisper Base (Balanced)",
                sizeMB = 150,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
            ),
            "whisper-small" to ModelInfo(
                name = "Whisper Small (Accurate)",
                sizeMB = 500,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
            )
        )

        data class ModelInfo(
            val name: String,
            val sizeMB: Int,
            val url: String
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadProgress = mutableMapOf<String, MutableStateFlow<Float?>>()

    fun isModelAvailable(engineId: String, language: String): Boolean {
        return when (engineId) {
            "vosk" -> VoskEngine.isModelDownloaded(context, language)
            "sherpa-onnx" -> isSherpaModelDownloaded(language)
            else -> false
        }
    }

    fun getDownloadProgress(engineId: String, language: String): Flow<Float?> {
        val key = "$engineId-$language"
        return downloadProgress.getOrPut(key) { MutableStateFlow(null) }
    }

    suspend fun downloadModel(engineId: String, language: String): Result<Unit> {
        return when (engineId) {
            "vosk" -> downloadVoskModel(language)
            "sherpa-onnx" -> downloadSherpaModel(language)
            else -> Result.failure(IllegalArgumentException("Unknown engine: $engineId"))
        }
    }

    suspend fun deleteModel(engineId: String, language: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val modelDir = when (engineId) {
                    "vosk" -> VoskEngine.getModelDir(context, language)
                    "sherpa-onnx" -> getSherpaModelDir(language)
                    else -> return@withContext Result.failure(IllegalArgumentException("Unknown engine"))
                }

                if (modelDir.exists()) {
                    modelDir.deleteRecursively()
                    Log.i(TAG, "ModelManager: Deleted model $engineId/$language")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "ModelManager: Failed to delete model", e)
                Result.failure(e)
            }
        }
    }

    fun getModelInfo(engineId: String, language: String): ModelInfo? {
        return when (engineId) {
            "vosk" -> VoskEngine.MODEL_INFO[language]?.let {
                ModelInfo(it.name, it.sizeMB, it.url)
            }
            "sherpa-onnx" -> SHERPA_MODEL_INFO[language]
            else -> null
        }
    }

    private suspend fun downloadVoskModel(language: String): Result<Unit> {
        val modelInfo = VoskEngine.MODEL_INFO[language]
            ?: return Result.failure(IllegalArgumentException("No model for language: $language"))

        val modelDir = VoskEngine.getModelDir(context, language)
        return downloadAndExtractZip(
            url = modelInfo.url,
            targetDir = modelDir,
            progressKey = "vosk-$language"
        )
    }

    private suspend fun downloadSherpaModel(language: String): Result<Unit> {
        val modelInfo = SHERPA_MODEL_INFO[language]
            ?: return Result.failure(IllegalArgumentException("No model for language: $language"))

        val modelDir = getSherpaModelDir(language)
        return downloadAndExtractTarBz2(
            url = modelInfo.url,
            targetDir = modelDir,
            progressKey = "sherpa-onnx-$language"
        )
    }

    private suspend fun downloadAndExtractTarBz2(
        url: String,
        targetDir: File,
        progressKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val progressFlow = downloadProgress.getOrPut(progressKey) { MutableStateFlow(null) }

        try {
            Log.i(TAG, "ModelManager: Starting download from $url")
            DebugLog.i("ModelManager: Downloading model from $url")
            progressFlow.value = 0f

            targetDir.parentFile?.mkdirs()
            val tempFile = File(context.cacheDir, "model_download_$progressKey.tar.bz2")

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: ${response.code}"))
            }

            val contentLength = response.body?.contentLength() ?: -1
            var downloadedBytes = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (contentLength > 0) {
                            progressFlow.value = downloadedBytes.toFloat() / contentLength * 0.8f
                        }
                    }
                }
            }

            Log.i(TAG, "ModelManager: Download complete, extracting tar.bz2...")
            progressFlow.value = 0.8f

            ArchiveExtractor.extractTarBz2(tempFile, targetDir)
            progressFlow.value = 1f

            tempFile.delete()

            Log.i(TAG, "ModelManager: Model ready at ${targetDir.absolutePath}")
            DebugLog.i("ModelManager: Model downloaded and extracted")

            progressFlow.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "ModelManager: Download failed", e)
            DebugLog.e("ModelManager: Download failed - ${e.message}")
            progressFlow.value = null
            Result.failure(e)
        }
    }

    private suspend fun downloadAndExtractZip(
        url: String,
        targetDir: File,
        progressKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val progressFlow = downloadProgress.getOrPut(progressKey) { MutableStateFlow(null) }

        try {
            Log.i(TAG, "ModelManager: Starting download from $url")
            DebugLog.i("ModelManager: Downloading model from $url")
            progressFlow.value = 0f

            targetDir.parentFile?.mkdirs()
            val tempZip = File(context.cacheDir, "model_download_$progressKey.zip")

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: ${response.code}"))
            }

            val contentLength = response.body?.contentLength() ?: -1
            var downloadedBytes = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (contentLength > 0) {
                            progressFlow.value = downloadedBytes.toFloat() / contentLength * 0.8f
                        }
                    }
                }
            }

            Log.i(TAG, "ModelManager: Download complete, extracting...")
            progressFlow.value = 0.8f

            ArchiveExtractor.extractZip(tempZip, targetDir)
            progressFlow.value = 1f

            tempZip.delete()

            Log.i(TAG, "ModelManager: Model ready at ${targetDir.absolutePath}")
            DebugLog.i("ModelManager: Model downloaded and extracted")

            progressFlow.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "ModelManager: Download failed", e)
            DebugLog.e("ModelManager: Download failed - ${e.message}")
            progressFlow.value = null
            Result.failure(e)
        }
    }

    private fun isSherpaModelDownloaded(language: String): Boolean {
        val modelDir = getSherpaModelDir(language)
        return modelDir.exists() &&
               (File(modelDir, "encoder.onnx").exists() ||
                File(modelDir, "model.onnx").exists() ||
                modelDir.listFiles()?.any { it.extension == "onnx" } == true)
    }

    private fun getSherpaModelDir(language: String): File {
        return File(context.filesDir, "sherpa_models/$language")
    }
}
