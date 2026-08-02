import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatListScreen.kt", "r") as f:
    content = f.read()

avatar_search = """                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { onAvatarClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chat.otherUserName.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }"""

avatar_replace = """                var remoteProfile by remember { mutableStateOf<com.neveroff.ui.screens.Profile?>(null) }
                LaunchedEffect(chat.otherUserId) {
                    remoteProfile = com.neveroff.data.cache.ProfileCache.getProfile(chat.otherUserId)
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { onAvatarClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (remoteProfile?.avatar_url != null) {
                        coil.compose.AsyncImage(
                            model = remoteProfile?.avatar_url,
                            contentDescription = "Avatar",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = chat.otherUserName.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }"""
content = content.replace(avatar_search, avatar_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatListScreen.kt", "w") as f:
    f.write(content)
