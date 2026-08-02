import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Define chatThemes at the top of the file
imports_end_search = """import androidx.compose.foundation.combinedClickable"""
imports_end_replace = """import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color

val chatThemes = mapOf(
    "Default" to listOf(Color.Transparent, Color.Transparent),
    "Vintage" to listOf(Color(0xFFEFEBE9), Color(0xFFD7CCC8)),
    "Modernism" to listOf(Color(0xFF232526), Color(0xFF414345)),
    "Love" to listOf(Color(0xFFff99cc), Color(0xFFff6699)),
    "Ocean" to listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
    "Forest" to listOf(Color(0xFF134E5E), Color(0xFF71B280))
)"""
if "val chatThemes = mapOf(" not in content:
    content = content.replace(imports_end_search, imports_end_replace)

# Fix line 288 (the compile error)
compile_err_search = """        val currentThemeColors = com.neveroff.util.SettingsManager.themes.find { it.first == chatTheme }?.second ?: listOf(Color.Transparent, Color.Transparent)"""
compile_err_replace = """        val currentThemeColors = chatThemes[chatTheme] ?: listOf(Color.Transparent, Color.Transparent)"""
content = content.replace(compile_err_search, compile_err_replace)

# Render mini preview boxes for theme selection
theme_selector_search = """                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")
                    items(themeOptions.size) { index ->
                        val theme = themeOptions[index]
                        androidx.compose.material3.FilterChip(
                            selected = chatTheme == theme,
                            onClick = { com.neveroff.util.SettingsManager.setChatTheme(theme) },
                            label = { androidx.compose.material3.Text(theme) }
                        )
                    }
                }"""

theme_selector_replace = """                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
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
content = content.replace(theme_selector_search, theme_selector_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
