import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Remove lock and temp icons
icons_search = """                    BounceIconButton(onClick = { isLockedMode = !isLockedMode }) { Icon(Icons.Default.Lock, "Lock", tint = if (isLockedMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    BounceIconButton(onClick = { isTempMode = !isTempMode }) { Icon(Icons.Default.Close, "Temp", tint = if (isTempMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }"""
content = content.replace(icons_search, "")

# 2. Fix FullScreenMediaViewer URI
state_search = """    var fullScreenMessage by remember { mutableStateOf<UiMessage?>(null) }"""
state_replace = """    var fullScreenMessage by remember { mutableStateOf<UiMessage?>(null) }
    var fullScreenMediaUri by remember { mutableStateOf<android.net.Uri?>(null) }"""
content = content.replace(state_search, state_replace)

overlay_search = """    fullScreenMessage?.let { msg ->
        com.neveroff.ui.components.FullScreenMediaViewer(
            uri = android.net.Uri.parse(msg.text.replace("[IMAGE]", "").replace("[VIDEO]", "").replace("[VOICE]", "")),
            message = msg,"""
overlay_replace = """    if (fullScreenMessage != null && fullScreenMediaUri != null) {
        val msg = fullScreenMessage!!
        com.neveroff.ui.components.FullScreenMediaViewer(
            uri = fullScreenMediaUri!!,
            message = msg,"""
content = content.replace(overlay_search, overlay_replace)

lazy_col_search = """onFullScreen = { fullScreenMessage = it }"""
lazy_col_replace = """onFullScreen = { msg, uri -> fullScreenMessage = msg; fullScreenMediaUri = uri }"""
content = content.replace(lazy_col_search, lazy_col_replace)

bubble_sig_search = """    onFullScreen: (UiMessage) -> Unit,"""
bubble_sig_replace = """    onFullScreen: (UiMessage, android.net.Uri) -> Unit,"""
content = content.replace(bubble_sig_search, bubble_sig_replace)

player_search = """onClickFullScreen = { onFullScreen(msg) }"""
player_replace = """onClickFullScreen = { uri -> onFullScreen(msg, uri) }"""
content = content.replace(player_search, player_replace)

# 3. Add myBubbleColor, otherBubbleColor to MessageBubble signature and pass them
bubble_sig2_search = """    onSelect: () -> Unit = {}
) {"""
bubble_sig2_replace = """    onSelect: () -> Unit = {},
    myBubbleColor: Long,
    otherBubbleColor: Long
) {"""
content = content.replace(bubble_sig2_search, bubble_sig2_replace)

lazy_col_call_search = """isSelectionMode = isSelectionMode,
                    onSelect = { """
lazy_col_call_replace = """isSelectionMode = isSelectionMode,
                    myBubbleColor = myBubbleColor,
                    otherBubbleColor = otherBubbleColor,
                    onSelect = { """
content = content.replace(lazy_col_call_search, lazy_col_call_replace)

# Fix MessageBubble colors
color1_search = """if (msg.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer"""
color1_replace = """if (msg.isMine) Color(myBubbleColor) else Color(otherBubbleColor)"""
content = content.replace(color1_search, color1_replace)
# Note: color1_search matches the .background() modifier, the Text() colors, Icon colors etc. Wait, we ONLY want to change the background. The text color can remain `onPrimary` and `onSecondaryContainer`. Let's be precise.

# First, undo color1_replace if it's too broad. Actually, I'll use regex.
# I want to replace `.background(if (msg.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)`
content = content.replace(".background(if (msg.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)", ".background(if (msg.isMine) Color(myBubbleColor) else Color(otherBubbleColor))")

# 4. Add chatTheme background to Scaffold body
scaffold_body_search = """    ) { padding ->
        if (isRecordingVideo) {"""

# Themes definition from SettingsManager
# Since we just get a string "chatTheme" like "Ocean", we need to parse it to a Brush or just apply it. Wait, `com.neveroff.util.SettingsManager.themes` is available!
scaffold_body_replace = """    ) { padding ->
        val currentThemeColors = com.neveroff.util.SettingsManager.themes.find { it.first == chatTheme }?.second ?: listOf(Color.Transparent, Color.Transparent)
        val backgroundBrush = if (currentThemeColors.size >= 2 && currentThemeColors[0] != Color.Transparent) {
            androidx.compose.ui.graphics.Brush.verticalGradient(currentThemeColors)
        } else {
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
        }
        
        Box(modifier = Modifier.fillMaxSize().background(backgroundBrush)) {
        if (isRecordingVideo) {"""

end_scaffold_search = """                MessageBubble(
                    msg = msg, """
# Need to close the Box at the end of Scaffold.
# The Scaffold ends after LazyColumn.
lazy_col_end_search = """        }
    }

    fullScreenMessage?.let { msg ->"""
lazy_col_end_replace = """        }
        } // End of Box with background Brush
    }

    if (fullScreenMessage != null && fullScreenMediaUri != null) {"""
content = content.replace(lazy_col_end_search, lazy_col_end_replace)

content = content.replace(scaffold_body_search, scaffold_body_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
