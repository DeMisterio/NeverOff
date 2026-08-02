package com.neveroff.util

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    val logs = mutableStateListOf<String>()

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // Run on main thread to ensure Compose updates safely
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logs.add(0, "[$time] $msg")
            if (logs.size > 30) {
                logs.removeAt(logs.size - 1)
            }
        }
    }
}
