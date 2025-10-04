package com.example.moodwatch

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

class VoskStt(
    private val onPartial: (String) -> Unit,
    private val onFinal: (ResultPacket) -> Unit
) {
    data class Word(val w: String, val t0: Float, val t1: Float)
    data class ResultPacket(val text: String, val words: List<Word>, val spk: FloatArray?)
    private var model: Model? = null
    private var spkModel: SpeakerModel? = null
    private var rec: Recognizer? = null
    private var ar: AudioRecord? = null
    @Volatile private var running = false

    suspend fun init(asrPath: String, spkPath: String) = withContext(Dispatchers.IO) {
        model = Model(asrPath)
        spkModel = SpeakerModel(spkPath)
        rec = Recognizer(model, 16000.0f, spkModel)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (min * 2).coerceAtLeast(4096)

        ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        running = true
        ar?.startRecording()

        Thread {
            val buf = ByteArray(4096)
            while (running) {
                val n = ar?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    val isFinal = rec?.acceptWaveForm(buf, n) == true
                    if (isFinal) parseFinal(rec?.result) else parsePartial(rec?.partialResult)
                }
            }
            rec?.finalResult?.let { parseFinal(it) }
            try { ar?.stop() } catch (_: Exception) {}
            ar?.release(); ar = null
        }.start()
    }

    fun stop() { running = false }

    private fun parsePartial(json: String?) {
        val p = json?.let { JSONObject(it).optString("partial") }.orEmpty()
        if (p.isNotBlank()) onPartial(p)
    }

    private fun parseFinal(json: String?) {
        if (json.isNullOrBlank()) return
        val o = JSONObject(json)
        val text = o.optString("text", "")
        if (text.isBlank()) return

        val wordsArr = o.optJSONArray("words") ?: JSONArray()
        val words = buildList {
            for (i in 0 until wordsArr.length()) {
                val w = wordsArr.getJSONObject(i)
                add(Word(
                    w = w.optString("word"),
                    t0 = w.optDouble("start", 0.0).toFloat(),
                    t1 = w.optDouble("end", 0.0).toFloat()
                ))
            }
        }

        val spkVec = when {
            o.has("spk") && o.get("spk") is JSONObject ->
                (o.getJSONObject("spk").optJSONArray("xvector") ?: JSONArray())
            o.has("spk") && o.get("spk") is JSONArray ->
                o.getJSONArray("spk")
            else -> null
        }?.let { arr -> FloatArray(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() } }

        onFinal(ResultPacket(text, words, spkVec))
    }
}
