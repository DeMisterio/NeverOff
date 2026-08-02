package com.neveroff.media

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

class CameraRecorderHelper(private val context: Context) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var outputFile: File? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        executor: Executor = ContextCompat.getMainExecutor(context)
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD)) // SD quality for smaller size
                .build()
            
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                
                pendingRecording?.let { callback ->
                    pendingRecording = null
                    startActualRecording(videoCapture!!, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                pendingRecording?.invoke(null)
                pendingRecording = null
            }
        }, executor)
    }

    private var pendingRecording: ((File?) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording(onVideoSaved: (File?) -> Unit) {
        val capture = this.videoCapture
        if (capture == null) {
            // Save it to be executed once bindCamera finishes
            pendingRecording = onVideoSaved
            return
        }
        
        startActualRecording(capture, onVideoSaved)
    }

    @SuppressLint("MissingPermission")
    private fun startActualRecording(capture: VideoCapture<Recorder>, onVideoSaved: (File?) -> Unit) {
        val fileName = "video_circle_${System.currentTimeMillis()}.mp4"
        val file = File(context.cacheDir, fileName)
        outputFile = file

        val outputOptions = FileOutputOptions.Builder(file).build()

        recording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            onVideoSaved(file)
                        } else {
                            recordEvent.cause?.printStackTrace()
                            onVideoSaved(null)
                        }
                    }
                }
            }
    }

    fun stopRecording() {
        if (recording == null) {
            pendingRecording?.invoke(null)
        }
        pendingRecording = null
        recording?.stop()
        recording = null
    }

    fun cancelRecording() {
        pendingRecording?.invoke(null)
        pendingRecording = null
        recording?.stop()
        recording = null
        outputFile?.delete()
        outputFile = null
    }
}
