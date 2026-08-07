package com.neveroff.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.data.model.isValidChannelUsername
import com.neveroff.data.model.sanitizeChannelUsername
import com.neveroff.data.repository.UserUsername
import com.neveroff.data.repository.UsernameRepository
import com.neveroff.ui.components.AppToast
import com.neveroff.util.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class NameCheck { EMPTY, TOO_SHORT, CHECKING, AVAILABLE, TAKEN }

/**
 * Add, remove and re-order the handles that point at this account.
 *
 * Giving one up does not free it. The claim is permanent, so a handle you stop
 * using cannot be registered by anyone else and start receiving messages meant
 * for you — the screen says so, because "remove" otherwise reads like
 * "release".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernamesScreen(
    repository: UsernameRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var usernames by remember { mutableStateOf<List<UserUsername>?>(null) }
    var draft by remember { mutableStateOf("") }
    var check by remember { mutableStateOf(NameCheck.EMPTY) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() { usernames = repository.myUsernames() }

    LaunchedEffect(Unit) { refresh() }

    // Debounced, so the field does not query on every keystroke and never
    // reports on a value the user has already typed past.
    LaunchedEffect(draft) {
        check = when {
            draft.isEmpty() -> NameCheck.EMPTY
            !isValidChannelUsername(draft) -> NameCheck.TOO_SHORT
            else -> {
                delay(400)
                if (repository.isAvailable(draft)) NameCheck.AVAILABLE else NameCheck.TAKEN
            }
        }
    }

    val list = usernames
    val atLimit = (list?.size ?: 0) >= UsernameRepository.MAX_ACTIVE
    val canAdd = check == NameCheck.AVAILABLE && !atLimit && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usernames_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.usernames_explain, UsernameRepository.MAX_ACTIVE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            if (list == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                list.forEach { entry ->
                    UsernameRow(
                        entry = entry,
                        enabled = !busy,
                        onMakePrimary = {
                            scope.launch {
                                busy = true
                                repository.setPrimary(entry.id)
                                    .onSuccess { AppToast.success(context.getString(R.string.changes_saved)) }
                                    .onFailure {
                                        AppToast.error(it.toUserMessage(context, R.string.error_save_failed, "Usernames"))
                                    }
                                refresh()
                                busy = false
                            }
                        },
                        onRemove = {
                            scope.launch {
                                busy = true
                                repository.removeUsername(entry.id)
                                    .onSuccess { AppToast.success(context.getString(R.string.username_removed)) }
                                    .onFailure {
                                        AppToast.error(
                                            // A refused removal is a rule, not a
                                            // failure: say which rule.
                                            if (entry.is_primary) {
                                                context.getString(R.string.username_primary_cannot_remove)
                                            } else {
                                                it.toUserMessage(context, R.string.error_save_failed, "Usernames")
                                            }
                                        )
                                    }
                                refresh()
                                busy = false
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.usernames_add),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = sanitizeChannelUsername(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                prefix = { Text("@", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                isError = check == NameCheck.TAKEN || check == NameCheck.TOO_SHORT,
                trailingIcon = {
                    when (check) {
                        NameCheck.CHECKING -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                        )
                        NameCheck.AVAILABLE -> Icon(
                            Icons.Default.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        NameCheck.TAKEN -> Icon(
                            Icons.Default.Close, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        else -> {}
                    }
                },
                supportingText = {
                    val message = when {
                        atLimit -> stringResource(R.string.usernames_at_limit, UsernameRepository.MAX_ACTIVE)
                        check == NameCheck.TOO_SHORT -> stringResource(R.string.usernames_format)
                        check == NameCheck.TAKEN -> stringResource(R.string.usernames_taken)
                        check == NameCheck.AVAILABLE -> stringResource(R.string.usernames_available)
                        else -> stringResource(R.string.usernames_format)
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (check == NameCheck.TAKEN || atLimit) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        repository.addUsername(draft)
                            .onSuccess {
                                draft = ""
                                AppToast.success(context.getString(R.string.username_added))
                            }
                            .onFailure {
                                AppToast.error(it.toUserMessage(context, R.string.error_save_failed, "Usernames"))
                            }
                        refresh()
                        busy = false
                    }
                },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.usernames_claim), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.usernames_permanent_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UsernameRow(
    entry: UserUsername,
    enabled: Boolean,
    onMakePrimary: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${entry.username}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (entry.is_primary) {
                    Text(
                        text = stringResource(R.string.username_primary_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!entry.is_primary) {
                TextButton(onClick = onMakePrimary, enabled = enabled) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.username_make_primary))
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.username_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}
