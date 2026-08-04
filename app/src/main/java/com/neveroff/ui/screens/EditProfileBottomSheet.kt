package com.neveroff.ui.screens

import com.neveroff.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileBottomSheet(
    isVisible: Boolean,
    profile: Profile?,
    onDismiss: () -> Unit,
    onSave: (displayName: String, bio: String) -> Unit,
    onPickAvatar: () -> Unit
) {
    if (!isVisible || profile == null) return

    var displayName by remember(profile) { mutableStateOf(profile.display_name) }
    var bio by remember(profile) { mutableStateOf(profile.bio ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // imePadding BEFORE verticalScroll. Applied after, the padding
                // becomes part of the scrollable content and simply scrolls
                // away with it, leaving the viewport the same height and the
                // field you are typing in still behind the keyboard. Applied
                // here it insets the viewport itself, which is what actually
                // moves the bio field into view.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.edit_profile_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            // Avatar Edit
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(com.neveroff.ui.theme.AccentGradient),
                        shape = CircleShape
                    )
                    .padding(2.dp)
                    .clip(CircleShape)
                    .clickable { onPickAvatar() },
                contentAlignment = Alignment.Center
            ) {
                if (profile.avatar_url != null) {
                    AsyncImage(
                        model = profile.avatar_url,
                        contentDescription = stringResource(R.string.avatar),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.display_name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                // Dark overlay with icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.change_avatar),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.label_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource(R.string.label_about)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onSave(displayName, bio) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                enabled = displayName.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
            }
        }
    }
}
