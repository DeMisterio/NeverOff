package com.neveroff.ui.screens

import androidx.compose.ui.res.stringResource

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.neveroff.R
import com.neveroff.data.remote.SupabaseClient
import com.neveroff.util.toUserMessage
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.OTP
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.UnknownHostException

/** Resend unlocks 30s after the screen opens; every later one after a full minute. */
private const val FIRST_RESEND_COOLDOWN = 30
private const val LATER_RESEND_COOLDOWN = 60

@Composable
fun VerifyOtpScreen(email: String, onVerified: () -> Unit) {
    var code by remember { mutableStateOf("") }
    // Verification outlives this composable: a successful verify flips the session to
    // Authenticated, which navigates away and would cancel a rememberCoroutineScope()
    // job mid-flight ("the coroutine scope left the composition").
    val authScope = LocalLifecycleOwner.current.lifecycleScope
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var isResending by remember { mutableStateOf(false) }
    var resendCooldown by remember { mutableStateOf(FIRST_RESEND_COOLDOWN) }

    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown--
        }
    }

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
                contentDescription = stringResource(R.string.neveroff_logo),
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.check_your_email), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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

                authScope.launch {
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
                            // A verify that fails after every OtpType has been tried is
                            // overwhelmingly a wrong or expired code, so that is the
                            // fallback; the classifier still catches "no network" first.
                            val error = lastError ?: IllegalStateException("verify failed")
                            com.neveroff.ui.components.AppToast.error(
                                error.toUserMessage(context, R.string.error_invalid_code, "VerifyOtp")
                            )
                        }
                    } catch (e: Exception) {
                        com.neveroff.ui.components.AppToast.error(
                            e.toUserMessage(context, R.string.error_invalid_code, "VerifyOtp")
                        )
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
                            contentDescription = stringResource(R.string.success),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    else -> {
                        Text(stringResource(R.string.verify_code), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                val targetEmail = email.trim()
                authScope.launch {
                    isResending = true
                    try {
                        SupabaseClient.client.auth.signInWith(OTP) {
                            this.email = targetEmail
                        }
                        code = ""
                        resendCooldown = LATER_RESEND_COOLDOWN
                        com.neveroff.ui.components.AppToast.show("A new code is on its way to $targetEmail")
                    } catch (e: Exception) {
                        // Supabase throttles OTP sends server-side; back off until the
                        // next window. The wording itself comes from the classifier.
                        val raw = e.message.orEmpty()
                        if (raw.contains("security purposes", ignoreCase = true) ||
                            raw.contains("rate limit", ignoreCase = true)
                        ) {
                            resendCooldown = LATER_RESEND_COOLDOWN
                        }
                        com.neveroff.ui.components.AppToast.error(
                            e.toUserMessage(context, R.string.error_resend_code, "VerifyOtp")
                        )
                    } finally {
                        isResending = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = resendCooldown == 0 && !isResending && !isLoading && !isSuccess
        ) {
            Text(
                text = when {
                    isResending -> "Sending..."
                    resendCooldown > 0 -> "Resend code in ${resendCooldown}s"
                    else -> "Resend code"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
        }
        }
    }
    }
}
