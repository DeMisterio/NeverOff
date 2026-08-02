package com.neveroff.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.neveroff.data.local.AppDatabase
import com.neveroff.data.local.RecentSearchEntity
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.res.stringResource
import com.neveroff.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchUserScreen(
    onBack: () -> Unit,
    onUserSelected: (Profile) -> Unit,
    onCreateChannel: () -> Unit = {},
    onChannelSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val myId = remember { com.neveroff.util.CurrentUser.id(context) }
    val channelRepository = remember(myId) {
        com.neveroff.data.repository.ChannelRepository(db, myId)
    }

    val recentSearches by db.recentSearchDao().getRecentSearches().collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var channelResults by remember { mutableStateOf<List<com.neveroff.data.model.Channel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun performSearch(query: String) {
        val cleanQuery = query.removePrefix("@")
            .removePrefix(com.neveroff.data.model.ChannelLinkScheme.PREFIX)
            .trim()
        if (cleanQuery.isBlank()) {
            searchResults = emptyList()
            channelResults = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(500) // debounce
            isLoading = true
            try {
                val meId = SupabaseClient.client.auth.currentUserOrNull()?.id
                searchResults = SupabaseClient.client.postgrest["profiles"]
                    .select {
                        filter { ilike("username", "%${cleanQuery}%") }
                    }
                    .decodeList<Profile>()
                    .filter { it.id != meId } // don't show self in results
                // Private channels are unlisted by design, so this only ever
                // returns public ones.
                channelResults = channelRepository.searchChannels(cleanQuery)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    val handleUserSelected: (Profile) -> Unit = { profile ->
        scope.launch {
            db.recentSearchDao().insertRecentSearch(
                RecentSearchEntity(
                    userId = profile.id,
                    username = profile.username,
                    displayName = profile.display_name,
                    avatarUrl = profile.avatar_url,
                    timestamp = System.currentTimeMillis()
                )
            )
            onUserSelected(profile)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_search_users)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                    performSearch(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.hint_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Sits directly under the search field: looking for someone and
            // starting your own broadcast are the two things this screen is for.
            CreateChannelBanner(onClick = onCreateChannel)

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (searchQuery.isBlank()) {
                if (recentSearches.isNotEmpty()) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(recentSearches, key = { it.userId }) { recent ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it == SwipeToDismissBoxValue.EndToStart) {
                                        scope.launch {
                                            db.recentSearchDao().deleteRecentSearch(recent.userId)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        when (dismissState.targetValue) {
                                            SwipeToDismissBoxValue.EndToStart -> Color.Red
                                            else -> Color.Transparent
                                        }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(color)
                                            .padding(end = 16.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                                    }
                                },
                                content = {
                                    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                                        UserListItem(
                                            displayName = recent.displayName,
                                            username = recent.username,
                                            avatarUrl = recent.avatarUrl,
                                            onClick = {
                                                handleUserSelected(
                                                    Profile(
                                                        id = recent.userId,
                                                        username = recent.username,
                                                        display_name = recent.displayName,
                                                        avatar_url = recent.avatarUrl,
                                                        public_key = "" // Not needed for selection, chat list handles pubkey
                                                    )
                                                )
                                            }
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false
                            )
                        }
                    }
                }
            } else if (searchResults.isEmpty() && channelResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_search), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (channelResults.isNotEmpty()) {
                        item(key = "channels_header") {
                            SearchSectionHeader("Channels")
                        }
                        items(channelResults, key = { "channel_${it.id}" }) { channel ->
                            ChannelSearchItem(
                                channel = channel,
                                onClick = { onChannelSelected(channel.id) }
                            )
                        }
                    }
                    if (searchResults.isNotEmpty()) {
                        if (channelResults.isNotEmpty()) {
                            item(key = "people_header") { SearchSectionHeader("People") }
                        }
                        items(searchResults, key = { "user_${it.id}" }) { profile ->
                            UserListItem(
                                displayName = profile.display_name,
                                username = profile.username,
                                avatarUrl = profile.avatar_url,
                                onClick = { handleUserSelected(profile) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Entry point to channel creation. It sits in search rather than behind a menu
 * because "find someone" and "start broadcasting" are the same intent — reaching
 * people — and this is the screen you land on to do that.
 */
@Composable
private fun CreateChannelBanner(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Create a channel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Broadcast posts to everyone who subscribes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ChannelSearchItem(channel: com.neveroff.data.model.Channel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.neveroff.ui.components.ChannelAvatar(
            avatarUrl = channel.avatar_url,
            name = channel.name,
            size = 50.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${com.neveroff.data.model.ChannelLinkScheme.linkFor(channel.username)} · " +
                    com.neveroff.ui.components.subscriberLabel(channel.subscriber_count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UserListItem(displayName: String, username: String, avatarUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "Avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = displayName.takeIf { it.isNotBlank() }?.take(1)?.uppercase() 
                        ?: username.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName.takeIf { it.isNotBlank() } ?: username,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "@${username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
