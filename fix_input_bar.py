import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Fix Row background color
row_search = """                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
row_replace = """                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).background(if (currentTheme.name == "Default") MaterialTheme.colorScheme.surfaceVariant else currentTheme.inputBarColor, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
content = content.replace(row_search, row_replace)

# 2. Add buttonColor and onSendTextLongPress to RecordButton
record_btn_search = """                    RecordButton(
                        isTextBlank = inputText.isBlank(),
                        isLocked = isLockedMode,
                        onSendText = {"""
record_btn_replace = """                    RecordButton(
                        isTextBlank = inputText.isBlank(),
                        isLocked = isLockedMode,
                        buttonColor = if (currentTheme.name == "Default") MaterialTheme.colorScheme.primary else currentTheme.iconTint,
                        onSendText = {"""
content = content.replace(record_btn_search, record_btn_replace)

# Also add onSendTextLongPress
longpress_search = """                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        },
                        onStartRecording = {"""
longpress_replace = """                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        },
                        onSendTextLongPress = {
                            var finalMsg = inputText
                            if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                            finalMsg = "[TEMP]$finalMsg"
                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        },
                        onStartRecording = {"""
content = content.replace(longpress_search, longpress_replace)

# 3. Remove 'Исчезающие сообщения' row from Settings
disappear_search = """                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text("Исчезающие сообщения", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.Switch(checked = isTempMode, onCheckedChange = { isTempMode = it })
                }"""
content = content.replace(disappear_search, "")

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
