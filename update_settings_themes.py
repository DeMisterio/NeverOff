import re

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "r") as f:
    content = f.read()

themes_code = """
            Spacer(modifier = Modifier.height(24.dp))
            val chatTheme by SettingsManager.chatThemeFlow.collectAsState()
            Text("Темы чата", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.items(themeOptions) { t ->
                    FilterChip(
                        selected = chatTheme == t,
                        onClick = { SettingsManager.setChatTheme(t) },
                        label = { Text(t) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
"""

content = content.replace("            Spacer(modifier = Modifier.height(24.dp))\n            HorizontalDivider()", themes_code)

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "w") as f:
    f.write(content)
