import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# Add imports
imports = """
import com.neveroff.ui.components.RecordButton
import com.neveroff.ui.components.RecordMode
import com.neveroff.ui.components.AudioRecordingPanel
import com.neveroff.ui.components.CircularCameraPreview
import com.neveroff.media.MediaRecorderHelper
import com.neveroff.media.CameraRecorderHelper
"""
if "import com.neveroff.ui.components.RecordButton" not in content:
    content = content.replace("package com.neveroff.ui.screens\n", "package com.neveroff.ui.screens\n" + imports)

# Add states
states_search = """    var inputText by remember { mutableStateOf("") }
    var isTempMode by remember { mutableStateOf(false) }"""

states_replace = """    var inputText by remember { mutableStateOf("") }
    var isTempMode by remember { mutableStateOf(false) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var isRecordingLocked by remember { mutableStateOf(false) }
    val audioHelper = remember { MediaRecorderHelper(context) }
    val cameraHelper = remember { CameraRecorderHelper(context) }"""

if "isRecordingAudio" not in content:
    content = content.replace(states_search, states_replace)

# Replace TextField + Send Button area
bottom_bar_search = """                    TextField(
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
                            
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }"""

bottom_bar_replace = """                    if (isRecordingAudio) {
                        AudioRecordingPanel(modifier = Modifier.weight(1f))
                    } else if (isRecordingVideo) {
                        Text("Recording video...", modifier = Modifier.weight(1f).padding(16.dp))
                    } else {
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            placeholder = { Text("Message") }
                        )
                    }

                    RecordButton(
                        isTextBlank = inputText.isBlank(),
                        isLocked = isLockedMode,
                        onSendText = {
                            var finalMsg = inputText
                            if (isLockedMode) finalMsg = "[PWD:0000]$finalMsg"
                            if (isTempMode) finalMsg = "[TEMP]$finalMsg"
                            if (replyToId != null) finalMsg = "$finalMsg::REPLY::$replyToId"
                            viewModel.sendMessage(finalMsg) { if (it) { inputText = ""; replyToId = null } }
                        },
                        onStartRecording = { mode ->
                            if (mode == RecordMode.AUDIO) {
                                isRecordingAudio = true
                                audioHelper.startRecording()
                            } else {
                                isRecordingVideo = true
                                cameraHelper.startRecording { file ->
                                    if (file != null) viewModel.sendMediaMessage("VIDEO", file) {}
                                    isRecordingVideo = false
                                }
                            }
                        },
                        onStopRecording = {
                            if (isRecordingAudio) {
                                isRecordingAudio = false
                                val file = audioHelper.stopRecording()
                                if (file != null) viewModel.sendMediaMessage("VOICE", file) {}
                            }
                            if (isRecordingVideo) {
                                cameraHelper.stopRecording() // callback triggers send
                            }
                            isRecordingLocked = false
                        },
                        onCancelRecording = {
                            if (isRecordingAudio) {
                                isRecordingAudio = false
                                audioHelper.cancelRecording()
                            }
                            if (isRecordingVideo) {
                                isRecordingVideo = false
                                cameraHelper.cancelRecording()
                            }
                            isRecordingLocked = false
                        },
                        onLockRecording = {
                            isRecordingLocked = true
                        }
                    )"""

content = content.replace(bottom_bar_search, bottom_bar_replace)

# Add CameraPreview on top of the list
lazy_col_search = """        LazyColumn("""
lazy_col_replace = """        if (isRecordingVideo) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                CircularCameraPreview(
                    cameraRecorderHelper = cameraHelper,
                    modifier = Modifier.padding(bottom = 80.dp, end = 16.dp)
                )
            }
        }
        LazyColumn("""

if "CircularCameraPreview(" not in content:
    content = content.replace(lazy_col_search, lazy_col_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
