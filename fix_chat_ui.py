import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Add imports for BottomSheet and Icons
imports = """
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.CircleShape
"""
content = content.replace("import androidx.compose.ui.res.stringResource\n", "import androidx.compose.ui.res.stringResource\n" + imports)

# 2. Add TopAppBar action
top_bar_action = """
                navigationIcon = { BounceIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Settings")
                    }
                }
"""
content = content.replace("navigationIcon = { BounceIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, \"Back\") } }\n            )", top_bar_action + "            )")

# 3. Add state variables at the beginning of ChatScreen
state_vars = """
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val myBubbleColor by com.neveroff.util.SettingsManager.myBubbleColorFlow.collectAsState()
    val otherBubbleColor by com.neveroff.util.SettingsManager.otherBubbleColorFlow.collectAsState()
    val textSize by com.neveroff.util.SettingsManager.bubbleTextSizeFlow.collectAsState()
    val chatTheme by com.neveroff.util.SettingsManager.chatThemeFlow.collectAsState()
"""
content = content.replace("    var isTempMode by remember { mutableStateOf(false) }", "    var isTempMode by remember { mutableStateOf(false) }\n" + state_vars)

# 4. Replace bottomBar
old_bottom_bar = """                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BounceIconButton(onClick = { /* Media Attachment TODO */ }) { Icon(Icons.Default.Add, "Attach") }
                    BounceIconButton(onClick = { isLockedMode = !isLockedMode }) { Icon(Icons.Default.Lock, "Lock", tint = if (isLockedMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    BounceIconButton(onClick = { isTempMode = !isTempMode }) { Icon(Icons.Default.Delete, "Temp", tint = if (isTempMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        placeholder = { Text("Message") }
                    )
                    if (inputText.isNotBlank()) {
                        BounceIconButton(onClick = {
                            var finalMsg = inputText
                            if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg" // Stub for password dialog, will implement later
                            if (isTempMode) finalMsg = "[TEMP]$finalMsg"
                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            
                            viewModel.sendMessage(finalMsg, onComplete = { success -> if (success) { inputText = ""; replyToId = null } })
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }"""

new_bottom_bar = """                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(myBubbleColor)).clickable { /* Media */ },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Upload", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(MaterialTheme.colorScheme.surfaceVariant),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        placeholder = { Text("Message...") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(myBubbleColor)).clickable {
                            if (inputText.isNotBlank()) {
                                var finalMsg = inputText
                                if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                                if (isTempMode) finalMsg = "[TEMP]$finalMsg"
                                if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                                viewModel.sendMessage(finalMsg, onComplete = { success -> if (success) { inputText = ""; replyToId = null } })
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (inputText.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                        } else {
                            Icon(Icons.Default.Mic, "Mic", tint = Color.White)
                        }
                    }
                }"""
content = content.replace(old_bottom_bar, new_bottom_bar)

# 5. Add BottomSheet at the end of ChatScreen
bottom_sheet = """
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())
            ) {
                // Toggles
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Пароль чата (Lock)", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = isLockedMode, onCheckedChange = { isLockedMode = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Исчезающие сообщения", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = isTempMode, onCheckedChange = { isTempMode = it })
                }
                Spacer(modifier = Modifier.height(24.dp))

                // Preview
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.align(Alignment.Start).clip(RoundedCornerShape(percent = 50)).background(Color(otherBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("Привет!", color = Color.White, fontSize = textSize.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(percent = 50)).background(Color(myBubbleColor)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("Привет! Как дела?", color = Color.White, fontSize = textSize.sp)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Размер текста: ${textSize.toInt()}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = textSize,
                    onValueChange = { com.neveroff.util.SettingsManager.setBubbleTextSize(it) },
                    valueRange = 10f..30f
                )

                Spacer(modifier = Modifier.height(24.dp))
                val colorOptions = listOf(0xFF9C27B0, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFFD32F2F, 0xFF3F51B5, 0xFF512DA8)
                Text("Цвет твоих сообщений", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { colorVal ->
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(colorVal))
                                .border(width = if (myBubbleColor == colorVal) 3.dp else 0.dp, color = if (myBubbleColor == colorVal) MaterialTheme.colorScheme.onBackground else Color.Transparent, shape = CircleShape)
                                .clickable { com.neveroff.util.SettingsManager.setMyBubbleColor(colorVal) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("Цвет сообщений собеседника", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { colorVal ->
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(colorVal))
                                .border(width = if (otherBubbleColor == colorVal) 3.dp else 0.dp, color = if (otherBubbleColor == colorVal) MaterialTheme.colorScheme.onBackground else Color.Transparent, shape = CircleShape)
                                .clickable { com.neveroff.util.SettingsManager.setOtherBubbleColor(colorVal) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("Темы чата", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(themeOptions) { t ->
                        FilterChip(
                            selected = chatTheme == t,
                            onClick = { com.neveroff.util.SettingsManager.setChatTheme(t) },
                            label = { Text(text = t) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
"""

content = content.replace("if (passwordDialogOpen) {", bottom_sheet + "\n    if (passwordDialogOpen) {")

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
