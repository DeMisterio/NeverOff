package com.neveroff.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.crypto.CryptoManager
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.stringResource
import com.neveroff.R
import com.neveroff.util.toUserMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@SerialName("Profile")
private class ProfileSurrogate(
    val id: String,
    val username: String? = null,
    val display_name: String? = null,
    val bio: String? = null,
    val avatar_url: String? = null,
    val public_key: String? = null
)

object ProfileSerializer : KSerializer<Profile> {
    override val descriptor: SerialDescriptor = ProfileSurrogate.serializer().descriptor
    override fun deserialize(decoder: Decoder): Profile {
        val surrogate = decoder.decodeSerializableValue(ProfileSurrogate.serializer())
        return Profile(
            id = surrogate.id,
            username = surrogate.username ?: "",
            display_name = surrogate.display_name ?: "Me",
            bio = surrogate.bio,
            avatar_url = surrogate.avatar_url,
            public_key = surrogate.public_key ?: ""
        )
    }
    override fun serialize(encoder: Encoder, value: Profile) {
        val surrogate = ProfileSurrogate(
            id = value.id,
            username = value.username,
            display_name = value.display_name,
            bio = value.bio,
            avatar_url = value.avatar_url,
            public_key = value.public_key
        )
        encoder.encodeSerializableValue(ProfileSurrogate.serializer(), surrogate)
    }
}

@Serializable(with = ProfileSerializer::class)
data class Profile(
    val id: String,
    val username: String,
    val display_name: String,
    val bio: String? = null,
    val avatar_url: String? = null,
    val public_key: String
)

@Composable
fun ProfileSetupScreen(onComplete: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AuthGradientBackground {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (displayName.ifBlank { username }).take(1).ifBlank { "?" }.uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(stringResource(R.string.title_profile_setup), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.subtitle_profile_setup),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))

        val textFieldColors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
        Column(modifier = Modifier.padding(20.dp)) {
        TextField(
            value = username,
            onValueChange = { username = it.removePrefix("@") },
            placeholder = { Text(stringResource(R.string.hint_username)) },
            leadingIcon = { Text("@", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = textFieldColors,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = displayName,
            onValueChange = { displayName = it },
            placeholder = { Text(stringResource(R.string.hint_display_name)) },
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = textFieldColors,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = bio,
            onValueChange = { bio = it },
            placeholder = { Text(stringResource(R.string.hint_bio)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            shape = shape,
            colors = textFieldColors
        )
        }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    try {
                        val userId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return@launch
                        val publicKey = CryptoManager.getPublicKeyBase64()
                        val existing = SupabaseClient.client.postgrest["profiles"]
                            .select {
                                filter { eq("username", username) }
                            }.decodeList<Profile>()
                            
                        if (existing.isNotEmpty()) {
                            com.neveroff.ui.components.AppToast.error(
                                context.getString(R.string.error_already_taken)
                            )
                            isLoading = false
                            return@launch
                        }

                        val profile = Profile(
                            id = userId,
                            username = username,
                            display_name = displayName,
                            bio = bio,
                            public_key = publicKey
                        )
                        
                        SupabaseClient.client.postgrest["profiles"].insert(profile)
                        
                        val prefs = context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
                        com.neveroff.util.SecurePrefs.putString(prefs, "profile_id", profile.id)
                        com.neveroff.util.SecurePrefs.putString(prefs, "profile_username", profile.username)
                        com.neveroff.util.SecurePrefs.putString(prefs, "profile_display_name", profile.display_name)
                        com.neveroff.util.SecurePrefs.putString(prefs, "profile_bio", profile.bio)
                        com.neveroff.util.SecurePrefs.putString(prefs, "profile_public_key", profile.public_key)

                        onComplete()
                    } catch (e: Exception) {
                        com.neveroff.ui.components.AppToast.error(
                            e.toUserMessage(context, R.string.error_save_failed, "ProfileSetup")
                        )
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            enabled = username.isNotEmpty() && !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = isLoading,
                label = "ProfileSaveButton"
            ) { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.btn_start), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    }
}
