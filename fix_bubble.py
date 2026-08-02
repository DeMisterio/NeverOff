import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Add textSize to MessageBubble signature
sig_search = """    myBubbleColor: Long,
    otherBubbleColor: Long
) {"""
sig_replace = """    myBubbleColor: Long,
    otherBubbleColor: Long,
    textSize: Float
) {"""
content = content.replace(sig_search, sig_replace)

# Pass textSize when calling MessageBubble
call_search = """                    myBubbleColor = myBubbleColor,
                    otherBubbleColor = otherBubbleColor,
                    onSelect = {"""
call_replace = """                    myBubbleColor = myBubbleColor,
                    otherBubbleColor = otherBubbleColor,
                    textSize = textSize,
                    onSelect = {"""
content = content.replace(call_search, call_replace)

# Update Text(msg.text, ...)
text_search = """                Text(msg.text, color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)"""
text_replace = """                Text(msg.text, color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))"""
content = content.replace(text_search, text_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
