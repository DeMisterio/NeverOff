package com.neveroff.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LoggerManager {
    private val actionLogs = mutableListOf<JSONObject>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logAction(action: String, details: Map<String, Any> = emptyMap()) {
        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("action", action)
                details.forEach { (k, v) -> put(k, v) }
            }
            actionLogs.add(json)
            // Limit in-memory logs to prevent OOM
            if (actionLogs.size > 1000) {
                actionLogs.removeAt(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun saveLogsToDisk(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // Internal storage, not getExternalFilesDir. The "not world-readable" property that
            // motivated external storage only holds from API 29 on; below that any app with
            // READ_EXTERNAL_STORAGE can read app-specific external dirs, and this app supports
            // API 26. Logs carry stack traces and exception messages, so they stay in the sandbox.
            val logsDir = File(context.applicationContext.filesDir, "NeverOffLogs")
            if (!logsDir.exists()) logsDir.mkdirs()

            val fileName = "actions_${dateFormat.format(Date())}.json"
            val file = File(logsDir, fileName)

            val jsonArray = JSONArray(actionLogs)
            file.writeText(jsonArray.toString(4))

            // Optionally clear after saving
            actionLogs.clear()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            logError("LoggerManager", e)
            false
        }
    }

    fun logError(category: String, exception: Throwable) {
        try {
            if (!this::appContext.isInitialized) return
            val logsDir = File(appContext.filesDir, "NeverOffLogs/Errors/$category")
            if (!logsDir.exists()) logsDir.mkdirs()

            val fileName = "error_${dateFormat.format(Date())}.json"
            val file = File(logsDir, fileName)

            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("category", category)
                put("message", exception.message)
                put("stackTrace", exception.stackTraceToString())
            }

            // Run in separate thread to not block UI since errors can happen anywhere
            Thread {
                try {
                    file.writeText(json.toString(4))
                } catch (e: Exception) {
                    Log.e("LoggerManager", "Failed to write error log", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("LoggerManager", "Failed to construct error log", e)
        }
    }

    fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            logError("Crash", exception)
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
}
