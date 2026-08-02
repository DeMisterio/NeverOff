with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "r") as f:
    content = f.read()

content = content.replace("androidx.compose.ui.graphics.Color(colorVal)", "Color(colorVal)")
content = content.replace("androidx.compose.foundation.shape.CircleShape", "CircleShape")
content = content.replace("Text(t)", "Text(text = t)")

with open("app/src/main/java/com/neveroff/ui/screens/SettingsScreen.kt", "w") as f:
    f.write(content)
