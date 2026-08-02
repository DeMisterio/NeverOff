package com.neveroff.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide state for the self-update flow.
 *
 * This lives outside any one screen because the same download has to be visible from three
 * places at once: the banner on the chat list, the progress strip inside a chat, and the entry
 * in Settings. Keeping it in ChatListScreen's `remember` meant leaving that screen cancelled the
 * UI's knowledge of an in-flight download.
 */
object UpdateController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _available = MutableStateFlow<ServerVersion?>(null)
    val available: StateFlow<ServerVersion?> = _available.asStateFlow()

    /** Null when idle; 0..1 while a download is running. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _downloadedFile = MutableStateFlow<File?>(null)
    val downloadedFile: StateFlow<File?> = _downloadedFile.asStateFlow()

    /** Banner hidden on the chat list. Settings still surfaces the update regardless. */
    private val _bannerDismissed = MutableStateFlow(false)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    private var downloadJob: Job? = null

    suspend fun refresh() {
        val found = UpdateManager.checkForUpdates()
        if (found == null) {
            _available.value = null
            return
        }
        // A genuinely new version un-hides the banner: dismissing 2.01 shouldn't also silence 2.02.
        if (found.version != _available.value?.version) {
            _available.value = found
            _bannerDismissed.value = false
            _downloadedFile.value = null
        }
    }

    /**
     * Hiding is refused while a download is running — that banner carries the only progress
     * readout and the only cancel control, so dismissing it would strand the download invisibly.
     */
    fun dismissBanner() {
        if (_progress.value == null) _bannerDismissed.value = true
    }

    fun startDownload(context: Context) {
        val version = _available.value ?: return
        if (downloadJob?.isActive == true || _downloadedFile.value != null) return

        _progress.value = 0f
        downloadJob = scope.launch {
            UpdateManager.downloadApk(
                context = context,
                downloadUrl = version.google_link,
                expectedSha256 = version.sha256,
                onProgress = { _progress.value = it },
                onComplete = { file ->
                    _progress.value = null
                    _downloadedFile.value = file
                },
                onError = { err ->
                    _progress.value = null
                    com.neveroff.ui.components.AppToast.error("Download failed: $err")
                }
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _progress.value = null
    }

    /** No-op unless the user opted in; called whenever an update becomes known. */
    fun maybeAutoDownload(context: Context) {
        if (!SettingsManager.autoDownloadUpdatesFlow.value) return
        if (_available.value == null) return
        if (_downloadedFile.value != null || downloadJob?.isActive == true) return
        startDownload(context)
    }
}
