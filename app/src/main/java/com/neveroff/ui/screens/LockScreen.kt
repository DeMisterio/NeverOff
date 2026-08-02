package com.neveroff.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import java.security.MessageDigest

fun String.sha256(): String {
    val bytes = this.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("", { str, it -> str + "%02x".format(it) })
}

private const val PBKDF2_ITERATIONS = 120_000
private const val PBKDF2_KEY_LENGTH_BITS = 256

fun generatePinSalt(): String {
    val salt = ByteArray(16)
    java.security.SecureRandom().nextBytes(salt)
    return android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
}

fun String.pbkdf2Hash(saltBase64: String): String {
    val salt = android.util.Base64.decode(saltBase64, android.util.Base64.NO_WRAP)
    val spec = javax.crypto.spec.PBEKeySpec(this.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val hash = factory.generateSecret(spec).encoded
    return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
}

enum class LockMode {
    SETUP,
    UNLOCK,
    DISABLE
}

@Composable
fun LockScreen(
    mode: LockMode,
    correctPinHash: String? = null,
    correctPinSalt: String? = null,
    onSuccess: (String?, String?) -> Unit, // returns (new PIN hash, new PIN salt) if SETUP, (null, null) otherwise
    onCancel: () -> Unit,
    onEraseChat: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEraseDialog by remember { mutableStateOf(false) }

    BackHandler { onCancel() }

    val canUseBiometrics = remember {
        val biometricManager = BiometricManager.from(context)
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Trigger biometrics on launch if unlocking or disabling
    LaunchedEffect(mode) {
        if ((mode == LockMode.UNLOCK || mode == LockMode.DISABLE) && canUseBiometrics) {
            promptBiometric(context, onSuccess = { onSuccess(null, null) }, onError = {})
        }
    }

    // Handle PIN input
    LaunchedEffect(pin, confirmPin) {
        error = null
        if (!isConfirming && pin.length == 4) {
            if (mode == LockMode.SETUP) {
                isConfirming = true
            } else {
                // Salted PBKDF2 for PINs set after this change; unsalted SHA-256 fallback
                // for PINs set before it (so existing users aren't forced to reset).
                val matches = if (correctPinSalt != null) {
                    pin.pbkdf2Hash(correctPinSalt) == correctPinHash
                } else {
                    pin.sha256() == correctPinHash
                }
                if (matches) {
                    onSuccess(null, null)
                } else {
                    error = "Неверный PIN"
                    delay(500)
                    pin = ""
                }
            }
        } else if (isConfirming && confirmPin.length == 4) {
            if (pin == confirmPin) {
                val newSalt = generatePinSalt()
                onSuccess(pin.pbkdf2Hash(newSalt), newSalt)
            } else {
                error = "PIN-коды не совпадают"
                delay(500)
                pin = ""
                confirmPin = ""
                isConfirming = false
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier
            .matchParentSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .blur(30.dp)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = when (mode) {
                    LockMode.SETUP -> if (isConfirming) "Повторите PIN-код" else "Придумайте PIN-код"
                    LockMode.UNLOCK -> "Введите PIN-код"
                    LockMode.DISABLE -> "Отключить пароль"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = " ",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // PIN Dots
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                val currentInput = if (isConfirming) confirmPin else pin
                for (i in 0 until 4) {
                    val isFilled = i < currentInput.length
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp)
                            .background(
                                color = if (isFilled) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Numpad
            val buttons = listOf(
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "bio", "0", "del"
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in buttons.chunked(3)) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        for (btn in row) {
                            NumpadButton(
                                text = btn,
                                showBiometric = btn == "bio" && canUseBiometrics && (mode == LockMode.UNLOCK || mode == LockMode.DISABLE),
                                onClick = {
                                    when (btn) {
                                        "bio" -> {
                                            if (canUseBiometrics && (mode == LockMode.UNLOCK || mode == LockMode.DISABLE)) {
                                                promptBiometric(context, onSuccess = { onSuccess(null, null) }, onError = {})
                                            }
                                        }
                                        "del" -> {
                                            if (isConfirming && confirmPin.isNotEmpty()) {
                                                confirmPin = confirmPin.dropLast(1)
                                            } else if (!isConfirming && pin.isNotEmpty()) {
                                                pin = pin.dropLast(1)
                                            }
                                        }
                                        else -> {
                                            if (isConfirming && confirmPin.length < 4) {
                                                confirmPin += btn
                                            } else if (!isConfirming && pin.length < 4) {
                                                pin += btn
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(onClick = onCancel) {
                Text("Отмена", color = MaterialTheme.colorScheme.onBackground)
            }

            if (mode == LockMode.UNLOCK || mode == LockMode.DISABLE) {
                TextButton(onClick = { showEraseDialog = true }) {
                    Text("Забыли пароль? Стереть чат", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (showEraseDialog) {
            AlertDialog(
                onDismissRequest = { showEraseDialog = false },
                title = { Text("Стереть чат?") },
                text = { Text("Это удалит всю историю переписки с вашего устройства (только для вас). Восстановить будет невозможно.") },
                confirmButton = {
                    TextButton(onClick = {
                        showEraseDialog = false
                        onEraseChat()
                    }) {
                        Text("Стереть", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEraseDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
fun NumpadButton(text: String, showBiometric: Boolean, onClick: () -> Unit) {
    if (text == "bio" && !showBiometric) {
        Spacer(modifier = Modifier.size(72.dp))
        return
    }

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = androidx.compose.animation.core.tween(150)
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text == "del") {
            Icon(Icons.Default.Clear, contentDescription = "Backspace", tint = MaterialTheme.colorScheme.onSurface)
        } else if (text == "bio") {
            Icon(Icons.Default.Face, contentDescription = "Biometric", tint = MaterialTheme.colorScheme.primary)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun promptBiometric(context: Context, onSuccess: () -> Unit, onError: () -> Unit) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(activity)
    
    val biometricPrompt = BiometricPrompt(activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError()
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError()
            }
        })

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Разблокировка чата")
        .setSubtitle("Приложите палец или посмотрите в камеру")
        .setNegativeButtonText("Использовать PIN")
        .build()

    biometricPrompt.authenticate(promptInfo)
}
