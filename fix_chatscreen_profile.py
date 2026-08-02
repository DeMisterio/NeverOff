import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Add showProfileId
state_search = """    var showSettingsSheet by remember { mutableStateOf(false) }"""
state_replace = """    var showSettingsSheet by remember { mutableStateOf(false) }
    var showProfileId by remember { mutableStateOf<String?>(null) }"""
content = content.replace(state_search, state_replace)

# 2. Add clickable to avatar
avatar_search = """                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {"""
avatar_replace = """                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { chatDetails?.otherUserId?.let { id -> showProfileId = id } },
                            contentAlignment = Alignment.Center
                        ) {"""
content = content.replace(avatar_search, avatar_replace)

# 3. Add ProfileDialog
dialog_search = """    if (showSettingsSheet) {
        androidx.compose.material3.ModalBottomSheet("""
dialog_replace = """    com.neveroff.ui.screens.ProfileDialog(
        isVisible = showProfileId != null,
        profileId = showProfileId ?: "",
        onDismiss = { showProfileId = null },
        onPickAvatar = null,
        selectedAvatarUri = null,
        onAvatarUploaded = {}
    )

    if (showSettingsSheet) {
        androidx.compose.material3.ModalBottomSheet("""
content = content.replace(dialog_search, dialog_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
