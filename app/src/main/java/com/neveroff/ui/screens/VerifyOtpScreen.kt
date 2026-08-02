package com.neveroff.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.UnknownHostException

@Composable
fun VerifyOtpScreen(email: String, onVerified: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }

    AuthGradientBackground {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.neveroff_logo),
                contentDescription = "NeverOff Logo",
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Check your email", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "We've sent a 6-digit code to\n$email",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
        Column(modifier = Modifier.padding(20.dp)) {
        BasicTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it.filter { char -> char.isDigit() } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index ->
                        val char = when {
                            index >= code.length -> ""
                            else -> code[index].toString()
                        }
                        val isFocused = code.length == index
                        val borderColor by animateColorAsState(
                            targetValue = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                            animationSpec = tween(150),
                            label = "otpBoxBorder"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val token = code.trim()
                if (token.length != 6) return@Button

                scope.launch {
                    isLoading = true
                    try {
                        var success = false
                        var lastError: Exception? = null
                        val typesToTry = listOf(
                            OtpType.Email.EMAIL,
                            OtpType.Email.MAGIC_LINK,
                            OtpType.Email.SIGNUP
                        )
                        
                        // Retry loop for DNS / transient network connection recovery
                        for (retryAttempt in 1..2) {
                            for (type in typesToTry) {
                                try {
                                    SupabaseClient.client.auth.verifyEmailOtp(
                                        type = type,
                                        email = email.trim(),
                                        token = token
                                    )
                                    success = true
                                    break
                                } catch (e: Exception) {
                                    lastError = e
                                }
                            }
                            if (success) break
                            if (retryAttempt < 2 && lastError is UnknownHostException) {
                                delay(1000)
                            }
                        }

                        if (success) {
                            isSuccess = true
                            delay(500)
                            onVerified()
                        } else {
                            val rawMsg = lastError?.message ?: ""
                            val userMsg = if (rawMsg.contains("Unable to resolve host", ignoreCase = true) || lastError is UnknownHostException) {
                                "No internet connection. Please check your Wi-Fi, Mobile Data, or VPN and try again."
                            } else {
                                "Error: $rawMsg"
                            }
                            com.neveroff.ui.components.AppToast.error(userMsg)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val rawMsg = e.message ?: ""
                        val userMsg = if (rawMsg.contains("Unable to resolve host", ignoreCase = true) || e is UnknownHostException) {
                            "No internet connection. Please check your Wi-Fi, Mobile Data, or VPN and try again."
                        } else {
                            "Error: $rawMsg"
                        }
                        com.neveroff.ui.components.AppToast.error(userMsg)
                    } finally {
                        if (!isSuccess) isLoading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = code.length == 6 && !isLoading && !isSuccess,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            AnimatedContent(
                targetState = when {
                    isSuccess -> "success"
                    isLoading -> "loading"
                    else -> "idle"
                },
                label = "VerifyButtonAnimation"
            ) { state ->
                when (state) {
                    "loading" -> {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    "success" -> {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    else -> {
                        Text("Verify Code", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        }
        }
    }
    }
}
