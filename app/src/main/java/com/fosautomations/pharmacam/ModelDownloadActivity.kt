package com.fosautomations.pharmacam

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fosautomations.pharmacam.databinding.ActivityDownloadBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private val MODEL_URL = "https://your-server.com/gemma3n.task"
    private val EMBEDDER_URL = "https://storage.googleapis.com/mediapipe-tasks/text_embedder/universal_sentence_encoder.tflite"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val modelFile = File(getExternalFilesDir(null), "gemma3n.task")
        val embedderFile = File(getExternalFilesDir(null), "universal_sentence_encoder.tflite")

        val modelOk = modelFile.exists() && modelFile.length() > 100 * 1024 * 1024
        val embedderOk = embedderFile.exists() && embedderFile.length() > 5 * 1024 * 1024

        if (modelOk && embedderOk) {
            startMainActivity()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!modelOk) {
                    withContext(Dispatchers.Main) {
                        binding.tvMessage.text = "Downloading Gemma 3n on-device AI model (~1.3 GB)…"
                    }
                    downloadFile(MODEL_URL, modelFile)
                }

                if (!embedderOk) {
                    withContext(Dispatchers.Main) {
                        binding.tvMessage.text = "Downloading Text Embedder model (~30 MB)…"
                    }
                    downloadFile(EMBEDDER_URL, embedderFile)
                }

                withContext(Dispatchers.Main) {
                    startMainActivity()
                }

            } catch (e: Exception) {
                Log.e("GemmaDownload", "Download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvMessage.text = "Download failed: ${e.localizedMessage ?: e.message}\nPlease restart the app to retry."
                    binding.progressBar.progress = 0
                    binding.tvProgress.text = "Error"
                }
            }
        }
    }

    private suspend fun downloadFile(urlString: String, destination: File) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Server returned HTTP $responseCode")
        }

        val totalBytes = connection.contentLength.toLong()
        val input = connection.inputStream
        
        destination.parentFile?.mkdirs()
        val output = FileOutputStream(destination)

        val buffer = ByteArray(8192)
        var downloaded = 0L
        var bytes: Int
        var lastProgressUpdate = 0L

        try {
            while (input.read(buffer).also { bytes = it } != -1) {
                output.write(buffer, 0, bytes)
                downloaded += bytes
                
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100 || downloaded == totalBytes) {
                    val progress = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else 0
                    lastProgressUpdate = now
                    withContext(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.tvProgress.text = "$progress%"
                    }
                }
            }
        } finally {
            output.close()
            input.close()
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
