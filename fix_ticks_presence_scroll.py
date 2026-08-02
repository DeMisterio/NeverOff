import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Add Check icon import
import_search = "import com.neveroff.util.BounceIconButton"
import_replace = """import com.neveroff.util.BounceIconButton
import androidx.compose.material.icons.filled.Check"""
content = content.replace(import_search, import_replace)

# 2. Collect onlineUsersFromPresence and add LaunchedEffect for scrolling
ui_msg_search = """        UiMessage(
            id = entity.id,
            text = rawText,
            isMine = entity.senderId == myId,
            timestamp = entity.createdAt,
            status = entity.status,
            replyToId = rId,
            isTemp = temp,
            hasPassword = pwd,
            isMedia = media
        )
    }"""

ui_msg_replace = """        UiMessage(
            id = entity.id,
            text = rawText,
            isMine = entity.senderId == myId,
            timestamp = entity.createdAt,
            status = entity.status,
            replyToId = rId,
            isTemp = temp,
            hasPassword = pwd,
            isMedia = media
        )
    }

    val onlineUsersFromPresence by com.neveroff.data.remote.PresenceManager.onlineUsers.collectAsState()

    LaunchedEffect(uiMessages.size) {
        if (uiMessages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }"""
content = content.replace(ui_msg_search, ui_msg_replace)

# 3. Update TopAppBar title to show online status
top_bar_search = """                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = finalRemoteUserName, style = MaterialTheme.typography.titleMedium)"""

top_bar_replace = """                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = finalRemoteUserName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val isOnline = onlineUsersFromPresence.contains(chatDetails?.otherUserId)
                            Text(
                                text = if (isOnline) "в сети" else "не в сети",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }"""
content = content.replace(top_bar_search, top_bar_replace)

# 4. Inject formatTime and StatusTicks checkmarks at the bottom of MessageBubble column
bubble_text_search = """            } else {
                Text(msg.text, color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
            }
        }"""

bubble_text_replace = """            } else {
                Text(msg.text, color = if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = androidx.compose.ui.unit.TextUnit(textSize, androidx.compose.ui.unit.TextUnitType.Sp))
            }

            // Time and Ticks Row
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val labelColor = (if (msg.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.6f)
                Text(
                    text = formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontSize = 9.sp
                )
                if (msg.isMine) {
                    Spacer(modifier = Modifier.width(3.dp))
                    StatusTicks(status = msg.status, color = labelColor)
                }
            }
        }"""
content = content.replace(bubble_text_search, bubble_text_replace)

# 5. Append helper composable/functions at the end of the file
helpers = """
fun formatTime(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(date)
}

@Composable
fun StatusTicks(status: Int, color: Color) {
    if (status == 0) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Check,
            contentDescription = "Sent",
            tint = color,
            modifier = Modifier.size(12.dp)
        )
    } else {
        Box(modifier = Modifier.size(width = 17.dp, height = 12.dp)) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                contentDescription = null,
                tint = if (status == 2) Color(0xFF03A9F4) else color,
                modifier = Modifier
                    .size(12.dp)
                    .offset(x = 5.dp)
            )
        }
    }
}
"""
content += helpers

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)

