package com.neveroff.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.crypto.KeyBackup
import com.neveroff.data.remote.KeyBackupRepository
import com.neveroff.data.remote.KeyBackupRepository.toBlob
import com.neveroff.ui.components.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setting a recovery passphrase, and restoring from one.
 *
 * Both screens are deliberately wordy about irreversibility. This is the one
 * place in the app where a vague warning costs somebody every message they
 * have — there is no support path that recovers a forgotten passphrase,
 * because a passphrase we could reset would be one an attacker could reset.
 */

@Composable
fun SetBackupPassphraseScreen(
    userId: String,
    hasExistingBackup: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AuthGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text(
                    text = stringResource(
                        if (hasExistingBackup) R.string.backup_change else R.string.backup_set
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.backup_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.backup_warning),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; error = null },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = repeat,
                onValueChange = { repeat = it; error = null },
                label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                isError = error != null,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    when {
                        passphrase.length < KeyBackup.MIN_PASSPHRASE_LENGTH ->
                            error = context.getString(
                                R.string.backup_too_short, KeyBackup.MIN_PASSPHRASE_LENGTH
                            )
                        passphrase != repeat ->
                            error = context.getString(R.string.backup_mismatch)
                        else -> {
                            working = true
                            scope.launch {
                                val ok = runCatching {
                                    // Key derivation is 210k PBKDF2 rounds by
                                    // design; running it on the main thread
                                    // would freeze the UI for seconds.
                                    val blob = withContext(Dispatchers.Default) {
                                        KeyBackup.create(passphrase.toCharArray())
                                    }
                                    KeyBackupRepository.save(userId, blob)
                                }.isSuccess
                                working = false
                                if (ok) {
                                    AppToast.success(context.getString(R.string.backup_saved))
                                    onDone()
                                } else {
                                    // Never report success on a failed upload:
                                    // the whole value of this screen is the
                                    // user believing they are covered, and they
                                    // must only believe it when they are.
                                    error = context.getString(R.string.backup_failed)
                                }
                            }
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.btn_save))
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * The Settings entry point: current backup state, and a way to set or withdraw it.
 *
 * Shows the "off" state prominently rather than hiding it, because the default
 * is the dangerous one — a user with no backup has no idea their history is one
 * uninstall away from gone until it is.
 */
@Composable
fun ChatBackupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userId = remember { com.neveroff.util.CurrentUser.id(context) }

    var backedUpAt by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(userId) {
        if (userId.isNotBlank()) {
            backedUpAt = KeyBackupRepository.fetch(userId)?.updated_at
        }
        loaded = true
    }

    if (showSetup) {
        SetBackupPassphraseScreen(
            userId = userId,
            hasExistingBackup = backedUpAt != null,
            onDone = {
                showSetup = false
                scope.launch { backedUpAt = KeyBackupRepository.fetch(userId)?.updated_at }
            },
            onBack = { showSetup = false }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            Text(
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = backedUpAt
                ?.let { stringResource(R.string.backup_subtitle_on, it.take(10)) }
                ?: stringResource(R.string.backup_subtitle_off),
            style = MaterialTheme.typography.bodySmall,
            color = if (backedUpAt == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(onClick = { showSetup = true }, enabled = loaded && userId.isNotBlank()) {
            Text(
                stringResource(
                    if (backedUpAt != null) R.string.backup_change else R.string.backup_set
                )
            )
        }

        if (backedUpAt != null) {
            TextButton(onClick = {
                scope.launch {
                    runCatching { KeyBackupRepository.delete(userId) }
                        .onSuccess {
                            backedUpAt = null
                            AppToast.show(context.getString(R.string.backup_removed))
                        }
                }
            }) {
                Text(
                    stringResource(R.string.backup_remove),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Shown when this device holds no identity key but the account has a backup.
 *
 * Skippable on purpose. Somebody who genuinely cannot remember the passphrase
 * still needs to use the app, and trapping them on this screen would turn a
 * lost history into a lost account.
 */
@Composable
fun RestoreFromBackupScreen(
    userId: String,
    onRestored: () -> Unit,
    onSkip: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AuthGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.restore_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.restore_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; error = null },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                isError = error != null,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    working = true
                    error = null
                    scope.launch {
                        val result = runCatching {
                            val row = KeyBackupRepository.fetch(userId)
                                ?: throw IllegalStateException("no backup")
                            withContext(Dispatchers.Default) {
                                KeyBackup.restore(row.toBlob(), passphrase.toCharArray())
                            }
                        }
                        working = false
                        if (result.isSuccess) {
                            AppToast.success(context.getString(R.string.restore_done))
                            onRestored()
                        } else {
                            error = context.getString(R.string.restore_wrong)
                        }
                    }
                },
                enabled = !working && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (working) {
                    Text(stringResource(R.string.restore_working))
                } else {
                    Text(stringResource(R.string.restore_action))
                }
            }

            Text(
                text = stringResource(R.string.restore_skip_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.restore_skip))
            }
        }
    }
}
