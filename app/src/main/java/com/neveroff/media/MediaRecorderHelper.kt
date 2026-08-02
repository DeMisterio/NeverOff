package com.neveroff.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class MediaRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    /**
     * Below this, MediaRecorder frequently hasn't written a single frame, so stop() throws and
     * leaves a 0-byte .m4a behind. Treat those as "didn't happen" rather than sending silence.
     */
    private val minDurationMs = 600L

    /** Returns the target file, or null if recording could not be started. */
    fun startRecording(): File? {
        // A recorder left alive from a previous failure still holds the microphone, which makes
        // every later attempt fail too. Always clear the decks first.
        releaseRecorder()

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()

            recorder = r
            outputFile = file
            startedAtMs = System.currentTimeMillis()
            file
        } catch (e: Exception) {
            // Releasing here is the whole point: the previous version returned from inside an
            // apply{} block, so this object was never stored and never released — it just kept
            // the mic open and poisoned every subsequent recording.
            e.printStackTrace()
            runCatching { r.reset() }
            runCatching { r.release() }
            recorder = null
            outputFile = null
            file.delete()
            null
        }
    }

    /**
     * Stops and returns the recording, or null if there is nothing worth sending — too short,
     * stop() failed, or the file came out empty. Callers must handle null rather than assuming
     * a file exists, otherwise a broken/0-byte voice note gets uploaded.
     */
    fun stopRecording(): File? {
        val r = recorder ?: return null
        val file = outputFile
        val heldMs = System.currentTimeMillis() - startedAtMs
        recorder = null
        outputFile = null

        if (heldMs < minDurationMs) {
            runCatching { r.reset() }
            runCatching { r.release() }
            file?.delete()
            return null
        }

        val stopped = try {
            r.stop()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        runCatching { r.release() }

        if (!stopped || file == null || file.length() <= 0L) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancelRecording() {
        val r = recorder
        recorder = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseRecorder() {
        recorder?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        recorder = null
    }
}
