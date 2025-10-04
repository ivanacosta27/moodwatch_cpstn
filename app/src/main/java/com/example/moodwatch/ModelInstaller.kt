package com.example.moodwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelInstaller {

    interface Progress {
        /** Called from a background thread. total may be -1 if server didn't send length. */
        fun onProgress(model: String, bytes: Long, total: Long)
        fun onMessage(msg: String)
    }

    private const val TAG = "ModelInstaller"

    /**
     * Ensure both ASR and SPK models are installed under filesDir/vosk/.
     * Downloads + unzips on first run; no-ops next times.
     */
    suspend fun ensureAllModels(context: Context, progress: Progress? = null) = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "vosk")
        if (!base.exists()) base.mkdirs()

        // ASR
        val asrOut = File(base, ModelConfig.ASR_FOLDER)
        ensureOneModel(
            context = context,
            url = ModelConfig.ASR_URL,
            zipName = ModelConfig.ASR_ZIP,
            modelFolderName = ModelConfig.ASR_FOLDER,
            outDir = asrOut,
            progress = progress
        )

        // Speaker
        val spkOut = File(base, ModelConfig.SPK_FOLDER)
        ensureOneModel(
            context = context,
            url = ModelConfig.SPK_URL,
            zipName = ModelConfig.SPK_ZIP,
            modelFolderName = ModelConfig.SPK_FOLDER,
            outDir = spkOut,
            progress = progress
        )
    }

    /**
     * Ensures a single model is available at outDir.
     * If a .ok marker exists with non-empty dir, it does nothing.
     */
    private fun ensureOneModel(
        context: Context,
        url: String,
        zipName: String,
        modelFolderName: String,
        outDir: File,
        progress: Progress?
    ) {
        val okMarker = File(outDir.parentFile, "$modelFolderName.ok")
        if (okMarker.exists() && outDir.exists() && outDir.list()?.isNotEmpty() == true) {
            Log.d(TAG, "Model already installed: ${outDir.absolutePath}")
            return
        }

        // Download zip to cache
        val tmpZip = File(context.cacheDir, zipName)
        progress?.onMessage("Downloading $zipName…")
        download(url, tmpZip, progress, modelFolderName)

        // Unzip
        progress?.onMessage("Unzipping $zipName…")
        unzip(tmpZip, outDir)

        // Cleanup & mark OK
        tmpZip.delete()
        okMarker.writeText("ok")
        Log.d(TAG, "Installed $modelFolderName to ${outDir.absolutePath}")
    }

    private fun download(
        urlStr: String,
        outFile: File,
        progress: Progress?,
        modelName: String
    ) {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var output: OutputStream? = null

        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 30000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $urlStr")
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            input = BufferedInputStream(conn.inputStream)
            output = BufferedOutputStream(FileOutputStream(outFile))

            val buffer = ByteArray(1024 * 32)
            var read: Int
            var downloaded = 0L
            while (true) {
                read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read
                progress?.onProgress(modelName, downloaded, total)
            }
            output.flush()
        } catch (e: Exception) {
            outFile.delete()
            throw IOException("Download failed for $urlStr: ${e.message}", e)
        } finally {
            try { output?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun unzip(zipFile: File, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            var entry = zip.nextEntry ?: throw IOException("Empty ZIP: ${zipFile.name}")
            while (entry != null) {
                val outPath = File(outDir, entry.name)
                if (entry.isDirectory) {
                    outPath.mkdirs()
                } else {
                    outPath.parentFile?.mkdirs()
                    FileOutputStream(outPath).use { os -> zip.copyTo(os) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // Sanity check
        if (outDir.list()?.isEmpty() != false) {
            throw IOException("Unzip produced empty directory: ${outDir.absolutePath}")
        }
    }
}
