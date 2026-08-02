with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Fix imports
content = content.replace("import androidx.compose.material.icons.filled.MoreVert", "import androidx.compose.material.icons.filled.MoreVert\nimport androidx.compose.material.icons.filled.KeyboardArrowUp\nimport androidx.compose.material.icons.filled.Mic")

# Remove the bad injection from MessageBubble
import re
start_idx = content.find("    if (showSettingsSheet) {\n        ModalBottomSheet(")
end_idx = content.find("    if (passwordDialogOpen) {", start_idx)
if start_idx != -1 and end_idx != -1:
    content = content[:start_idx] + content[end_idx:]

# Now place the bottom sheet at the end of the ChatScreen composable!
# Find where ChatScreen ends. ChatScreen ends before `fun MessageBubble`
msg_bubble_idx = content.find("@Composable\nfun MessageBubble(")
if msg_bubble_idx != -1:
    chat_screen_end = content.rfind("}", 0, msg_bubble_idx)
    
    bottom_sheet = """
    if (showSettingsSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())
            ) {
                // Toggles
                androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text("Пароль чата (Lock)", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.Switch(checked = isLockedMode, onCheckedChange = { isLockedMode = it })
                }
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text("Исчезающие сообщения", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.Switch(checked = isTempMode, onCheckedChange = { isTempMode = it })
                }
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))

                // Preview
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Start).clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)).background(Color(otherBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет!", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End).clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)).background(Color(myBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            androidx.compose.material3.Text("Привет! Как дела?", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                    }
                }
                
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                androidx.compose.material3.Text("Размер текста: ${textSize.toInt()}", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Slider(
                    value = textSize,
                    onValueChange = { com.neveroff.util.SettingsManager.setBubbleTextSize(it) },
                    valueRange = 10f..30f
                )

                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                val colorOptions = listOf(0xFF9C27B0, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFFD32F2F, 0xFF3F51B5, 0xFF512DA8)
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
                val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    items(themeOptions) { t ->
                        androidx.compose.material3.FilterChip(
                            selected = chatTheme == t,
                            onClick = { com.neveroff.util.SettingsManager.setChatTheme(t) },
                            label = { androidx.compose.material3.Text(text = t) }
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(32.dp))
            }
        }
    }
"""
    content = content[:chat_screen_end] + bottom_sheet + content[chat_screen_end:]

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
