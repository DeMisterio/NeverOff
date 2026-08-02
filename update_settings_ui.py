import re

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "r") as f:
    content = f.read()

ui_code = """
            Spacer(modifier = Modifier.height(24.dp))
            val textSize by SettingsManager.bubbleTextSizeFlow.collectAsState()
            Text("Размер текста: ${textSize.toInt()}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = textSize,
                onValueChange = { SettingsManager.setBubbleTextSize(it) },
                valueRange = 10f..30f
            )

            Spacer(modifier = Modifier.height(24.dp))
            val colorOptions = listOf(0xFF9C27B0, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFFD32F2F, 0xFF3F51B5, 0xFF512DA8)
            val myBubbleColor by SettingsManager.myBubbleColorFlow.collectAsState()
            Text("Цвет твоих сообщений", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colorOptions.forEach { colorVal ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(40.dp)
                            .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.CircleShape)
                            .androidx.compose.foundation.background(androidx.compose.ui.graphics.Color(colorVal))
                            .androidx.compose.foundation.border(
                                width = if (myBubbleColor == colorVal) 3.dp else 0.dp,
                                color = if (myBubbleColor == colorVal) MaterialTheme.colorScheme.onBackground else androidx.compose.ui.graphics.Color.Transparent,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .androidx.compose.foundation.clickable { SettingsManager.setMyBubbleColor(colorVal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            val otherBubbleColor by SettingsManager.otherBubbleColorFlow.collectAsState()
            Text("Цвет сообщений собеседника", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colorOptions.forEach { colorVal ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(40.dp)
                            .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.CircleShape)
                            .androidx.compose.foundation.background(androidx.compose.ui.graphics.Color(colorVal))
                            .androidx.compose.foundation.border(
                                width = if (otherBubbleColor == colorVal) 3.dp else 0.dp,
                                color = if (otherBubbleColor == colorVal) MaterialTheme.colorScheme.onBackground else androidx.compose.ui.graphics.Color.Transparent,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .androidx.compose.foundation.clickable { SettingsManager.setOtherBubbleColor(colorVal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
"""

content = content.replace("            Spacer(modifier = Modifier.height(24.dp))\n            HorizontalDivider()", ui_code)

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "w") as f:
    f.write(content)
