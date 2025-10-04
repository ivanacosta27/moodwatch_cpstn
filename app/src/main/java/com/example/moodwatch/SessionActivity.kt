package com.example.moodwatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.moodwatch.databinding.ActivitySessionBinding
import com.example.moodwatch.expression.EmotionAnalyzer
import com.example.moodwatch.expression.EmotionClassifier
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SessionActivity : AppCompatActivity() {

    // ---------- View / camera ----------
    private lateinit var binding: ActivitySessionBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var classifier: EmotionClassifier
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    // ---------- Vosk STT + speaker id ----------
    private lateinit var stt: VoskStt
    private val spk = SpeakerId()
    private var enrolling: String? = null
    private val enrollCounselor = mutableListOf<FloatArray>()
    private val enrollStudent   = mutableListOf<FloatArray>()

    // ---------- Transcript / timing ----------
    private val transcriptLines = mutableListOf<String>()
    private var sessionStartMs: Long = 0L

    // ---------- Firestore ----------
    private val studentId: String by lazy { intent.getStringExtra("student_id") ?: "anon" }
    private val sessionId: String = UUID.randomUUID().toString()
    private val startedAt: Timestamp = Timestamp.now()

    // ---------- Permissions launcher (Option A call-site check) ----------
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cam = results[Manifest.permission.CAMERA] == true
            val mic = results[Manifest.permission.RECORD_AUDIO] == true

            if (!cam) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish(); return@registerForActivityResult
            }
            if (!mic) {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
                finish(); return@registerForActivityResult
            }

            // Both granted:
            startCamera()

            // Re-check mic permission right here to satisfy Lint & runtime safety
            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!micGranted) {
                Toast.makeText(this, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            initAndStartVosk()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStartMs = System.currentTimeMillis()
        classifier = EmotionClassifier(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // UI handlers
        binding.btnEnd.setOnClickListener {
            saveTranscriptToFirestore(
                onDone = { finish() },
                onError = { finish() }
            )
        }
        binding.btnSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            startCamera()
        }
        binding.btnEnrollCounselor.setOnClickListener { startEnrollment("counselor") }
        binding.btnEnrollStudent.setOnClickListener { startEnrollment("student") }

        // Request permissions if needed
        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needs += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needs += Manifest.permission.RECORD_AUDIO

        if (needs.isEmpty()) {
            startCamera()

            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                initAndStartVosk()
            } else {
                // Shouldn't happen if needs.isEmpty(), but keep safe guard
                requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            requestPermissions.launch(needs.toTypedArray())
        }
    }

    // ========= Vosk install + init + start =========
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initAndStartVosk() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1) Install/download models on first run
            try {
                ModelInstaller.ensureAllModels(
                    context = this@SessionActivity,
                    progress = object : ModelInstaller.Progress {
                        override fun onProgress(model: String, bytes: Long, total: Long) {
                            // Optional: update a progress UI here with runOnUiThread { ... }
                            if (total > 0) {
                                val pct = (bytes * 100 / total).toInt()
                                Log.d("ModelDL", "$model: $pct%")
                            } else {
                                Log.d("ModelDL", "$model: $bytes bytes")
                            }
                        }
                        override fun onMessage(msg: String) { Log.d("ModelDL", msg) }
                    }
                )
            } catch (t: Throwable) {
                Log.e("SessionActivity", "Model install failed", t)
                runOnUiThread {
                    Toast.makeText(
                        this@SessionActivity,
                        "Model install failed: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                return@launch
            }

            // 2) Paths after install
            val base = File(filesDir, "vosk")
            val asrDir = File(base, ModelConfig.ASR_FOLDER)
            val spkDir = File(base, ModelConfig.SPK_FOLDER)

            // 3) Build recognizer
            stt = VoskStt(
                onPartial = { /* optional live captions */ },
                onFinal = { pkt ->
                    when (enrolling) {
                        "counselor" -> pkt.spk?.let { enrollCounselor += it }
                        "student"   -> pkt.spk?.let { enrollStudent   += it }
                        else -> {
                            val who = spk.label(pkt.spk) // "counselor" | "student" | "unknown"
                            val emotion = if (who == "student") currentEmotion() else null
                            appendTranscriptLine(pkt.text, who, emotion)
                        }
                    }
                }
            )

            try {
                stt.init(asrDir.absolutePath, spkDir.absolutePath)

                // Double-check mic permission before opening mic (extra safety)
                val micGranted = ContextCompat.checkSelfPermission(
                    this@SessionActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!micGranted) {
                    runOnUiThread {
                        Toast.makeText(
                            this@SessionActivity,
                            "Microphone permission not granted",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                try {
                    stt.start()
                } catch (se: SecurityException) {
                    Log.e("SessionActivity", "Mic start denied", se)
                    runOnUiThread {
                        Toast.makeText(
                            this@SessionActivity,
                            "Microphone access denied",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            } catch (t: Throwable) {
                Log.e("SessionActivity", "Vosk init/start failed", t)
                runOnUiThread {
                    Toast.makeText(
                        this@SessionActivity,
                        "Vosk failed: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    // ========= Enrollment helpers =========
    private fun startEnrollment(who: String) {
        // who = "counselor" or "student"
        enrolling = who
        if (who == "counselor") {
            enrollCounselor.clear()
            binding.btnEnrollCounselor.isEnabled = false
            binding.btnEnrollStudent.isEnabled = false
        } else {
            enrollStudent.clear()
            binding.btnEnrollStudent.isEnabled = false
            binding.btnEnrollCounselor.isEnabled = false
        }
        Toast.makeText(this, "Enrolling $who… please speak naturally", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            enrolling = null
            if (who == "counselor") {
                if (enrollCounselor.isNotEmpty()) spk.enrollCounselor(enrollCounselor)
                binding.btnEnrollCounselor.isEnabled = true
            } else {
                if (enrollStudent.isNotEmpty()) spk.enrollStudent(enrollStudent)
                binding.btnEnrollStudent.isEnabled = true
            }
            binding.btnEnrollCounselor.isEnabled = true
            binding.btnEnrollStudent.isEnabled = true
            Toast.makeText(this, "Enrollment finished for $who", Toast.LENGTH_SHORT).show()
        }, 5_000)
    }

    // ========= Camera / emotion =========
    private fun startCamera() {
        val hasCam = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(
                    if (provider.hasCameraSafe(lensFacing)) lensFacing
                    else CameraSelector.LENS_FACING_FRONT
                )
                .build()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        cameraExecutor,
                        EmotionAnalyzer(classifier, binding.tvEmotion)
                    )
                }

            try {
                provider.unbindAll()
                run {
                    provider.bindToLifecycle(this, cameraSelector, preview, analyzer)
                }
                Log.d("SessionActivity", "Camera started. lensFacing=$lensFacing")
            } catch (se: SecurityException) {
                Log.e("SessionActivity", "Camera security exception", se)
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Log.e("SessionActivity", "Camera binding failed", e)
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ProcessCameraProvider.hasCameraSafe(lens: Int): Boolean {
        return try {
            hasCamera(CameraSelector.Builder().requireLensFacing(lens).build())
        } catch (_: Exception) { false }
    }

    private fun currentEmotion(): String? {
        val raw = binding.tvEmotion.text?.toString().orEmpty()
        val label = raw.substringAfter("Emotion:", "").trim()
        return if (label.isEmpty() || label == "—") null else label
    }

    // ========= Transcript formatting =========
    private fun appendTranscriptLine(text: String, speaker: String, emotion: String?) {
        if (text.isBlank()) return
        val ms = System.currentTimeMillis() - sessionStartMs
        val ts = msToMmSs(ms)
        val line = buildString {
            append(ts).append(" ").append(speaker.lowercase(Locale.ROOT))
            if (!emotion.isNullOrBlank()) append(" (").append(emotion).append(")")
            append(": ").append(text)
        }
        transcriptLines.add(line)
        Log.d("Transcript", line)
    }

    private fun msToMmSs(ms: Long): String {
        val total = ms / 1000
        val mm = (total / 60).toString().padStart(2, '0')
        val ss = (total % 60).toString().padStart(2, '0')
        return "$mm:$ss"
    }

    // ========= Firestore save =========
    private fun saveTranscriptToFirestore(onDone: () -> Unit, onError: () -> Unit) {
        try { stt.stop() } catch (_: Exception) {}
        val payload = hashMapOf(
            "sessionId" to sessionId,
            "studentId" to studentId,
            "startedAt" to startedAt,
            "endedAt" to Timestamp.now(),
            "lines" to transcriptLines
        )
        Firebase.firestore
            .collection("student").document(studentId)
            .collection("session").document(sessionId)
            .set(payload)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener { e ->
                Log.e("SessionActivity", "Firestore save failed", e)
                onError()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stt.stop() } catch (_: Exception) {}
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
