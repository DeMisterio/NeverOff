with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Add imports if missing
imports = """
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.neveroff.util.ImageHelper
import java.io.File
"""
if "import androidx.activity.result.contract.ActivityResultContracts" not in content:
    content = content.replace("package com.neveroff.ui.screens\n", "package com.neveroff.ui.screens\n" + imports)

# 2. Add the launcher inside ChatScreen function
launcher_code = """
    val context = LocalContext.current
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                val path = ImageHelper.copyImageToInternalStorage(context, uri, "chat_media")
                if (path != null) {
                    viewModel.sendMediaMessage("IMAGE", File(path)) {}
                }
            }
        }
    )
"""
if "val mediaPickerLauncher" not in content:
    # insert right after "val uiMessages by viewModel.messages.collectAsState()"
    content = content.replace(
        "val uiMessages by viewModel.messages.collectAsState()",
        "val uiMessages by viewModel.messages.collectAsState()\n" + launcher_code
    )

# 3. Wire the Add button
add_btn_search = """BounceIconButton(onClick = { /* Media Attachment TODO */ }) { Icon(Icons.Default.Add, "Attach") }"""
add_btn_replace = """BounceIconButton(onClick = { 
                        mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Icon(Icons.Default.Add, "Attach") }"""

content = content.replace(add_btn_search, add_btn_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
