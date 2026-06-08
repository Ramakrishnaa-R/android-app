package com.fosautomations.pharmacam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fosautomations.pharmacam.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import java.util.Date
import java.util.Locale
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private var serverIp = ""
    private var isLocked = false
    private var isMatching = false
    private var lastScanTime = 0L
    private var scanStartTime = 0L
    private var lastBlurStatus = false
    private var lastGlareStatus = false

    private var isCapturing = false

    private val matchCounts = mutableMapOf<String, Int>()

    private val blacklist = mutableSetOf<String>()
    private val confirmedMedicines = mutableListOf<Medicine>()
    private val learningMemory = mutableMapOf<String, String>()

    private var currentMatch: Medicine? = null
    private var currentDetectedQuantity: Int? = null
    private var currentPillCount: Int? = null
    private var currentVisiblePackQuantity: Int? = null
    private var latestScanText: String? = null
    private var lastOcrRaw: String? = null
    private var lastSearchQuery: String? = null
    private var lastNormalizationStatus: String? = null

    private val ocrSemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private lateinit var cameraExecutor: ExecutorService
    private val matchingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var beep: ToneGenerator? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var cameraInstance: androidx.camera.core.Camera? = null

    private var imageCapture: ImageCapture? = null

    private var isFlashOn = false

    private var pillDetector: PillDetector? = null
    private var visionFinder: VisionMedicineFinder? = null
    private lateinit var confirmedAdapter: ConfirmedMedicineAdapter
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false
    private var voiceAnimator: android.animation.AnimatorSet? = null
    private val TAG = "TOM_DEBUG"
    private val sampleFileName = "sample.txt"

    companion object {
        /** OCR text from [ImageProcessingActivity] (Rhohit pipeline on 1:1 crop). */
        var pendingOcrResult: String? = null
        /** Parallel 3:1 wide-crop OCR while the processing screen is open. */
        var pendingWideOcrText: String? = null
        private const val REQUEST_IMAGE_PROCESSING = 1201
        // Pre-compiled once at class load — never recompiled per-call
        private val UNIT_QTY_REGEX = Regex(
            """\b(\d{1,4})\s*(ML|M L|GM|GMS|GRAM|G|TAB|TABS|TABLET|TABLETS|CAP|CAPS|CAPSULE|CAPSULES|SYP|SUSP|LOTION|CREAM)\b"""
        )
        private val STRIP_COUNT_REGEX   = Regex("""\b(\d{1,3})\s*'?S\b""")
        private val SAMPLE_CLEAN_REGEX  = Regex("[^A-Z0-9 ]")
        private val SAMPLE_SPACES_REGEX = Regex("\\s+")
        private val SAMPLE_ALPHA_REGEX  = Regex("[^A-Z]")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: App starting")
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, 0)
                insets
            }

            setupUI()
            setupData()
            ScanDebugImageSaver.logWhereToFind(this)
            binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            requestRequiredPermissions()
            startAutoReconnect()
            startScanAnimation()
        } catch (e: Exception) {
            Log.e(TAG, "Critical Crash in onCreate", e)
            finish()
        }
    }

    private fun requestRequiredPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "requestRequiredPermissions: Requesting $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            var cameraGranted = false
            for (i in permissions.indices) {
                if (permissions[i] == Manifest.permission.CAMERA &&
                    grantResults[i] == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraGranted = true
                }
            }
            Log.d(TAG, "Permissions Result: Camera=$cameraGranted")
            if (cameraGranted) startCamera()
            else Toast.makeText(this, "Camera is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        val configPrefs = getSharedPreferences("config", MODE_PRIVATE)
        serverIp = configPrefs.getString("server_ip", "") ?: ""
        binding.ipInput.setText(serverIp)

        binding.connectBtn.setOnClickListener {
            val ip = binding.ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                serverIp = ip
                configPrefs.edit { putString("server_ip", ip) }
                checkHealth(ip)
            } else scanNetwork()
        }

        binding.btnFlash.setOnClickListener {
            cameraInstance?.let { cam ->
                isFlashOn = !isFlashOn
                cam.cameraControl.enableTorch(isFlashOn)
                it.alpha = if (isFlashOn) 1.0f else 0.5f
            }
        }

        binding.resetBtn.setOnClickListener {
            resetState()
            confirmedMedicines.clear()
            confirmedAdapter.notifyDataSetChanged()
            updateConfirmedVisibility()
        }

        binding.confirmBtn.setOnClickListener { handleConfirmClick() }

        binding.captureModeBtn.setOnClickListener {
            startActivity(Intent(this, MedicineDbActivity::class.java))
        }

        binding.btnShutter.setOnClickListener {
            requestShutterCapture()
        }
        confirmedAdapter = ConfirmedMedicineAdapter(confirmedMedicines) { position ->
            if (position in confirmedMedicines.indices) {
                confirmedMedicines.removeAt(position)
                confirmedAdapter.notifyItemRemoved(position)
                updateConfirmedVisibility()
                if (confirmedMedicines.isEmpty()) binding.confirmBtn.isEnabled = false
            }
        }
        binding.confirmedRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.confirmedRecyclerView.adapter = confirmedAdapter
    }

    // =======================================================================
    // SHUTTER: capture a single high-quality frame and run the OCR pipeline
    //
    // Key changes vs original:
    //  1. Y-plane quality check fires BEFORE any Bitmap is allocated
    //  2. imageProxy.toBitmap() replaces toDetectorBitmap() — eliminates the
    //     NV21 → JPEG-encode → JPEG-decode roundtrip (was 3 extra allocs)
    //  3. Single rotation pass (original double-rotated: toDetectorBitmap
    //     already rotated, then the let-block rotated again)
    //  4. LabelOcrHelper 1:1 + 3:1 center crops → prepareForOcr → line-scored text
    // =======================================================================
    /** Focus briefly, then take the still — improves sharpness on real devices. */
    private fun requestShutterCapture() {
        if (isCapturing || isMatching || isListening) return
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "SHUTTER: tap (preview blur=$lastBlurStatus glare=$lastGlareStatus)")
        clearActiveMatchUiForNewScan()
        binding.btnShutter.isEnabled = false
        binding.statusText.text = "Focusing…"
        binding.statusText.setTextColor("#FF9800".toColorInt())
        triggerCaptureFocus()
        mainHandler.postDelayed({ performShutterCapture() }, 550)
    }

    private fun performShutterCapture() {
        val capture = imageCapture ?: run {
            binding.btnShutter.isEnabled = true
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (isCapturing || isMatching || isListening) {
            binding.btnShutter.isEnabled = true
            return
        }

        Log.d(TAG, "SHUTTER: taking picture")
        isCapturing = true
        binding.scanBox.setBackgroundColor("#4D4CAF50".toColorInt())
        binding.statusText.text = "Capturing…"
        binding.statusText.setTextColor("#FF9800".toColorInt())
        binding.btnShutter.isEnabled = false

        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()
        val boxLeft = binding.scanBox.left.toFloat()
        val boxTop = binding.scanBox.top.toFloat()
        val boxRight = binding.scanBox.right.toFloat()
        val boxBottom = binding.scanBox.bottom.toFloat()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        Log.d(TAG, "SHUTTER: capture success")

                        val glare = imageProxy.hasExcessiveGlareYPlane()
                        val blur = imageProxy.isBlurryYPlane()
                        val blurScore = imageProxy.blurScoreYPlane()
                        Log.d(
                            TAG,
                            "SHUTTER: quality glare=$glare blur=$blur blurScore=$blurScore " +
                                "(still running OCR)"
                        )
                        if (glare || blur) {
                            runOnUiThread {
                                binding.statusText.text = when {
                                    glare -> "Glare detected — OCR will try anyway"
                                    else -> "Blur detected — OCR will try anyway"
                                }
                                binding.statusText.setTextColor("#FF9800".toColorInt())
                            }
                        }

                        // --- Single Bitmap conversion, single rotation pass ---
                        val bitmap = imageProxy.toBitmap().let { bmp ->
                            val deg = imageProxy.imageInfo.rotationDegrees
                            if (deg == 0) bmp
                            else {
                                val matrix = android.graphics.Matrix()
                                matrix.postRotate(deg.toFloat())
                                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                    .also { bmp.recycle() }
                            }
                        }

                        scanStartTime = System.currentTimeMillis()
                        val debugSaves = mutableListOf<ScanDebugImageSaver.SavedCapture>()

                        // Projection crop matching the green scanBox
                        val bmpWidth = bitmap.width.toFloat()
                        val bmpHeight = bitmap.height.toFloat()
                        val scale: Float
                        val dx: Float
                        val dy: Float
                        val viewRatio = viewWidth / viewHeight
                        val bmpRatio = bmpWidth / bmpHeight

                        if (bmpRatio > viewRatio) {
                            scale = viewHeight / bmpHeight
                            val scaledWidth = bmpWidth * scale
                            dx = (scaledWidth - viewWidth) / 2f
                            dy = 0f
                        } else {
                            scale = viewWidth / bmpWidth
                            val scaledHeight = bmpHeight * scale
                            dx = 0f
                            dy = (scaledHeight - viewHeight) / 2f
                        }

                        val leftBmp = ((boxLeft + dx) / scale).toInt().coerceIn(0, bitmap.width)
                        val topBmp = ((boxTop + dy) / scale).toInt().coerceIn(0, bitmap.height)
                        val rightBmp = ((boxRight + dx) / scale).toInt().coerceIn(0, bitmap.width)
                        val bottomBmp = ((boxBottom + dy) / scale).toInt().coerceIn(0, bitmap.height)

                        val cropW = (rightBmp - leftBmp).coerceAtLeast(1)
                        val cropH = (bottomBmp - topBmp).coerceAtLeast(1)

                        val projectionCrop = Bitmap.createBitmap(bitmap, leftBmp, topBmp, cropW, cropH)
                        // projectionCrop IS the scan zone — no second crop needed.
                        val crop3x1ForDebug = projectionCrop.copy(Bitmap.Config.ARGB_8888, false)
                        BitmapHolder.bitmap = crop3x1ForDebug
                        BitmapHolder.wideBitmap = null
                        Log.d(
                            TAG,
                            "OCR scan zone crop: ${projectionCrop.width}x${projectionCrop.height}"
                        )

                        if (ScanDebugImageSaver.ENABLED) {
                            matchingScope.launch(Dispatchers.IO) {
                                ScanDebugImageSaver.saveCapture(
                                    this@MainActivity,
                                    crop3x1ForDebug,
                                    "crop_3x1"
                                )?.let { debugSaves.add(it) }
                                withContext(Dispatchers.Main) {
                                    if (debugSaves.isNotEmpty()) {
                                        ScanDebugImageSaver.showSavedToast(
                                            this@MainActivity,
                                            debugSaves
                                        )
                                    }
                                }
                            }
                        }

                        bitmap.recycle()

                        pendingWideOcrText = null
                        pendingOcrResult = null

                        matchingScope.launch {
                            var statusJob: kotlinx.coroutines.Job? = null
                            try {
                                statusJob = launch {
                                    val messages = listOf(
                                        "Analyzing image with Gemma 3n on-device AI…",
                                        "Gemma 3n: Initializing multimodal session…",
                                        "Gemma 3n: Converting image & processing vision tokens…",
                                        "Gemma 3n: Running on-device neural engine inference…",
                                        "Gemma 3n: Generating brand & strength tokens…",
                                        "Gemma 3n: Finalizing detected text sequence…",
                                        "Matcher: Initializing database lookup…"
                                    )
                                    var index = 0
                                    while (isActive) {
                                        binding.statusText.text = messages[index]
                                        binding.statusText.setTextColor("#FF9800".toColorInt())
                                        index = (index + 1) % messages.size
                                        delay(8000)
                                    }
                                }

                                val medicineName = visionFinder?.extractMedicineName(projectionCrop)
                                statusJob?.cancel()

                                withContext(Dispatchers.Main) {
                                    finishCapture()
                                    startActivityForResult(
                                        Intent(
                                            this@MainActivity,
                                            ImageProcessingActivity::class.java
                                        ).apply {
                                            putExtra(
                                                ImageProcessingActivity.EXTRA_PRIMARY_OCR,
                                                medicineName.orEmpty()
                                            )
                                            putExtra(
                                                ImageProcessingActivity.EXTRA_WIDE_OCR,
                                                ""
                                            )
                                            putStringArrayListExtra(
                                                ImageProcessingActivity.EXTRA_BLACKLIST,
                                                ArrayList(blacklist)
                                            )
                                        },
                                        REQUEST_IMAGE_PROCESSING
                                    )
                                }
                            } catch (e: Exception) {
                                statusJob?.cancel()
                                Log.e("Gemma", "Gemma inference failed", e)
                                withContext(Dispatchers.Main) {
                                    finishCapture()
                                    binding.statusText.text = "Not found — try again"
                                    binding.statusText.setTextColor("#F44336".toColorInt())
                                }
                            }
                        }

                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "SHUTTER capture failed", exception)
                    runOnUiThread {
                        finishCapture()
                        Toast.makeText(
                            this@MainActivity,
                            "Capture failed: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
    private fun finishCapture() {
        isCapturing = false
        binding.btnShutter.isEnabled = true
        binding.scanBox.setBackgroundResource(R.drawable.scan_box_border)
    }

    private fun clearActiveMatchUiForNewScan() {
        currentMatch = null
        latestScanText = null
        isLocked = false
        binding.confirmBtn.isEnabled = false
        binding.top3Choices.removeAllViews()
        binding.top3Container.visibility = View.GONE
    }

    private fun triggerCaptureFocus() {
        try {
            val w = binding.previewView.width.toFloat().coerceAtLeast(1f)
            val h = binding.previewView.height.toFloat().coerceAtLeast(1f)
            val factory = SurfaceOrientedMeteringPointFactory(w, h)
            val point = factory.createPoint(w / 2f, h / 2f)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
            cameraInstance?.cameraControl?.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "SHUTTER: focus trigger failed", e)
        }
    }

    /**
     * OCR on square (1:1) + wide (3:1) crops; each with enhance + optional binarized pass.
     */
    private fun runLabelOcrFromCapture(
        fullBitmap: Bitmap,
        fromShutter: Boolean,
        debugSaves: MutableList<ScanDebugImageSaver.SavedCapture> = mutableListOf(),
        onFinished: (() -> Unit)? = null
    ) {
        matchingScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    binding.loader.visibility = View.VISIBLE
                    binding.statusText.text = "Cropping center wide zone…"
                    binding.statusText.setTextColor("#FF9800".toColorInt())
                }

                val crop3x1 = LabelOcrHelper.cropCenterWide3x1(fullBitmap)
                if (ScanDebugImageSaver.ENABLED) {
                    withContext(Dispatchers.IO) {
                        ScanDebugImageSaver.saveCapture(
                            this@MainActivity,
                            crop3x1,
                            "crop_3x1"
                        )?.let { debugSaves.add(it) }
                    }
                }
                fullBitmap.recycle()

                Log.d(
                    TAG,
                    "OCR crop 3x1: ${crop3x1.width}x${crop3x1.height}"
                )

                val consensus = processMultiFilterCrops(crop3x1)

                val ocrResult = consensus.ocrResult
                pendingWideOcrText = ocrResult.fullText.ifBlank { ocrResult.matchText }
                val wideText = pendingWideOcrText.orEmpty()

                BitmapHolder.winningFilter = consensus.filterName

                withContext(Dispatchers.Main) {
                    binding.loader.visibility = View.GONE
                    if (fromShutter && debugSaves.isNotEmpty()) {
                        ScanDebugImageSaver.showSavedToast(this@MainActivity, debugSaves)
                    }

                    if (fromShutter) finishCapture()

                    val ocrForMatch = ocrResult.fullText.ifBlank { ocrResult.matchText }
                    if (ocrForMatch.length >= 3) {
                        binding.resultTextView.text = getString(R.string.matching_medicine)
                        binding.resultTextView.setTextColor("#FF9800".toColorInt())

                        if (consensus.resolved != null) {
                            processScanResult(
                                resolved = consensus.resolved,
                                primaryOcr = ocrForMatch,
                                hintOcr = wideText.takeIf { it.length >= 3 }
                            )
                        } else {
                            processScanResult(
                                primaryOcr = ocrForMatch,
                                hintOcr = wideText.takeIf { it.length >= 3 }
                            )
                        }
                    } else {
                        binding.statusText.text = "Nothing readable — move closer & tap camera"
                        binding.statusText.setTextColor("#FF9800".toColorInt())
                        Toast.makeText(
                            this@MainActivity,
                            "Could not read text — hold steady and tap green camera",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: CancellationException) {
                // TimeoutCancellationException from withTimeout — must catch explicitly
                Log.w(TAG, "OCR pipeline timed out")
                withContext(Dispatchers.Main) {
                    binding.loader.visibility = View.GONE
                    if (fromShutter) finishCapture()
                    binding.statusText.text = "Scan timed out — tap again"
                    binding.statusText.setTextColor("#FF9800".toColorInt())
                    Toast.makeText(
                        this@MainActivity,
                        "Scan took too long — tap again",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR pipeline failed", e)
                withContext(Dispatchers.Main) {
                    binding.loader.visibility = View.GONE
                    if (fromShutter) finishCapture()
                    binding.statusText.text = "OCR failed"
                    binding.statusText.setTextColor("#F44336".toColorInt())
                    Toast.makeText(
                        this@MainActivity,
                        "OCR failed \u2014 tap green camera again",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private suspend fun processMultiFilterCrops(
        crop: Bitmap
    ): SingleFilterConsensusResult = withContext(Dispatchers.Default) {
        // 180-second hard cap (3 minutes) — ensures we never timeout under normal usage.
        withTimeout(180_000L) {
            withContext(Dispatchers.Main) {
                binding.statusText.text = "Running OCR pipeline…"
            }

            val filterResult = recognizeSingleFilter(crop)

            BitmapHolder.filterOcrTexts = mapOf("bp-sat" to (filterResult.ocrResult.fullText.ifBlank { filterResult.ocrResult.matchText }))
            BitmapHolder.wideFilterOcrTexts = emptyMap()

            // Single filter — no consensus needed, just return directly
            SingleFilterConsensusResult(
                filterName = filterResult.filterName,
                ocrResult  = filterResult.ocrResult,
                resolved   = filterResult.resolved
            )
        }
    }

    /**
     * Single-filter OCR pipeline — Black Point 70 + Saturation 75%.
     *
     * Saturation 75% (not 100%) avoids the "rainbow explosion" that full
     * saturation causes on gold/holographic foil packages while still making
     * coloured text (red, blue) pop clearly against metallic backgrounds.
     *
     * Black point 70 crushes dark shadow noise to black while preserving the
     * mid-tone text that 82 was clipping too aggressively.
     */
    private suspend fun recognizeSingleFilter(
        crop: Bitmap
    ): FilterResult = withContext(Dispatchers.Default) {
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Enhancing image contrast…"
        }
        // Apply filter on the ORIGINAL colour crop so hue info is preserved
        val colourUpscaled = LabelOcrHelper.upscaleIfNeeded(crop.copy(Bitmap.Config.ARGB_8888, false))
        val filtered = ImageUtils.applyBlackPointSaturation(colourUpscaled, blackPoint = 70, saturation = 0.75f)
        colourUpscaled.recycle()

        BitmapHolder.filteredBitmap = filtered.copy(Bitmap.Config.ARGB_8888, false)

        withContext(Dispatchers.Main) {
            binding.statusText.text = "ML Kit OCR text recognition…"
        }
        val ocr = recognizePreparedSafe(filtered)
        filtered.recycle()

        val text = ocr.fullText.ifBlank { ocr.matchText }
        val correctedText = NumericOcrCorrector.correct(text)
        
        withContext(Dispatchers.Main) {
            binding.statusText.text = "Resolving name in database…"
        }
        val resolved = if (correctedText.length >= 3) {
            MedicineNameResolver.resolve(correctedText, blacklist)
        } else null

        // Save OCR text for debug activity display
        BitmapHolder.filterOcrTexts = mapOf(
            "bp-sat" to text
        )

        FilterResult("bp-sat", ocr, resolved)
    }

    /** Calls ML Kit with an 8-second safety timeout; returns empty result on timeout/failure.
     *  8 s is generous enough for slow devices while still guaranteeing forward progress. */
    private suspend fun recognizePreparedSafe(bitmap: Bitmap): LabelOcrHelper.OcrResult {
        ocrSemaphore.acquire()
        return try {
            withTimeout(10_000L) { recognizePrepared(bitmap) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "OCR timed out for one filter — skipping, others will still run")
            LabelOcrHelper.OcrResult("", "", emptyList(), 0)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed for one filter: ${e.message}")
            LabelOcrHelper.OcrResult("", "", emptyList(), 0)
        } finally {
            ocrSemaphore.release()
        }
    }

    // computeConsensus removed — single filter pipeline no longer needs consensus voting.


    private suspend fun recognizePrepared(bitmap: Bitmap): LabelOcrHelper.OcrResult =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(LabelOcrHelper.extractBestText(visionText))
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private data class FilterResult(
        val filterName: String,
        val ocrResult: LabelOcrHelper.OcrResult,
        val resolved: MedicineNameResolver.Resolved?
    )

    private class SingleFilterConsensusResult(
        val filterName: String,
        val ocrResult: LabelOcrHelper.OcrResult,
        val resolved: MedicineNameResolver.Resolved?
    )


    private fun deliverOcrResult(
        result: LabelOcrHelper.OcrResult,
        wideOcr: String?,
        fromShutter: Boolean
    ) {
        Log.d(TAG, "OCR line: ${result.matchText}")
        Log.d(TAG, "OCR full: ${result.fullText}")
        Log.d(TAG, "OCR wide: ${wideOcr.orEmpty()}")

        if (fromShutter) finishCapture()

        val ocrForMatch = result.fullText.ifBlank { result.matchText }
        if (ocrForMatch.length >= 3) {
            binding.resultTextView.text = getString(R.string.matching_medicine)
            binding.resultTextView.setTextColor("#FF9800".toColorInt())
            processScanResult(
                primaryOcr = ocrForMatch,
                hintOcr = wideOcr?.takeIf { it.length >= 3 }
            )
        } else {
            binding.statusText.text = "Nothing readable — move closer & tap camera"
            binding.statusText.setTextColor("#FF9800".toColorInt())
            Toast.makeText(
                this,
                "Could not read text — hold steady and tap green camera",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupData() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            beep = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: Exception) {}

        matchingScope.launch(Dispatchers.IO) {
            pillDetector = PillDetector(this@MainActivity)
            try {
                visionFinder = VisionMedicineFinder(this@MainActivity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize VisionMedicineFinder", e)
            }
        }
        matchingScope.launch {
            MedicineRepository.loadIfNeeded(this@MainActivity)
        }
    }

    private fun startCamera() {
        if (isListening) return
        Log.d(TAG, "startCamera: Re-initializing")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val selectors = listOf(
                    "back"  to CameraSelector.DEFAULT_BACK_CAMERA,
                    "front" to CameraSelector.DEFAULT_FRONT_CAMERA
                )

                var bound = false
                var lastError: Exception? = null

                for ((label, selector) in selectors) {
                    try {
                        if (!cameraProvider.hasCamera(selector)) {
                            Log.w(TAG, "startCamera: $label camera not available")
                            continue
                        }

                        val preview = Preview.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(binding.previewView.display.rotation)
                            .build()
                            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(binding.previewView.display.rotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            try {
                                val now = System.currentTimeMillis()

                                if (!isLocked && !isMatching && !isListening && !isCapturing &&
                                    isQuantityEnabled() && (now - lastScanTime > 700)
                                ) {
                                    pillDetector?.let { detector ->
                                        val bitmap = imageProxy.toDetectorBitmap().centerCrop(
                                            widthPercent = 0.60f,
                                            heightPercent = 0.22f
                                        )
                                        val pillCount = detector.detectPills(bitmap)
                                        bitmap.recycle()
                                        if (pillCount > 0) {
                                            runOnUiThread {
                                                currentPillCount = pillCount
                                                if (currentMatch?.isPillLike() != false) {
                                                    currentDetectedQuantity = pillCount
                                                }
                                                binding.statusText.text = "PILLS FOUND: $pillCount"
                                                binding.statusText.setTextColor("#4CAF50".toColorInt())
                                            }
                                        }
                                    }
                                }

                                if (isLocked || isMatching || isListening || isCapturing ||
                                    currentMatch != null || (now - lastScanTime < 700)
                                ) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                lastScanTime = now

                                // Live quality feedback — no OCR from live stream
                                runQualityFeedback(imageProxy)

                            } catch (e: Exception) {
                                Log.e(TAG, "Analyzer error", e)
                                imageProxy.close()
                            }
                        }

                        val imageCaptureUseCase = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(binding.previewView.display.rotation)
                            .build()

                        imageCapture = imageCaptureUseCase

                        cameraInstance = cameraProvider.bindToLifecycle(
                            this,
                            selector,
                            preview,
                            imageAnalyzer,
                            imageCaptureUseCase
                        )

                        val factory = SurfaceOrientedMeteringPointFactory(
                            binding.previewView.width.toFloat(),
                            binding.previewView.height.toFloat()
                        )
                        val point = factory.createPoint(
                            binding.previewView.width / 2f,
                            binding.previewView.height / 2f
                        )
                        val action = FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cameraInstance?.cameraControl?.startFocusAndMetering(action)

                        Log.d(TAG, "startCamera: Bound $label camera successfully")
                        bound = true
                        break

                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "startCamera: Failed to bind $label camera", e)
                        cameraProvider.unbindAll()
                    }
                }

                if (!bound) {
                    binding.resultTextView.text = "CAMERA UNAVAILABLE"
                    binding.resultTextView.setTextColor("#F44336".toColorInt())
                    Toast.makeText(this, "Camera preview failed to start", Toast.LENGTH_LONG).show()
                    lastError?.let { Log.e(TAG, "Cam Error", it) }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                binding.resultTextView.text = "CAMERA ERROR"
                binding.resultTextView.setTextColor("#F44336".toColorInt())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -----------------------------------------------------------------------
    // Quality feedback on the live preview frame.
    //
    // Replaced: toDetectorBitmap() + centerCrop() + hasExcessiveGlare(Bitmap)
    //           + isBlurry(Bitmap) = 3 allocations + NV21→JPEG→Bitmap roundtrip
    // Now:      direct Y-plane buffer sampling = zero allocations
    // -----------------------------------------------------------------------
    private fun runQualityFeedback(imageProxy: ImageProxy) {
        try {
            lastGlareStatus = imageProxy.hasExcessiveGlareYPlane()
            lastBlurStatus  = imageProxy.isBlurryYPlane()

            runOnUiThread {
                // Don't overwrite anything if we have a match or suggestions showing
                if (currentMatch != null || binding.top3Container.visibility == View.VISIBLE) {
                    return@runOnUiThread
                }

                when {
                    lastGlareStatus -> {
                        binding.statusText.text = "Glare — tilt strip (tap shutter to scan)"
                        binding.statusText.setTextColor("#F44336".toColorInt())
                    }
                    lastBlurStatus -> {
                        binding.statusText.text = "Blurry — still tap green camera"
                        binding.statusText.setTextColor("#FF9800".toColorInt())
                    }
                    else -> {
                        val wasDisabled = !binding.btnShutter.isEnabled
                        if (!isCapturing && !isMatching) {
                            binding.statusText.text = getString(R.string.ready_status)
                            binding.statusText.setTextColor("#4CAF50".toColorInt())
                            if (wasDisabled) {
                                cameraInstance?.let { camera ->
                                    val factory = SurfaceOrientedMeteringPointFactory(
                                        binding.previewView.width.toFloat(),
                                        binding.previewView.height.toFloat()
                                    )
                                    val point = factory.createPoint(
                                        binding.previewView.width / 2f,
                                        binding.previewView.height / 2f
                                    )
                                    val action = FocusMeteringAction.Builder(point)
                                        .setAutoCancelDuration(5, TimeUnit.SECONDS)
                                        .build()
                                    camera.cameraControl.startFocusAndMetering(action)
                                }
                            }
                        }
                    }
                }
                if (!isCapturing && !isMatching) {
                    binding.btnShutter.isEnabled = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runQualityFeedback error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun stopCamera() {
        Log.d(TAG, "stopCamera: Unbinding camera")
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            cameraInstance = null
            imageCapture = null
        } catch (e: Exception) {
            Log.e(TAG, "stopCamera Error", e)
        }
    }

    private fun startScanAnimation() {
        binding.scanLine.post {
            ObjectAnimator.ofFloat(
                binding.scanLine, "y",
                binding.scanBox.top.toFloat(),
                binding.scanBox.bottom.toFloat()
            ).apply {
                duration = 1500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    // Kept for potential future re-enable of live OCR.
    // All helpers it calls (toDetectorBitmap, hasExcessiveGlare, isBlurry,
    // LabelOcrHelper for OCR preprocessing and line scoring.
    @OptIn(ExperimentalGetImage::class)
    private fun processImageWithOCR(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toDetectorBitmap()
            val croppedBitmap = bitmap.centerCrop(widthPercent = 0.72f, heightPercent = 0.32f)

            lastGlareStatus = hasExcessiveGlare(croppedBitmap)
            if (lastGlareStatus) {
                runOnUiThread {
                    binding.statusText.text = "Too much glare - tilt strip"
                    binding.statusText.setTextColor("#F44336".toColorInt())
                }
                bitmap.recycle(); croppedBitmap.recycle(); imageProxy.close()
                return
            }

            lastBlurStatus = isBlurry(croppedBitmap)
            if (lastBlurStatus) {
                runOnUiThread {
                    binding.statusText.text = "Image blurry - hold steady"
                    binding.statusText.setTextColor("#FF9800".toColorInt())
                }
                bitmap.recycle(); croppedBitmap.recycle(); imageProxy.close()
                return
            }

            scanStartTime = System.currentTimeMillis()
            bitmap.recycle()
            runLabelOcrFromCapture(croppedBitmap, fromShutter = false) {
                imageProxy.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "processImageWithOCR ERROR", e)
            imageProxy.close()
        }
    }

    private fun logScanMetrics(
        ocr: String, topMatch: String, confidence: Int,
        blurry: Boolean, glare: Boolean, timeMs: Long
    ) {
        try {
            val file = File(filesDir, "scan_metrics.csv")
            if (!file.exists()) {
                file.appendText("timestamp,ocr,topMatch,confidence,blur,glare,timeMs\n")
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("\"$timestamp\",\"$ocr\",\"$topMatch\",$confidence,$blurry,$glare,$timeMs\n")
        } catch (e: Exception) {
            Log.e("CSV_LOG", "Failed to write CSV", e)
        }
    }

    /** Rhohit-style matcher debug in Logcat (MATCHER_DEBUG). */
    private fun logMatchAlternatives(searchQuery: String, matches: List<Matcher.ScoredMatch>) {
        Log.d("MATCHER_DEBUG", "Search query used: '$searchQuery'")
        matches.forEachIndexed { i, scored ->
            Log.d(
                "MATCHER_DEBUG",
                "#${i + 1}: ${scored.medicine.name} → ${scored.score.toInt()}% [${scored.explanation}]"
            )
        }
    }

    /** UI-only — does not change matching input. */
    private fun normalizationStatusLine(normalized: String, search: String, extra: String? = null): String {
        val base = "Normalized: $normalized | Search: $search"
        return if (extra.isNullOrBlank()) base else "$base | $extra"
    }

    private fun processScanResult(primaryOcr: String, hintOcr: String?) {
        if (primaryOcr.length < 3) return
        matchingScope.launch(Dispatchers.Default) {
            val correctedPrimaryOcr = NumericOcrCorrector.correct(primaryOcr)
            val correctedHintOcr = hintOcr?.let { NumericOcrCorrector.correct(it) }
            val resolved = MedicineNameResolver.resolveForScan(correctedPrimaryOcr, correctedHintOcr, blacklist, 3)
            withContext(Dispatchers.Main) {
                processScanResult(resolved, correctedPrimaryOcr, correctedHintOcr)
            }
        }
    }

    private fun processScanResult(resolved: MedicineNameResolver.Resolved, primaryOcr: String, hintOcr: String?) {
        lastOcrRaw = primaryOcr
        Log.d("MATCHER_DEBUG", "processScanResult pre-resolved primary='$primaryOcr' hint='${hintOcr?.take(60)}'")
        Log.d("MATCHER_DEBUG", Matcher.debugInput(primaryOcr))

        val normalized = Matcher.normalize(primaryOcr)
        val searchQuery = resolved.searchQuery
        val topMatch = resolved.alternatives.firstOrNull()
        val matchType = if (topMatch != null) {
            if (topMatch.explanation == "EXACT_HASHMAP_MATCH") "⚡ HashMap Exact" else "🔍 Fuzzy"
        } else {
            "No Match"
        }
        lastNormalizationStatus = normalizationStatusLine(normalized, searchQuery, "Match: $matchType")

        runOnUiThread {
            binding.statusText.text = lastNormalizationStatus
            binding.statusText.setTextColor("#9E9E9E".toColorInt())

            extractVisibleQuantity(primaryOcr)?.let { quantity ->
                currentVisiblePackQuantity = quantity
                if (isQuantityEnabled() && currentPillCount == null) {
                    currentDetectedQuantity = quantity
                }
            }
        }

        isMatching = true
        matchingScope.launch(Dispatchers.Default) {
            lastSearchQuery = resolved.searchQuery
            val scanTime = System.currentTimeMillis() - scanStartTime

            logScanMetrics(
                ocr = resolved.searchQuery.ifBlank { primaryOcr },
                topMatch = resolved.medicine?.name ?: "NONE",
                confidence = resolved.score.toInt(),
                blurry = lastBlurStatus,
                glare = lastGlareStatus,
                timeMs = scanTime
            )

            withContext(Dispatchers.Main) {
                isMatching = false
                val matches = resolved.alternatives
                if (matches.isEmpty()) {
                    Log.e(TAG, "MATCH: no medicines for query '${resolved.searchQuery}'")
                    currentMatch = null
                    isLocked = false
                    binding.confirmBtn.isEnabled = false
                    binding.top3Choices.removeAllViews()
                    binding.statusText.text = normalizationStatusLine(
                        normalized,
                        resolved.searchQuery.ifBlank { searchQuery }
                    )
                    binding.resultTextView.text = getString(R.string.no_match_instruction)
                    binding.resultTextView.setTextColor("#F44336".toColorInt())
                    binding.top3Container.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Not in your medicine list", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val top = matches.first()
                latestScanText = top.medicine.name
                logMatchAlternatives(resolved.searchQuery, matches)
                Log.d(
                    TAG,
                    "MATCH: '${resolved.searchQuery}' → ${top.medicine.name} (${top.score.toInt()}%)"
                )

                binding.statusText.text = normalizationStatusLine(
                    normalized,
                    resolved.searchQuery,
                    extra = "Found: ${top.medicine.name}"
                )
                binding.statusText.setTextColor("#4CAF50".toColorInt())

                if (MedicineNameResolver.shouldAutoPick(resolved)) {
                    binding.top3Container.visibility = View.GONE
                    onMedicineDetected(top.medicine)
                    Toast.makeText(this@MainActivity, top.medicine.name, Toast.LENGTH_SHORT).show()
                } else {
                    binding.resultTextView.text = top.medicine.name
                    binding.resultTextView.setTextColor("#4CAF50".toColorInt())
                    binding.statusText.text = normalizationStatusLine(
                        normalized,
                        resolved.searchQuery,
                        extra = getString(R.string.select_match_instruction)
                    )
                    showSuggestionsUI(matches)
                }
            }
        }
    }

    private fun processRawOutput(text: String) {
        processScanResult(text, hintOcr = null)
    }

    private fun showSuggestionsUI(matches: List<Matcher.ScoredMatch>) {
        if (isMultiModeEnabled()) {
            onMedicineDetected(matches.first().medicine)
            return
        }
        binding.top3Choices.removeAllViews()
        binding.top3Container.visibility = View.VISIBLE

        matches.forEach { scored ->
            val med = scored.medicine
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
            val nameBtn = TextView(this).apply {
                text = "${med.name.uppercase()} (${scored.score.toInt()}%)"
                textSize = 15f
                setTextColor("#4CAF50".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(16, 24, 16, 24)
            }
            val wrongBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.btn_default)
                backgroundTintList = android.content.res.ColorStateList.valueOf("#33F44336".toColorInt())
                setColorFilter("#F44336".toColorInt())
                setOnClickListener {
                    blacklist.add(med.name)
                    lastOcrRaw?.let { processRawOutput(it) }
                }
            }
            layout.setOnClickListener {
                lastOcrRaw?.let { raw ->
                    learningMemory[raw.uppercase(Locale.ROOT)] = med.name
                }
                onMedicineDetected(med)
                binding.top3Container.visibility = View.GONE
            }
            layout.addView(nameBtn)
            layout.addView(wrongBtn)
            binding.top3Choices.addView(layout)
        }

        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
    }

    private fun onMedicineDetected(med: Medicine) {
        applyBestQuantityFor(med)
        if (isMultiModeEnabled()) {
            if (!confirmedMedicines.any { it.id == med.id }) {
                confirmedMedicines.add(0, med)
                confirmedAdapter.notifyItemInserted(0)
                binding.confirmedRecyclerView.scrollToPosition(0)
                updateConfirmedVisibility()
                beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                binding.confirmBtn.isEnabled = true
            }
        } else {
            isLocked = true
            currentMatch = med
            binding.resultTextView.text = formatDetectionResult(med.name)
            binding.resultTextView.setTextColor("#4CAF50".toColorInt())
            lastNormalizationStatus?.let { line ->
                binding.statusText.text = "$line | Found: ${med.name}"
                binding.statusText.setTextColor("#4CAF50".toColorInt())
            }
            beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            vibrateFeedback(50)
            binding.confirmBtn.isEnabled = true
        }
    }

    private fun formatDetectionResult(name: String): String {
        val quantity = currentDetectedQuantity
        return if (isQuantityEnabled() && quantity != null && quantity > 0) {
            "${name.uppercase()}  |  QTY $quantity"
        } else {
            name.uppercase()
        }
    }

    private fun applyBestQuantityFor(med: Medicine) {
        if (!isQuantityEnabled()) return
        val quantity = if (med.isPillLike()) {
            currentPillCount ?: currentVisiblePackQuantity ?: extractVisibleQuantity(med.name)
        } else {
            currentVisiblePackQuantity ?: extractVisibleQuantity(med.name)
        }
        if (quantity != null && quantity > 0) {
            currentDetectedQuantity = quantity
        }
    }

    private fun isMultiModeEnabled(): Boolean = false

    private fun isQuantityEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("include_qty", true)

    // Uses pre-compiled UNIT_QTY_REGEX and STRIP_COUNT_REGEX from companion object.
    // Previously Regex(...) was constructed fresh on every call.
    private fun extractVisibleQuantity(text: String): Int? {
        val normalized = text.uppercase(Locale.ROOT)
            .replace(",", " ").replace(".", " ").replace("-", " ")

        UNIT_QTY_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..500 }
            ?.let { return it }

        STRIP_COUNT_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..200 }
            ?.let { return it }

        return null
    }

    private fun Medicine.isPillLike(): Boolean {
        val tokens = MedicineRepository.tokenize(name).toSet()
        return tokens.any {
            it in setOf("TAB", "TABS", "TABLET", "TABLETS", "CAP", "CAPS",
                "CAPSULE", "CAPSULES", "BOLUS", "SOFTGEL")
        } || name.uppercase(Locale.ROOT).contains("SOFT GEL")
    }

    private fun handleConfirmClick() {
        val action = if (getSharedPreferences("settings", MODE_PRIVATE)
                .getString("action_mode", "enter") == "tab") "tab" else "enter"
        val includeQty = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("include_qty", true)
        val list = if (isMultiModeEnabled()) confirmedMedicines.toList()
        else currentMatch?.let { listOf(it) } ?: emptyList()
        val sampleText = list.map { it.name }.joinToString(", ").ifBlank { latestScanText.orEmpty() }
        val quantity = if (includeQty) {
            currentDetectedQuantity ?: 1
        } else null

        val sampleSaveMessage = appendConfirmationToSampleFile(sampleText, quantity)
        binding.statusText.text = sampleSaveMessage

        if (serverIp.isEmpty()) {
            Toast.makeText(this, "$sampleSaveMessage. Please enter Server IP first", Toast.LENGTH_LONG).show()
            return
        }

        if (list.isNotEmpty()) {
            matchingScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    binding.syncLoader.visibility = View.VISIBLE
                    binding.buttonLayout.alpha = 0.5f
                    binding.confirmBtn.isEnabled = false
                    binding.resetBtn.isEnabled = false
                }
                val success = sendDataToServer(list.map { it.name }, includeQty, action, quantity, sampleSaveMessage)
                withContext(Dispatchers.Main) {
                    binding.syncLoader.visibility = View.GONE
                    binding.buttonLayout.alpha = 1.0f
                    binding.confirmBtn.isEnabled = true
                    binding.resetBtn.isEnabled = true
                    if (success) {
                        blacklist.clear()
                        resetState()
                        if (isMultiModeEnabled()) {
                            confirmedMedicines.clear()
                            confirmedAdapter.notifyDataSetChanged()
                            updateConfirmedVisibility()
                        }
                    }
                }
            }
        }
    }

    private fun appendConfirmationToSampleFile(itemName: String, quantity: Int?): String {
        val text    = itemName.ifBlank { "unknown" }
        val qtyText = quantity?.takeIf { it > 0 }?.toString() ?: "unknown"
        val timestamp = SimpleDateFormat("dd-MM-yy HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "text:$text , qty:$qtyText  $timestamp\n"

        val savedFiles     = mutableListOf<File>()
        val failedMessages = mutableListOf<String>()
        val targets = listOfNotNull(
            File(filesDir, sampleFileName),
            getExternalFilesDir(null)?.let { File(it, sampleFileName) }
        ).distinctBy { it.absolutePath }

        targets.forEach { sampleFile ->
            try {
                sampleFile.parentFile?.mkdirs()
                sampleFile.appendText(line)
                savedFiles.add(sampleFile)
                Log.d(TAG, "Wrote confirmation to ${sampleFile.absolutePath}")
            } catch (e: Exception) {
                failedMessages.add(e.message ?: sampleFile.absolutePath)
                Log.e(TAG, "Failed to write confirmation sample", e)
            }
        }

        return if (savedFiles.isNotEmpty()) {
            "Saved ${savedFiles.size} sample.txt"
        } else {
            val reason = failedMessages.joinToString("; ").ifBlank { "unknown error" }
            Toast.makeText(this, "Failed to write sample.txt: $reason", Toast.LENGTH_LONG).show()
            "sample.txt save failed"
        }
    }

    private suspend fun sendDataToServer(
        itemNames: List<String>, includeQty: Boolean,
        action: String, quantity: Int?, sampleSaveMessage: String
    ): Boolean {
        return try {
            val endpoint = parseServerEndpoint(serverIp)
            val json = JSONObject().apply {
                put("items", JSONArray(itemNames))
                put("quantityEnabled", includeQty)
                put("action", action)
                if (quantity != null && quantity > 0) {
                    put("quantity", quantity)
                    put("quantities", JSONArray(List(itemNames.size) { quantity }))
                }
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5000)
                socket.getOutputStream().write(json.toString().toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Sent to Desktop", Toast.LENGTH_SHORT).show()
                vibrateFeedback(100)
            }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "$sampleSaveMessage. Send Failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            false
        }
    }

    private fun resetState() {
        isLocked = false
        isMatching = false
        isCapturing = false
        currentMatch = null
        currentDetectedQuantity = null
        currentPillCount = null
        currentVisiblePackQuantity = null
        latestScanText = null
        matchCounts.clear()
        runOnUiThread {
            binding.resultTextView.text = getString(R.string.ready_status)
            binding.resultTextView.setTextColor("#E0E0E0".toColorInt())
            binding.loader.visibility = View.GONE
            binding.top3Container.visibility = View.GONE
            binding.scanBox.setBackgroundResource(R.drawable.scan_box_border)
            binding.btnShutter.isEnabled = true
            if (confirmedMedicines.isEmpty()) binding.confirmBtn.isEnabled = false
        }
    }

    private fun updateConfirmedVisibility() {
        binding.confirmedRecyclerView.visibility =
            if (isMultiModeEnabled() && confirmedMedicines.isNotEmpty()) View.VISIBLE
            else View.GONE
    }

    private fun scanNetwork() {
        matchingScope.launch(Dispatchers.IO) {
            val wifi = getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = try {
                @Suppress("DEPRECATION") wifi.connectionInfo.ipAddress
            } catch (_: Exception) { 0 }
            if (ipInt == 0) return@launch
            val subnet = String.format(
                Locale.US, "%d.%d.%d",
                (ipInt and 0xff), (ipInt shr 8 and 0xff), (ipInt shr 16 and 0xff)
            )
            for (i in 1..254) {
                val testIP = "$subnet.$i"
                try {
                    Socket().use { it.connect(InetSocketAddress(testIP, 5001), 100) }
                    withContext(Dispatchers.Main) {
                        binding.ipInput.setText(testIP)
                        serverIp = testIP
                        checkHealth(testIP)
                    }
                    return@launch
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkHealth(ip: String) {
        matchingScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val endpoint = parseServerEndpoint(ip)
                Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), 1000) }
                success = true
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                binding.statusText.text = if (success) "CONNECTED" else "DISCONNECTED"
                binding.statusText.setTextColor(
                    if (success) "#4CAF50".toColorInt() else "#F44336".toColorInt()
                )
            }
        }
    }

    private fun startAutoReconnect() {
        matchingScope.launch {
            while (isActive) {
                delay(5000)
                if (isAutoReconnectEnabled() && serverIp.isNotEmpty()) checkHealth(serverIp)
            }
        }
    }

    private fun isAutoReconnectEnabled(): Boolean =
        getSharedPreferences("config", MODE_PRIVATE).getBoolean("auto_connect", true)

    private data class ServerEndpoint(val host: String, val port: Int)

    private fun parseServerEndpoint(value: String): ServerEndpoint {
        val trimmed = value.trim()
        val colonIndex = trimmed.lastIndexOf(':')
        if (colonIndex > 0 && colonIndex < trimmed.lastIndex) {
            val host = trimmed.substring(0, colonIndex).trim()
            val port = trimmed.substring(colonIndex + 1).trim().toIntOrNull()
            if (host.isNotEmpty() && port != null) {
                return ServerEndpoint(host, port)
            }
        }
        return ServerEndpoint(trimmed, 5001)
    }

    private fun vibrateFeedback(duration: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(duration)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        matchingScope.cancel()
        recognizer.close()
        pillDetector?.close()
        visionFinder?.close()
        beep?.release()
        speechRecognizer?.destroy()
        _binding = null
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    private class ConfirmedMedicineAdapter(
        private val list: List<Medicine>,
        private val onDeleteClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ConfirmedMedicineAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameTv: TextView       = view.findViewById(R.id.tvConfirmedName)
            val deleteBtn: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_confirmed_medicine, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.nameTv.text = list[position].name.uppercase()
            holder.deleteBtn.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onDeleteClick(pos)
            }
        }

        override fun getItemCount() = list.size
    }

    // -----------------------------------------------------------------------
    // Image helpers
    // -----------------------------------------------------------------------

    // Replaced NV21 → JPEG-encode (quality 100) → JPEG-decode → Bitmap with
    // CameraX's built-in toBitmap(), which uses a native JNI path and avoids
    // the JPEG compression/decompression cycle entirely.
    // Rotation is applied exactly once (the old toDetectorBitmap was called
    // from captureAndScan's let-block which then rotated again — double rotate).
    private fun ImageProxy.toDetectorBitmap(): Bitmap {
        val bmp = toBitmap()
        if (imageInfo.rotationDegrees == 0) return bmp
        val matrix = android.graphics.Matrix()
        matrix.postRotate(imageInfo.rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bmp.recycle()
        return rotated
    }

    // Uses pre-compiled companion-object regex. Previously these were
    // constructed with Regex(...) on every call to toSampleText().
    private fun String.toSampleText(): String {
        val normalized = uppercase(Locale.ROOT)
            .replace(SAMPLE_CLEAN_REGEX,  " ")
            .replace(SAMPLE_SPACES_REGEX, " ")
            .trim()
        val token = normalized.split(" ").firstOrNull { it.any(Char::isLetter) }.orEmpty()
        return token.replace(SAMPLE_ALPHA_REGEX, "").ifBlank { normalized }
    }

    // Bulk getPixels() replaces per-pixel getPixel() calls.
    // Used by processImageWithOCR (live OCR fallback path).
    private fun hasExcessiveGlare(bitmap: Bitmap): Boolean {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var bright = 0; var total = 0
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                val p    = pixels[row * w + col]
                val luma = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (luma > 245) bright++
                total++
                col += 5
            }
            row += 5
        }
        return total > 0 && bright.toFloat() / total > 0.15f
    }

    // Bulk getPixels() replaces per-pixel getPixel() calls.
    // Used by processImageWithOCR (live OCR fallback path).
    private fun isBlurry(bitmap: Bitmap): Boolean {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var diffSum = 0L; var count = 0
        var row = 1
        while (row < h) {
            var col = 1
            while (col < w) {
                val cur  = pixels[row * w + col]
                val prev = pixels[(row - 1) * w + (col - 1)]
                val cG = ((cur  shr 16 and 0xFF) + (cur  shr 8 and 0xFF) + (cur  and 0xFF)) / 3
                val pG = ((prev shr 16 and 0xFF) + (prev shr 8 and 0xFF) + (prev and 0xFF)) / 3
                diffSum += abs(cG - pG)
                count++
                col += 5
            }
            row += 5
        }
        return count > 0 && diffSum.toFloat() / count < 3.2f
    }

    private fun removeSpecularHighlights(bitmap: Bitmap): Bitmap =
        ImageUtils.removeSpecularHighlights(bitmap)


    // -----------------------------------------------------------------------
    // Y-plane sampling — zero Bitmap allocation
    //
    // Both functions read directly from the YUV_420_888 luminance plane buffer.
    // The sampled ROI matches LabelOcrHelper center 1:1 square (same as OCR crop).
    // A try/catch makes both safe on devices that return JPEG-format captures
    // (planes[0] would contain JPEG bytes; the function returns false harmlessly).
    // -----------------------------------------------------------------------

    private fun ImageProxy.hasExcessiveGlareYPlane(): Boolean {
        return try {
            val buf    = planes[0].buffer
            val stride = planes[0].rowStride
            val w = width; val h = height
            val roi = LabelOcrHelper.centerWide3x1Roi(w, h)
            val x0 = roi[0]; val y0 = roi[1]; val x1 = roi[2]; val y1 = roi[3]
            var bright = 0; var total = 0
            var row = y0
            while (row < y1) {
                var col = x0
                while (col < x1) {
                    if (buf.get(row * stride + col).toInt() and 0xFF > 245) bright++
                    total++
                    col += 5
                }
                row += 5
            }
            total > 0 && bright.toFloat() / total > 0.15f
        } catch (_: Exception) { false }
    }

    private fun ImageProxy.isBlurryYPlane(): Boolean {
        return try {
            val buf    = planes[0].buffer
            val stride = planes[0].rowStride
            val w = width; val h = height
            val roi = LabelOcrHelper.centerWide3x1Roi(w, h)
            val x0 = (roi[0] + 1).coerceAtMost(roi[2] - 1)
            val y0 = (roi[1] + 1).coerceAtMost(roi[3] - 1)
            val x1 = roi[2]; val y1 = roi[3]
            var diffSum = 0L; var count = 0
            var row = y0
            while (row < y1) {
                var col = x0
                while (col < x1) {
                    val cur  = buf.get(row * stride + col).toInt() and 0xFF
                    val prev = buf.get((row - 1) * stride + (col - 1)).toInt() and 0xFF
                    diffSum += abs(cur - prev)
                    count++
                    col += 5
                }
                row += 5
            }
            count > 0 && diffSum.toFloat() / count < 3.2f
        } catch (_: Exception) { false }
    }

    /** Average Y-plane edge strength in scan ROI (higher = sharper). For logging only. */
    private fun ImageProxy.blurScoreYPlane(): Float {
        return try {
            val buf = planes[0].buffer
            val stride = planes[0].rowStride
            val w = width
            val h = height
            val roi = LabelOcrHelper.centerWide3x1Roi(w, h)
            val x0 = (roi[0] + 1).coerceAtMost(roi[2] - 1)
            val y0 = (roi[1] + 1).coerceAtMost(roi[3] - 1)
            val x1 = roi[2]
            val y1 = roi[3]
            var diffSum = 0L
            var count = 0
            var row = y0
            while (row < y1) {
                var col = x0
                while (col < x1) {
                    val cur = buf.get(row * stride + col).toInt() and 0xFF
                    val prev = buf.get((row - 1) * stride + (col - 1)).toInt() and 0xFF
                    diffSum += abs(cur - prev)
                    count++
                    col += 5
                }
                row += 5
            }
            if (count == 0) 0f else diffSum.toFloat() / count
        } catch (_: Exception) {
            0f
        }
    }

    private fun Bitmap.centerCrop(widthPercent: Float, heightPercent: Float): Bitmap {
        val cropWidth  = (width  * widthPercent).toInt()
        val cropHeight = (height * heightPercent).toInt()
        val left = (width  - cropWidth)  / 2
        val top  = (height - cropHeight) / 2
        return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE_PROCESSING) return

        val squareOcr = data?.getStringExtra(ImageProcessingActivity.EXTRA_PRIMARY_OCR)
            ?: pendingOcrResult
        val wideOcr = data?.getStringExtra(ImageProcessingActivity.EXTRA_WIDE_OCR)
            ?: pendingWideOcrText

        pendingOcrResult = null
        pendingWideOcrText = null

        val primary = squareOcr?.trim().orEmpty()
        val wide = wideOcr?.trim().orEmpty()
        if (primary.length < 3 && wide.length < 3) {
            binding.resultTextView.text = getString(R.string.ready_status)
            binding.resultTextView.setTextColor("#E0E0E0".toColorInt())
            binding.statusText.text = "Nothing readable — move closer & tap camera"
            binding.statusText.setTextColor("#FF9800".toColorInt())
            return
        }

        Log.d(TAG, "Scan match after debug close: square=${primary.take(50)} wide=${wide.take(50)}")
        processScanResult(
            primaryOcr = if (primary.length >= 3) primary else wide,
            hintOcr = if (primary.length >= 3) wide.takeIf { it.length >= 3 } else null
        )
    }
}
