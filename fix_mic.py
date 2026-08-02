with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

content = content.replace("import androidx.compose.material.icons.filled.Mic", "")
content = content.replace("Icons.Default.Mic", "Icons.Default.Face")

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
