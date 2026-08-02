import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Replace `val chatThemes = mapOf(...)` with `data class ChatTheme` and `AvailableChatThemes`
old_themes_search = """val chatThemes = mapOf(
    "Default" to listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent),
    "Vintage" to listOf(androidx.compose.ui.graphics.Color(0xFFEFEBE9), androidx.compose.ui.graphics.Color(0xFFD7CCC8)),
    "Modernism" to listOf(androidx.compose.ui.graphics.Color(0xFF232526), androidx.compose.ui.graphics.Color(0xFF414345)),
    "Love" to listOf(androidx.compose.ui.graphics.Color(0xFFff99cc), androidx.compose.ui.graphics.Color(0xFFff6699)),
    "Ocean" to listOf(androidx.compose.ui.graphics.Color(0xFF0F2027), androidx.compose.ui.graphics.Color(0xFF203A43), androidx.compose.ui.graphics.Color(0xFF2C5364)),
    "Forest" to listOf(androidx.compose.ui.graphics.Color(0xFF134E5E), androidx.compose.ui.graphics.Color(0xFF71B280))
)"""

new_themes_code = """data class ChatTheme(
    val name: String,
    val backgroundGradient: List<androidx.compose.ui.graphics.Color>,
    val myBubbleColor: androidx.compose.ui.graphics.Color,
    val otherBubbleColor: androidx.compose.ui.graphics.Color,
    val inputBarColor: androidx.compose.ui.graphics.Color,
    val iconTint: androidx.compose.ui.graphics.Color
)

val AvailableChatThemes = listOf(
    ChatTheme(
        name = "Default",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFF3F51B5),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFF009688),
        inputBarColor = androidx.compose.ui.graphics.Color.Transparent,
        iconTint = androidx.compose.ui.graphics.Color.Gray
    ),
    ChatTheme(
        name = "Vintage",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color(0xFFEFEBE9), androidx.compose.ui.graphics.Color(0xFFD7CCC8)),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFF8D6E63),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFFA1887F),
        inputBarColor = androidx.compose.ui.graphics.Color(0x80D7CCC8),
        iconTint = androidx.compose.ui.graphics.Color(0xFF5D4037)
    ),
    ChatTheme(
        name = "Modernism",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color(0xFF232526), androidx.compose.ui.graphics.Color(0xFF414345)),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFF1E88E5),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFF424242),
        inputBarColor = androidx.compose.ui.graphics.Color(0x80232526),
        iconTint = androidx.compose.ui.graphics.Color(0xFF90CAF9)
    ),
    ChatTheme(
        name = "Love",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color(0xFFff99cc), androidx.compose.ui.graphics.Color(0xFFff6699)),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFFD81B60),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFFF06292),
        inputBarColor = androidx.compose.ui.graphics.Color(0x80ff6699),
        iconTint = androidx.compose.ui.graphics.Color(0xFF880E4F)
    ),
    ChatTheme(
        name = "Ocean",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color(0xFF0F2027), androidx.compose.ui.graphics.Color(0xFF203A43), androidx.compose.ui.graphics.Color(0xFF2C5364)),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFF0277BD),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFF00ACC1),
        inputBarColor = androidx.compose.ui.graphics.Color(0x800F2027),
        iconTint = androidx.compose.ui.graphics.Color(0xFF4FC3F7)
    ),
    ChatTheme(
        name = "Forest",
        backgroundGradient = listOf(androidx.compose.ui.graphics.Color(0xFF134E5E), androidx.compose.ui.graphics.Color(0xFF71B280)),
        myBubbleColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
        otherBubbleColor = androidx.compose.ui.graphics.Color(0xFF558B2F),
        inputBarColor = androidx.compose.ui.graphics.Color(0x80134E5E),
        iconTint = androidx.compose.ui.graphics.Color(0xFFAED581)
    )
)"""
content = content.replace(old_themes_search, new_themes_code)

# 2. In ChatScreen composition, compute currentTheme
collect_search = """    val myBubbleColor by com.neveroff.util.SettingsManager.myBubbleColorFlow.collectAsState()
    val otherBubbleColor by com.neveroff.util.SettingsManager.otherBubbleColorFlow.collectAsState()
    val textSize by com.neveroff.util.SettingsManager.bubbleTextSizeFlow.collectAsState()
    val chatTheme by com.neveroff.util.SettingsManager.chatThemeFlow.collectAsState()"""
collect_replace = """    val textSize by com.neveroff.util.SettingsManager.bubbleTextSizeFlow.collectAsState()
    val chatTheme by com.neveroff.util.SettingsManager.chatThemeFlow.collectAsState()
    
    val currentTheme = AvailableChatThemes.find { it.name == chatTheme } ?: AvailableChatThemes.first()"""
content = content.replace(collect_search, collect_replace)

# 3. Use currentTheme for Scaffold background
bg_search = """        val currentThemeColors = chatThemes[chatTheme] ?: listOf(Color.Transparent, Color.Transparent)
        val backgroundBrush = if (currentThemeColors.size >= 2 && currentThemeColors[0] != Color.Transparent) {
            androidx.compose.ui.graphics.Brush.verticalGradient(currentThemeColors)
        } else {
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
        }"""
bg_replace = """        val backgroundBrush = if (currentTheme.backgroundGradient.size >= 2 && currentTheme.backgroundGradient[0] != Color.Transparent) {
            androidx.compose.ui.graphics.Brush.verticalGradient(currentTheme.backgroundGradient)
        } else {
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
        }"""
content = content.replace(bg_search, bg_replace)

# 4. Pass currentTheme to MessageBubble instead of myBubbleColor / otherBubbleColor
call_search = """                    isSelectionMode = isSelectionMode,
                    myBubbleColor = myBubbleColor,
                    otherBubbleColor = otherBubbleColor,
                    textSize = textSize,"""
call_replace = """                    isSelectionMode = isSelectionMode,
                    theme = currentTheme,
                    textSize = textSize,"""
content = content.replace(call_search, call_replace)

# 5. Fix Settings Modal Sheet (remove old sliders, apply themes)
settings_search = """                val colorOptions = listOf(0xFF9C27B0, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFFD32F2F, 0xFF3F51B5, 0xFF512DA8)
                androidx.compose.material3.Text("Цвет твоих сообщений", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { colorVal ->
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier.size(40.dp).clip(CircleShape).background(Color(colorVal))
                                .border(width = if (myBubbleColor == colorVal) 3.dp else 0.dp, color = if (myBubbleColor == colorVal) androidx.compose.material3.MaterialTheme.colorScheme.onBackground else Color.Transparent, shape = CircleShape)
                                .clickable { com.neveroff.util.SettingsManager.setMyBubbleColor(colorVal) }
                        )
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                androidx.compose.material3.Text("Цвет сообщений собеседника", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { colorVal ->
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier.size(40.dp).clip(CircleShape).background(Color(colorVal))
                                .border(width = if (otherBubbleColor == colorVal) 3.dp else 0.dp, color = if (otherBubbleColor == colorVal) androidx.compose.material3.MaterialTheme.colorScheme.onBackground else Color.Transparent, shape = CircleShape)
                                .clickable { com.neveroff.util.SettingsManager.setOtherBubbleColor(colorVal) }
                        )
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                androidx.compose.material3.Text("Темы чата", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")
                    items(themeOptions.size) { index ->
                        val theme = themeOptions[index]
                        val colors = chatThemes[theme] ?: listOf(Color.Transparent, Color.Transparent)
                        val brush = if (colors.size >= 2 && colors[0] != Color.Transparent) {
                            androidx.compose.ui.graphics.Brush.verticalGradient(colors)
                        } else {
                            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { com.neveroff.util.SettingsManager.setChatTheme(theme) }) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp, 80.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(brush)
                                    .androidx.compose.foundation.border(
                                        width = if (chatTheme == theme) 2.dp else 0.dp,
                                        color = if (chatTheme == theme) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(theme, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }"""

settings_replace = """                androidx.compose.material3.Text("Темы чата", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    items(AvailableChatThemes.size) { index ->
                        val t = AvailableChatThemes[index]
                        val brush = if (t.backgroundGradient.size >= 2 && t.backgroundGradient[0] != Color.Transparent) {
                            androidx.compose.ui.graphics.Brush.verticalGradient(t.backgroundGradient)
                        } else {
                            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { com.neveroff.util.SettingsManager.setChatTheme(t.name) }) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp, 80.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(brush)
                                    .androidx.compose.foundation.border(
                                        width = if (currentTheme.name == t.name) 2.dp else 0.dp,
                                        color = if (currentTheme.name == t.name) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                            ) {
                                // Mini bubbles
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterStart).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.otherBubbleColor))
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterEnd).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.myBubbleColor))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }"""
content = content.replace(settings_search, settings_replace)

# 6. Settings Preview Box (uses myBubbleColor / otherBubbleColor which we removed)
preview_search = """                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Start).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Color(otherBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет!", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Color(myBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет! Как дела?", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                    }
                }"""

preview_replace = """                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(
                    if (currentTheme.backgroundGradient.size >= 2 && currentTheme.backgroundGradient[0] != Color.Transparent) androidx.compose.ui.graphics.Brush.verticalGradient(currentTheme.backgroundGradient)
                    else androidx.compose.ui.graphics.SolidColor(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
                ).padding(16.dp)) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Start).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(currentTheme.otherBubbleColor).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет!", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(currentTheme.myBubbleColor).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет! Как дела?", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                    }
                }"""
content = content.replace(preview_search, preview_replace)

# 7. Apply theme to input bar
input_bar_search = """                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {"""
input_bar_replace = """                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(currentTheme.inputBarColor)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {"""
content = content.replace(input_bar_search, input_bar_replace)

# Apply icon tint to input bar buttons
add_btn_search = """                        Icon(Icons.Default.Add, contentDescription = "Attach", tint = LocalContentColor.current)"""
add_btn_replace = """                        Icon(Icons.Default.Add, contentDescription = "Attach", tint = if (currentTheme.name == "Default") LocalContentColor.current else currentTheme.iconTint)"""
content = content.replace(add_btn_search, add_btn_replace)

# 8. Modify MessageBubble signature
msg_bubble_search = """    isSelectionMode: Boolean = false,
    onSelect: () -> Unit = {},
    myBubbleColor: Long,
    otherBubbleColor: Long,
    textSize: Float
) {"""
msg_bubble_replace = """    isSelectionMode: Boolean = false,
    onSelect: () -> Unit = {},
    theme: ChatTheme,
    textSize: Float
) {"""
content = content.replace(msg_bubble_search, msg_bubble_replace)

# Update MessageBubble background
bubble_bg_search = """if (msg.isMine) Color(myBubbleColor) else Color(otherBubbleColor)"""
bubble_bg_replace = """if (msg.isMine) theme.myBubbleColor else theme.otherBubbleColor"""
content = content.replace(bubble_bg_search, bubble_bg_replace)

# 9. Ensure no leftover references to myBubbleColor in topbar
topbar_search = """                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )"""
topbar_replace = """                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (currentTheme.name == "Default") MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else currentTheme.inputBarColor
                )"""
content = content.replace(topbar_search, topbar_replace)

# Same for normal topbar
norm_topbar_search = """                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )"""
norm_topbar_replace = """                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = currentTheme.inputBarColor,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )"""
content = content.replace(norm_topbar_search, norm_topbar_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
