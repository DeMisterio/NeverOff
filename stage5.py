with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Add selection state
state_search = """    var replyToId by remember { mutableStateOf<String?>(null) }"""
state_replace = """    var replyToId by remember { mutableStateOf<String?>(null) }
    val selectedMessages = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val isSelectionMode = selectedMessages.isNotEmpty()"""
content = content.replace(state_search, state_replace)

# Replace TopAppBar
topbar_search = """        topBar = {
            TopAppBar(
                title = { Text(if (chatId == "test_chat") "Settings Preview" else finalRemoteUserName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.MoreVert, "Settings")
                    }
                }
            )
        },"""

topbar_replace = """        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedMessages.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedMessages.clear() }) { Icon(Icons.Default.Close, "Cancel Selection") }
                    },
                    actions = {
                        IconButton(onClick = { 
                            selectedMessages.forEach { viewModel.deleteMessageForEveryone(it) }
                            selectedMessages.clear()
                        }) {
                            Icon(Icons.Default.Delete, "Delete Selected")
                        }
                    },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            } else {
                TopAppBar(
                    title = { Text(if (chatId == "test_chat") "Settings Preview" else finalRemoteUserName) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.MoreVert, "Settings")
                        }
                    }
                )
            }
        },"""

if "if (isSelectionMode) {" not in content:
    content = content.replace(topbar_search, topbar_replace)

# Change MessageBubble call in LazyColumn
lazy_col_search = """MessageBubble(msg, uiMessages, finalRemoteUserName, onReply = { replyToId = msg.id }, onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: "") }, onFullScreen = { fullScreenMessage = it })"""
lazy_col_replace = """MessageBubble(
                    msg = msg, 
                    allMessages = uiMessages, 
                    remoteName = finalRemoteUserName, 
                    onReply = { replyToId = msg.id }, 
                    onDelete = { viewModel.deleteMessageForEveryone(msg.id ?: "") }, 
                    onFullScreen = { fullScreenMessage = it },
                    isSelected = selectedMessages.contains(msg.id),
                    isSelectionMode = isSelectionMode,
                    onSelect = { 
                        if (msg.id != null) {
                            if (selectedMessages.contains(msg.id)) selectedMessages.remove(msg.id) 
                            else selectedMessages.add(msg.id) 
                        }
                    }
                )"""
content = content.replace(lazy_col_search, lazy_col_replace)

# Change MessageBubble signature
sig_search = """    onDelete: (String) -> Unit,
    onFullScreen: (UiMessage) -> Unit
) {"""
sig_replace = """    onDelete: (String) -> Unit,
    onFullScreen: (UiMessage) -> Unit,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onSelect: () -> Unit = {}
) {"""
content = content.replace(sig_search, sig_replace)

# Change RoundedCornerShapes
content = content.replace("RoundedCornerShape(percent = 50)", "RoundedCornerShape(20.dp)")
# Exception: Input bar can stay 24.dp
content = content.replace("Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))", "Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))")

# Add combinedClickable to outer Box in MessageBubble
bubble_box_search = """    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).scale(scale).alpha(alpha),
        contentAlignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {"""
bubble_box_replace = """    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).scale(scale).alpha(alpha)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .androidx.compose.foundation.ExperimentalFoundationApi::class.let {
                androidx.compose.foundation.combinedClickable(
                    onClick = { if (isSelectionMode) onSelect() },
                    onLongClick = { onSelect() }
                )
            },
        contentAlignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {"""
# Note: Kotlin has `Modifier.combinedClickable`. Need `@OptIn(ExperimentalFoundationApi::class)`.
content = content.replace("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun ChatScreen", "@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)\n@Composable\nfun ChatScreen")

bubble_box_replace = """    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).scale(scale).alpha(alpha)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
            .androidx.compose.foundation.combinedClickable(
                onClick = { if (isSelectionMode) onSelect() },
                onLongClick = { onSelect() }
            ),
        contentAlignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {"""
content = content.replace(bubble_box_search, bubble_box_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
