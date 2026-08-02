with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Add fullScreenMessage state
state_search = """    var isRecordingLocked by remember { mutableStateOf(false) }"""
state_replace = """    var isRecordingLocked by remember { mutableStateOf(false) }
    var fullScreenMessage by remember { mutableStateOf<UiMessage?>(null) }"""

content = content.replace(state_search, state_replace)

# Modify LazyColumn call
call_search = """MessageBubble(msg, uiMessages, finalRemoteUserName, onReply = { replyToId = msg.id }, onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: "") })"""
call_replace = """MessageBubble(msg, uiMessages, finalRemoteUserName, onReply = { replyToId = msg.id }, onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: "") }, onFullScreen = { fullScreenMessage = it })"""

content = content.replace(call_search, call_replace)

# Modify MessageBubble signature
sig_search = """fun MessageBubble(msg: UiMessage, allMessages: List<UiMessage>, remoteName: String, onReply: () -> Unit, onDelete: (String) -> Unit) {"""
sig_replace = """fun MessageBubble(msg: UiMessage, allMessages: List<UiMessage>, remoteName: String, onReply: () -> Unit, onDelete: (String) -> Unit, onFullScreen: (UiMessage) -> Unit) {"""

content = content.replace(sig_search, sig_replace)

# Modify MediaMessagePlayer onClick
player_search = """                    MediaMessagePlayer(
                        messageText = msg.text,
                        textColor = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        onClickFullScreen = {}
                    )"""
player_replace = """                    MediaMessagePlayer(
                        messageText = msg.text,
                        textColor = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        onClickFullScreen = { onFullScreen(msg) }
                    )"""

content = content.replace(player_search, player_replace)

# Add FullScreenMediaViewer overlay at the bottom of the file right before ChatScreen ends
overlay_search = """    if (showSettingsSheet) {"""
overlay_replace = """    fullScreenMessage?.let { msg ->
        com.neveroff.ui.components.FullScreenMediaViewer(
            uri = android.net.Uri.parse(msg.text.replace("[IMAGE]", "").replace("[VIDEO]", "").replace("[VOICE]", "")),
            message = msg,
            onDismiss = { fullScreenMessage = null },
            onReply = { replyToId = msg.id; fullScreenMessage = null },
            onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: ""); fullScreenMessage = null }
        )
    }

    if (showSettingsSheet) {"""

content = content.replace(overlay_search, overlay_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
