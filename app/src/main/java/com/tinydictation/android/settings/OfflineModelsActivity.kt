package com.tinydictation.android.settings

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tinydictation.android.R
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.transcription.models.ModelManager
import kotlinx.coroutines.launch

/**
 * Activity for downloading and managing offline transcription models.
 */
class OfflineModelsActivity : AppCompatActivity() {

    private lateinit var modelManager: ModelManager

    // Vosk
    private lateinit var tvVoskEnStatus: TextView
    private lateinit var tvVoskDeStatus: TextView
    private lateinit var btnDownloadVoskEn: MaterialButton
    private lateinit var btnDownloadVoskDe: MaterialButton

    // Whisper
    private lateinit var tvWhisperTinyStatus: TextView
    private lateinit var tvWhisperBaseStatus: TextView
    private lateinit var tvWhisperSmallStatus: TextView
    private lateinit var btnDownloadWhisperTiny: MaterialButton
    private lateinit var btnDownloadWhisperBase: MaterialButton
    private lateinit var btnDownloadWhisperSmall: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_models)

        modelManager = ModelManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        updateModelStatus()
        setupClickListeners()
    }

    private fun initViews() {
        tvVoskEnStatus = findViewById(R.id.tvVoskEnStatus)
        tvVoskDeStatus = findViewById(R.id.tvVoskDeStatus)
        btnDownloadVoskEn = findViewById(R.id.btnDownloadVoskEn)
        btnDownloadVoskDe = findViewById(R.id.btnDownloadVoskDe)

        tvWhisperTinyStatus = findViewById(R.id.tvWhisperTinyStatus)
        tvWhisperBaseStatus = findViewById(R.id.tvWhisperBaseStatus)
        tvWhisperSmallStatus = findViewById(R.id.tvWhisperSmallStatus)
        btnDownloadWhisperTiny = findViewById(R.id.btnDownloadWhisperTiny)
        btnDownloadWhisperBase = findViewById(R.id.btnDownloadWhisperBase)
        btnDownloadWhisperSmall = findViewById(R.id.btnDownloadWhisperSmall)
    }

    private fun setupClickListeners() {
        btnDownloadVoskEn.setOnClickListener {
            handleModelButton("vosk", "en", btnDownloadVoskEn, tvVoskEnStatus)
        }
        btnDownloadVoskDe.setOnClickListener {
            handleModelButton("vosk", "de", btnDownloadVoskDe, tvVoskDeStatus)
        }
        btnDownloadWhisperTiny.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-tiny", btnDownloadWhisperTiny, tvWhisperTinyStatus)
        }
        btnDownloadWhisperBase.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-base", btnDownloadWhisperBase, tvWhisperBaseStatus)
        }
        btnDownloadWhisperSmall.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-small", btnDownloadWhisperSmall, tvWhisperSmallStatus)
        }
    }

    private fun updateModelStatus() {
        // Vosk English
        val voskEnAvailable = modelManager.isModelAvailable("vosk", "en")
        if (voskEnAvailable) {
            tvVoskEnStatus.text = "Downloaded"
            btnDownloadVoskEn.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["en"]
            tvVoskEnStatus.text = "Not downloaded (${info?.sizeMB ?: 41} MB)"
            btnDownloadVoskEn.text = "Download"
        }

        // Vosk German
        val voskDeAvailable = modelManager.isModelAvailable("vosk", "de")
        if (voskDeAvailable) {
            tvVoskDeStatus.text = "Downloaded"
            btnDownloadVoskDe.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["de"]
            tvVoskDeStatus.text = "Not downloaded (${info?.sizeMB ?: 45} MB)"
            btnDownloadVoskDe.text = "Download"
        }

        // Whisper Tiny
        val whisperTinyAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")
        if (whisperTinyAvailable) {
            tvWhisperTinyStatus.text = "Downloaded"
            btnDownloadWhisperTiny.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-tiny"]
            tvWhisperTinyStatus.text = "Not downloaded (${info?.sizeMB ?: 75} MB)"
            btnDownloadWhisperTiny.text = "Download"
        }

        // Whisper Base
        val whisperBaseAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-base")
        if (whisperBaseAvailable) {
            tvWhisperBaseStatus.text = "Downloaded"
            btnDownloadWhisperBase.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-base"]
            tvWhisperBaseStatus.text = "Not downloaded (${info?.sizeMB ?: 150} MB)"
            btnDownloadWhisperBase.text = "Download"
        }

        // Whisper Small
        val whisperSmallAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-small")
        if (whisperSmallAvailable) {
            tvWhisperSmallStatus.text = "Downloaded"
            btnDownloadWhisperSmall.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-small"]
            tvWhisperSmallStatus.text = "Not downloaded (${info?.sizeMB ?: 500} MB)"
            btnDownloadWhisperSmall.text = "Download"
        }
    }

    private fun handleModelButton(engineId: String, modelId: String, button: MaterialButton, statusView: TextView) {
        val isAvailable = modelManager.isModelAvailable(engineId, modelId)

        if (isAvailable) {
            // Delete model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Deleting..."

                val result = modelManager.deleteModel(engineId, modelId)
                if (result.isSuccess) {
                    Toast.makeText(this@OfflineModelsActivity, "Model deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@OfflineModelsActivity, "Failed to delete model", Toast.LENGTH_SHORT).show()
                }

                updateModelStatus()
                button.isEnabled = true
            }
        } else {
            // Download model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Downloading..."
                statusView.text = "Downloading..."

                val result = modelManager.downloadModel(engineId, modelId)
                if (result.isSuccess) {
                    Toast.makeText(this@OfflineModelsActivity, "Model downloaded!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@OfflineModelsActivity, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }

                updateModelStatus()
                button.isEnabled = true
            }
        }
    }
}
