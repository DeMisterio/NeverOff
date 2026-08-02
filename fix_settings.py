with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "r") as f:
    content = f.read()

content = content.replace("androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.CircleShape)", "clip(androidx.compose.foundation.shape.CircleShape)")
content = content.replace("androidx.compose.foundation.background", "background")
content = content.replace("androidx.compose.foundation.border", "border")
content = content.replace("androidx.compose.foundation.clickable", "clickable")
content = content.replace("androidx.compose.foundation.layout.Box", "Box")
content = content.replace("androidx.compose.foundation.lazy.LazyRow", "androidx.compose.foundation.lazy.LazyRow")

content = content.replace("androidx.compose.foundation.lazy.items(themeOptions) { t ->", "items(themeOptions) { t ->")

if "import androidx.compose.ui.draw.clip" not in content:
    content = content.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.clip\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.border\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.LazyRow")

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "w") as f:
    f.write(content)
