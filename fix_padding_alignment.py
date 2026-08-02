import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Decrease vertical padding of message bubble
old_padding = ".padding(horizontal = 16.dp, vertical = 10.dp)"
new_padding = ".padding(horizontal = 16.dp, vertical = 6.dp)"
content = content.replace(old_padding, new_padding)

# 2. Change alignment of time row
old_alignment = """            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),"""
new_alignment = """            Row(
                modifier = Modifier
                    .align(if (msg.isMine) Alignment.End else Alignment.Start)
                    .padding(top = 4.dp),"""
content = content.replace(old_alignment, new_alignment)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
