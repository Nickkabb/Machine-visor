package com.example.machinevisor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.util.concurrent.ListenableFuture
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    
    // UI elements
    private lateinit var statusText: TextView
    private lateinit var cameraImageView: ImageView
    private lateinit var cameraPreviewView: androidx.camera.view.PreviewView
    private lateinit var cameraStatusText: TextView
    private var imageCapture: ImageCapture? = null

    // Tabs and settings UI
    private lateinit var tabHome: TextView
    private lateinit var tabSettings: TextView
    private lateinit var homeContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout
    private lateinit var ttsSpeedSeek: SeekBar
    private lateinit var ttsSpeedValue: TextView
    private lateinit var ttsVoiceSpinner: Spinner
    private lateinit var ttsTestButton: Button
    private lateinit var serverUrlEdit: EditText
    private lateinit var serverUrlSave: Button

    // TTS and prefs
    private var tts: TextToSpeech? = null
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    
    // Data storage
    private var lastAccelValues = floatArrayOf(0f, 0f, 0f)
    private var lastGyroValues = floatArrayOf(0f, 0f, 0f)
    
    // Camera variables
    private var cameraTimer: Timer? = null
    private var imageCounter = 0
    private var sequenceCounter = 0
    
    // Permission request code
    @Suppress("PrivatePropertyName")
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001
    
    // Voice recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val triggerWords = listOf("чек", "посмотреть", "увидеть", "осмотр")

    // Last results cache for TTS readout
    private var lastObjectsHeader: String = ""
    private var lastObjectsLines: List<String> = emptyList()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initializeViews()
        initializeSensors()
        initializeCamera()
        initializeSpeechRecognition()
    }
    
    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        cameraPreviewView = findViewById(R.id.cameraPreviewView)
        cameraImageView = findViewById(R.id.cameraImageView)
        cameraStatusText = findViewById(R.id.cameraStatusText)

        tabHome = findViewById(R.id.tabHome)
        tabSettings = findViewById(R.id.tabSettings)
        homeContainer = findViewById(R.id.homeContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        ttsSpeedSeek = findViewById(R.id.ttsSpeedSeek)
        ttsSpeedValue = findViewById(R.id.ttsSpeedValue)
        ttsVoiceSpinner = findViewById(R.id.ttsVoiceSpinner)
        ttsTestButton = findViewById(R.id.ttsTestButton)
        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        serverUrlSave = findViewById(R.id.serverUrlSave)

        setupTabs()
        setupTtsControls()
        setupServerControls()
    }
    
    private fun initializeSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        
        // Get accelerometer sensor
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            statusText.text = "Accelerometer not available"
            return
        }
        
        // Get gyroscope sensor
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscope == null) {
            statusText.text = "Gyroscope not available"
            return
        }
        
        // Register listeners
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        
        statusText.text = "Sensors active - Data updating in real-time"
    }

    private fun setupServerControls() {
        val gradleDefault = BuildConfig.SERVER_BASE_URL
        val saved = prefs.getString("server_base_url", null)
        val effective = (saved ?: gradleDefault).ifEmpty { "http://10.0.2.2:8000" }
        serverUrlEdit.setText(effective)
        serverUrlSave.setOnClickListener {
            val raw = serverUrlEdit.text?.toString()?.trim().orEmpty()
            val validated = normalizeBaseUrl(raw)
            if (validated.isNotEmpty()) {
                prefs.edit().putString("server_base_url", validated).apply()
                cameraStatusText.text = "Сервер сохранён: ${validated}"
            } else {
                cameraStatusText.text = "Некорректный адрес сервера"
            }
        }
    }

    private fun normalizeBaseUrl(input: String): String {
        if (input.isBlank()) return ""
        val candidate = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://${input}"
        return try {
            val uri = android.net.Uri.parse(candidate)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) return ""
            var out = candidate
            if (!out.endsWith("/")) out += "/"
            out
        } catch (_: Exception) { "" }
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            when (sensorEvent.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccelValues = sensorEvent.values.clone()
                    updateTiltStatus()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroValues = sensorEvent.values.clone()
                    updateTiltStatus()
                }
            }
        }
    }
    
    @SuppressLint("DefaultLocale")
    private fun updateTiltStatus() {
        // Compute pitch (forward/back tilt) and roll (side tilt) from accelerometer
        val ax = lastAccelValues[0]
        val ay = lastAccelValues[1]
        val az = lastAccelValues[2]
        val pitchRadians = kotlin.math.atan2(-ax.toDouble(), kotlin.math.sqrt((ay * ay + az * az).toDouble()))
        val pitchDegrees = Math.toDegrees(pitchRadians)
        val rollRadians = kotlin.math.atan2(ay.toDouble(), az.toDouble())
        val rollDegrees = Math.toDegrees(rollRadians)

        // Use gyro magnitude to ensure the phone is relatively stable when evaluating tilt
        val gx = lastGyroValues[0]
        val gy = lastGyroValues[1]
        val gz = lastGyroValues[2]
        val rotationRate = sqrt(gx * gx + gy * gy + gz * gz)

        // Define acceptable position windows
        val pitchMin = -15.0 // degrees
        val pitchMax = 15.0 // degrees
        val rollMin = 65.0 // degrees (absolute)
        val rollMax = 85.0 // degrees (absolute)

        val absRoll = kotlin.math.abs(rollDegrees)
        val withinAngles = (pitchDegrees in pitchMin..pitchMax) && (absRoll in rollMin..rollMax)
        val stable = rotationRate < 0.5f
        val pitchStr = String.format("%.1f", pitchDegrees)
        val rollStr = String.format("%.1f", rollDegrees)
        statusText.text = if (withinAngles && stable) {
            "Pitch ${pitchStr}°\nRoll ${rollStr}°\nправильная позиция"
        } else {
            "Pitch ${pitchStr}°\nRoll ${rollStr}°\nнеправильная позиция"
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
        when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                // High accuracy
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                // Medium accuracy
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                // Low accuracy
            }
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                statusText.text = "Sensor data unreliable"
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Re-register sensor listeners when activity resumes
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    
    private fun initializeSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }
        // Request mic permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }
        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Restart listening after short delay
                cameraStatusText.postDelayed({ startListening() }, 500)
            }
            override fun onResults(results: Bundle?) {
                handleSpeechResults(results)
                cameraStatusText.postDelayed({ startListening() }, 200)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                handleSpeechResults(partialResults)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    private fun startListening() {
        try {
            recognizerIntent?.let { speechRecognizer?.startListening(it) }
        } catch (_: Exception) {}
    }

    private fun handleSpeechResults(bundle: Bundle?) {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (texts.isEmpty()) return
        val heard = texts.joinToString(" ").lowercase()
        if (triggerWords.any { heard.contains(it) }) {
            speakLastObjects()
        }
    }

    private fun speakLastObjects() {
        if (lastObjectsHeader.isBlank() && lastObjectsLines.isEmpty()) return
        // Remove pixel sizes from spoken lines: " — 123x456 px"
        val spokenLines = lastObjectsLines.map { line ->
            // Remove optional NBSP, optional spaces, dash, size pattern WxH and 'px'
            line.replace(Regex("""(?:\u00A0)?\s?—\s?\d+x\d+\s?px"""), "")
        }
        val phrase = buildString {
            append(lastObjectsHeader)
            if (spokenLines.isNotEmpty()) {
                append(". ")
                append(spokenLines.joinToString(". "))
            }
        }
        val rate = prefs.getFloat("tts_speed", 1.0f)
        tts?.language = java.util.Locale("ru", "RU")
        tts?.setSpeechRate(rate)
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "tts_objects_readout")
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister sensor listeners to save battery
        sensorManager.unregisterListener(this)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up sensor listeners
        sensorManager.unregisterListener(this)
        // Clean up camera timer
        cameraTimer?.cancel()
        cameraTimer = null
        // Shutdown TTS
        try { tts?.shutdown() } catch (_: Exception) {}
        // Destroy recognizer
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
    }
    
    // Camera initialization
    private fun initializeCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            startCameraX()
            startCameraTimer()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startCameraX() {
        try {
            val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(cameraPreviewView.surfaceProvider)
                    imageCapture = ImageCapture.Builder().build()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                    cameraStatusText.text = "Camera ready - preview + capture every 2 seconds"
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to bind camera", e)
                    cameraStatusText.text = "Camera bind failed: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting CameraX", e)
            cameraStatusText.text = "CameraX error: ${e.message}"
        }
    }
    
    private fun startCameraTimer() {
        cameraStatusText.text = "Camera active - Capturing every 2 seconds"
        cameraTimer = Timer()
        cameraTimer?.schedule(object : TimerTask() {
            override fun run() {
                captureAndDisplayImage()
            }
        }, 0, 2000) // 2 seconds
    }

    private fun captureAndDisplayImage() {
        val capture = imageCapture
        if (capture == null) {
            // If ImageCapture not ready, show demo image
            runOnUiThread { createDemoImage() }
            return
        }
        try {
            val tempFile = File.createTempFile("mv_capture_", ".jpg", cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
            capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                    if (bitmap != null) {
                        cameraImageView.setImageBitmap(bitmap)
                        // Immediately upload the image with sensor metadata
                        uploadCapturedImage(tempFile)
                    } else {
                        createDemoImage()
                    }
                    // Do not delete here; uploadCapturedImage will clean up
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainActivity", "Image capture failed", exception)
                    cameraStatusText.text = "Capture error: ${exception.message}"
                    createDemoImage()
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "captureAndDisplayImage error", e)
            runOnUiThread { createDemoImage() }
        }
    }

    private fun uploadCapturedImage(file: File) {
        val savedPref = prefs.getString("server_base_url", null)
        val defaultGradle = BuildConfig.SERVER_BASE_URL
        val serverUrl = normalizeBaseUrl(savedPref ?: defaultGradle).ifEmpty { "http://10.0.2.2:8000/" }
        val api = ApiClient.get(serverUrl)

        // Compute pitch/roll consistent with UI
        val ax = lastAccelValues[0]
        val ay = lastAccelValues[1]
        val az = lastAccelValues[2]
        val pitchRadians = kotlin.math.atan2(-ax.toDouble(), kotlin.math.sqrt((ay * ay + az * az).toDouble()))
        val pitchDegrees = Math.toDegrees(pitchRadians)
        val rollRadians = kotlin.math.atan2(ay.toDouble(), az.toDouble())
        val rollDegrees = Math.toDegrees(rollRadians)

        val seq = ++sequenceCounter
        val details = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reqFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("image", file.name, reqFile)

                val response = api.uploadImage(
                    image = part,
                    pitchDeg = pitchDegrees,
                    rollDeg = rollDegrees,
                    seq = seq,
                    details = details
                )

                // Prepare display text off the main thread
                val displayText: String = if (response.isSuccessful) {
                    val body = response.body()
                    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

                    var objectsAmount = "0"
                    val lines = mutableListOf<String>()
                    val analysisAny = body?.analysis
                    if (analysisAny is Map<*, *>) {
                        // Count
                        val oc = analysisAny["object_count"]
                        objectsAmount = when (oc) {
                            is Number -> oc.toInt().toString()
                            is String -> oc.toDoubleOrNull()?.toInt()?.toString() ?: oc
                            else -> "0"
                        }
                        // Objects list
                        val objsAny = analysisAny["objects"]
                        if (objsAny is List<*>) {
                            val sizedLines = mutableListOf<Pair<Long, String>>()
                            for (entry in objsAny) {
                                if (entry is Map<*, *>) {
                                    val name = entry["class"]?.toString() ?: "объект"
                                    val nameRu = translateClassEnToRu(name)
                                    val locAny = entry["grid_positions"]
                                    val locationRu = when (locAny) {
                                        is List<*> -> locAny.joinToString(", ") { it?.toString()?.let { s -> translateLocationEnToRu(s) } ?: "" }
                                        is String -> translateLocationEnToRu(locAny)
                                        else -> ""
                                    }
                                    val wPxNum = entry["width"] as? Number
                                    val hPxNum = entry["height"] as? Number
                                    val wPx = wPxNum?.toInt()
                                    val hPx = hPxNum?.toInt()
                                    val area = if (wPx != null && hPx != null) (wPx.toLong() * hPx.toLong()) else 0L
                                    val line = "- ${nameRu}: ${locationRu}"
                                    sizedLines.add(area to line)
                                }
                            }
                            sizedLines.sortByDescending { it.first }
                            lines.addAll(sizedLines.map { it.second })
                        }
                    }

                    buildString {
                        append("Объектов: ")
                        append(objectsAmount)
                        if (lines.isNotEmpty()) {
                            append('\n')
                            append(lines.joinToString("\n"))
                        }
                    }.also { txt ->
                        // Cache last objects for voice readout
                        lastObjectsHeader = "Объектов: ${objectsAmount}"
                        lastObjectsLines = lines
                    }
                } else {
                    val err = try { response.errorBody()?.string() } catch (_: Exception) { null }
                    "Upload failed: ${response.code()}${if (!err.isNullOrBlank()) "\n" + err else ""}"
                }

                withContext(Dispatchers.Main) {
                    cameraStatusText.text = displayText
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    cameraStatusText.text = "Upload error: ${e.message}"
                }
            } finally {
                // Clean up temp file
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }
    
    @SuppressLint("SetTextI18n")
    private fun createDemoImage() {
        try {
            // Create a simple demo image with current timestamp
            val bitmap = createBitmap(400, 300)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            
            // Fill with a gradient background
            val gradient = LinearGradient(
                0f, 0f, 400f, 300f,
                intArrayOf("#4CAF50".toColorInt(), "#2196F3".toColorInt()),
                null,
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawRect(0f, 0f, 400f, 300f, paint)
            
            // Add text with timestamp
            paint.shader = null
            paint.color = Color.WHITE
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            val timestamp = System.currentTimeMillis()
            imageCounter++
            canvas.drawText("Camera Feed #$imageCounter", 200f, 150f, paint)
            canvas.drawText("Time: $timestamp", 200f, 180f, paint)
            
            // Update UI on main thread
            runOnUiThread {
                cameraImageView.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating demo image", e)
            runOnUiThread {
                cameraStatusText.text = "Error creating image: ${e.message}"
            }
        }
    }
    
    private fun translateLocationEnToRu(src: String): String {
        // Simple phrase-based translation for known grid position descriptors
        return src
            .replace("entire image", "всё изображение")
            .replace("upper half", "верхняя половина")
            .replace("lower half", "нижняя половина")
            .replace("entire upper", "вся верхняя часть")
            .replace("entire lower", "вся нижняя часть")
            .replace("left half", "левая половина")
            .replace("right half", "правая половина")
            .replace("center area", "центр")
            .replace("upper left area", "верхняя левая часть")
            .replace("upper right area", "верхняя правая часть")
            .replace("lower left area", "нижняя левая часть")
            .replace("lower right area", "нижняя правая часть")
            .replace("upper area", "верхняя часть")
            .replace("lower area", "нижняя часть")
            .replace("left area", "левая часть")
            .replace("right area", "правая часть")
            .replace("upper", "верхняя")
            .replace("center", "центр")
            .replace("lower", "нижняя")
            .replace("left", "левая")
            .replace("middle", "средняя")
            .replace("right", "правая")
    }
    
    private fun translateClassEnToRu(name: String): String {
        val map = mapOf(
            "person" to "человек",
            "car" to "автомобиль",
            "bicycle" to "велосипед",
            "motorcycle" to "мотоцикл",
            "bus" to "автобус",
            "truck" to "грузовик",
            "train" to "поезд",
            "boat" to "лодка",
            "traffic light" to "светофор",
            "fire hydrant" to "пожарный гидрант",
            "stop sign" to "знак стоп",
            "parking meter" to "паркомат",
            "bench" to "скамейка",
            "bird" to "птица",
            "cat" to "кот",
            "dog" to "собака",
            "horse" to "лошадь",
            "sheep" to "овца",
            "cow" to "корова",
            "elephant" to "слон",
            "bear" to "медведь",
            "zebra" to "зебра",
            "giraffe" to "жираф",
            "backpack" to "рюкзак",
            "umbrella" to "зонт",
            "handbag" to "сумка",
            "tie" to "галстук",
            "suitcase" to "чемодан",
            "frisbee" to "фрисби",
            "skis" to "лыжи",
            "snowboard" to "сноуборд",
            "sports ball" to "мяч",
            "kite" to "воздушный змей",
            "baseball bat" to "бита",
            "baseball glove" to "перчатка для бейсбола",
            "skateboard" to "скейтборд",
            "surfboard" to "серфборд",
            "tennis racket" to "ракетка",
            "bottle" to "бутылка",
            "wine glass" to "бокал",
            "cup" to "чашка",
            "fork" to "вилка",
            "knife" to "нож",
            "spoon" to "ложка",
            "bowl" to "миска",
            "banana" to "банан",
            "apple" to "яблоко",
            "sandwich" to "сэндвич",
            "orange" to "апельсин",
            "broccoli" to "брокколи",
            "carrot" to "морковь",
            "hot dog" to "хот-дог",
            "pizza" to "пицца",
            "donut" to "пончик",
            "cake" to "торт",
            "chair" to "стул",
            "couch" to "диван",
            "potted plant" to "горшечное растение",
            "bed" to "кровать",
            "dining table" to "обеденный стол",
            "toilet" to "туалет",
            "tv" to "телевизор",
            "laptop" to "ноутбук",
            "mouse" to "мышь",
            "remote" to "пульт",
            "keyboard" to "клавиатура",
            "cell phone" to "телефон",
            "microwave" to "микроволновка",
            "oven" to "духовка",
            "toaster" to "тостер",
            "sink" to "раковина",
            "refrigerator" to "холодильник",
            "book" to "книга",
            "clock" to "часы",
            "vase" to "ваза",
            "scissors" to "ножницы",
            "teddy bear" to "плюшевый мишка",
            "hair drier" to "фен",
            "toothbrush" to "зубная щётка"
        )
        return map[name.lowercase()] ?: name
    }
    
    private fun setupTabs() {
        fun activateHome() {
            homeContainer.visibility = View.VISIBLE
            settingsContainer.visibility = View.GONE
            tabHome.setBackgroundColor("#DDDDDD".toColorInt())
            tabSettings.setBackgroundColor("#EEEEEE".toColorInt())
        }
        fun activateSettings() {
            homeContainer.visibility = View.GONE
            settingsContainer.visibility = View.VISIBLE
            tabHome.setBackgroundColor("#EEEEEE".toColorInt())
            tabSettings.setBackgroundColor("#DDDDDD".toColorInt())
        }
        tabHome.setOnClickListener { activateHome() }
        tabSettings.setOnClickListener { activateSettings() }
        activateHome()
    }

    private fun setupTtsControls() {
        val savedSpeed = prefs.getFloat("tts_speed", 1.0f)
        ttsSpeedSeek.progress = (savedSpeed * 100).toInt()
        ttsSpeedValue.text = String.format("%.1fx", savedSpeed)
        ttsSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = (progress.coerceIn(20, 150)) / 100f // 0.2x..1.5x
                ttsSpeedValue.text = String.format("%.1fx", rate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val rate = (ttsSpeedSeek.progress.coerceIn(20, 150)) / 100f
                prefs.edit().putFloat("tts_speed", rate).apply()
                tts?.setSpeechRate(rate)
            }
        })

        // Initialize TTS to enumerate voices
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    // Force Russian language
                    tts?.language = Locale("ru", "RU")

                    // Filter voices to Russian only
                    val allVoices = tts?.voices?.toList().orEmpty()
                    val voices = allVoices.filter { it.locale?.language?.lowercase() == "ru" }

                    if (voices.isEmpty()) {
                        val items = listOf("Нет русских голосов (установите Google TTS RU)")
                        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        ttsVoiceSpinner.adapter = adapter
                        ttsVoiceSpinner.isEnabled = false
                        ttsTestButton.isEnabled = false
                    } else {
                        val items = voices.map { v ->
                            val lang = v.locale?.displayName ?: ""
                            "${v.name} (${lang})"
                        }
                        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        ttsVoiceSpinner.adapter = adapter
                        ttsVoiceSpinner.isEnabled = true
                        ttsTestButton.isEnabled = true

                        // Restore previously selected RU voice
                        val savedVoiceName = prefs.getString("tts_voice", null)
                        if (!savedVoiceName.isNullOrEmpty()) {
                            val index = voices.indexOfFirst { it.name == savedVoiceName }
                            if (index >= 0) ttsVoiceSpinner.setSelection(index)
                        }

                        ttsVoiceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                                val v = voices.getOrNull(position)
                                if (v != null) {
                                    prefs.edit().putString("tts_voice", v.name).apply()
                                    tts?.voice = v
                                    tts?.language = Locale("ru", "RU")
                                }
                            }
                            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
                        })
                    }

                    // Apply saved speed
                    tts?.setSpeechRate(savedSpeed)

                    // Test button (stays in RU)
                    ttsTestButton.setOnClickListener {
                        val sample = "Проверка голоса и скорости речи."
                        val rate = prefs.getFloat("tts_speed", 1.0f)
                        tts?.language = Locale("ru", "RU")
                        tts?.setSpeechRate(rate)
                        tts?.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "tts_test")
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }
    
    @SuppressLint("SetTextI18n")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCameraTimer()
                } else {
                    cameraStatusText.text = "Camera permission denied - showing demo images"
                    startCameraTimer() // Start demo images even without permission
                }
            }
            2001 -> { // RECORD_AUDIO
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    setupSpeechRecognizer()
                } else {
                    // Mic denied; skip voice control
                }
            }
        }
    }
}